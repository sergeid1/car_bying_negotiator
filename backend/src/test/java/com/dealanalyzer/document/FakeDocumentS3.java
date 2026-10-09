package com.dealanalyzer.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Offline, directory-backed S3 fake so existing restart/physical-removal fixtures remain meaningful. */
public final class FakeDocumentS3 {
  private FakeDocumentS3() {}

  public static S3Client create(Path root) {
    var client = mock(S3Client.class);
    var bucketCreated = new java.util.concurrent.atomic.AtomicBoolean(Files.exists(root));
    when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> io(() -> {
      var request = call.getArgument(0, PutObjectRequest.class);
      if (request.serverSideEncryption() != ServerSideEncryption.AES256) throw new AssertionError("SSE-S3 required");
      Files.createDirectories(root);
      bucketCreated.set(true);
      try (var input = call.getArgument(1, RequestBody.class).contentStreamProvider().newStream()) {
        Files.write(root.resolve(request.key()), input.readAllBytes());
      }
      return PutObjectResponse.builder().build();
    }));
    when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenAnswer(call -> io(() -> {
      var request = call.getArgument(0, GetObjectRequest.class);
      return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), Files.readAllBytes(root.resolve(request.key())));
    }));
    when(client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> io(() -> {
      Files.deleteIfExists(root.resolve(call.getArgument(0, DeleteObjectRequest.class).key()));
      return DeleteObjectResponse.builder().build();
    }));
    when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenAnswer(call -> io(() -> {
      var request = call.getArgument(0, ListObjectVersionsRequest.class);
      if (Files.notExists(root)) return ListObjectVersionsResponse.builder().isTruncated(false).build();
      try (var files = Files.list(root)) {
        return ListObjectVersionsResponse.builder().isTruncated(false)
            .versions(files.map(path -> path.getFileName().toString())
                .filter(key -> key.startsWith(request.prefix()))
                .map(key -> ObjectVersion.builder().key(key).versionId("null").build()).toList()).build();
      }
    }));
    when(client.headBucket(any(HeadBucketRequest.class))).thenAnswer(call -> {
      if (bucketCreated.get() && Files.notExists(root)) throw S3Exception.builder().statusCode(404).build();
      return HeadBucketResponse.builder().build();
    });
    when(client.getPublicAccessBlock(any(GetPublicAccessBlockRequest.class))).thenReturn(GetPublicAccessBlockResponse.builder()
        .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder().blockPublicAcls(true)
            .ignorePublicAcls(true).blockPublicPolicy(true).restrictPublicBuckets(true).build()).build());
    when(client.getBucketPolicyStatus(any(GetBucketPolicyStatusRequest.class))).thenReturn(GetBucketPolicyStatusResponse.builder()
        .policyStatus(PolicyStatus.builder().isPublic(false).build()).build());
    when(client.getBucketAcl(any(GetBucketAclRequest.class))).thenReturn(GetBucketAclResponse.builder().build());
    when(client.getBucketVersioning(any(GetBucketVersioningRequest.class))).thenReturn(GetBucketVersioningResponse.builder().build());
    return client;
  }

  private interface Operation<T> { T run() throws java.io.IOException; }
  private static <T> T io(Operation<T> operation) {
    try { return operation.run(); }
    catch (java.io.IOException failure) {
      throw software.amazon.awssdk.core.exception.SdkClientException.create("Offline storage failure", failure);
    }
  }
}

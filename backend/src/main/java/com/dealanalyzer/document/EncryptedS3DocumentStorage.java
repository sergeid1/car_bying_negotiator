package com.dealanalyzer.document;

import com.dealanalyzer.buyingcase.persistence.CaseWriteGuard;
import com.dealanalyzer.deployment.DeploymentStage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Component
@ConditionalOnProperty(name = "document.storage.provider", havingValue = "s3")
class EncryptedS3DocumentStorage extends EncryptedDocumentStorage {
  private final S3Client client;
  private final String bucket;

  EncryptedS3DocumentStorage(S3Client client,
      @Value("${document.storage.s3.bucket:}") String bucket,
      @Value("${document.storage.encryption-key:}") String configuredKey,
      CaseWriteGuard guard, Environment environment) {
    super(configuredKey, guard, environment);
    if (bucket.isBlank() || environment.getProperty("document.storage.s3.region", "").isBlank())
      throw new IllegalStateException("document.storage.s3.bucket and document.storage.s3.region are required");
    this.client = client;
    this.bucket = bucket;
    if (DeploymentStage.from(environment).productionLike()) S3BucketSafety.verify(client, bucket);
  }

  @Override void writeCiphertext(String objectKey, byte[] stored) throws IOException {
    try {
      client.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey)
          .contentType("application/octet-stream").serverSideEncryption(ServerSideEncryption.AES256).build(),
          RequestBody.fromBytes(stored));
    } catch (RuntimeException failure) { throw new IOException("Document storage unavailable"); }
  }

  @Override byte[] readCiphertext(String objectKey) throws IOException {
    try {
      return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey).build()).asByteArray();
    } catch (RuntimeException failure) { throw new IOException("Document storage unavailable"); }
  }

  @Override public void delete(String objectKey) {
    try {
      client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
      // Remove actual versions, not just a delete marker. Also works for never-versioned buckets.
      for (var version : versions(objectKey)) {
        if (version.key().equals(objectKey)) client.deleteObject(DeleteObjectRequest.builder()
            .bucket(bucket).key(objectKey).versionId(version.id()).build());
      }
      if (versions(objectKey).stream().anyMatch(version -> version.key().equals(objectKey)))
        throw new IllegalStateException("Artifact removal not verified");
    } catch (RuntimeException failure) {
      throw new IllegalStateException("Unable to remove unreferenced encrypted document");
    }
  }

  @Override public List<String> caseObjectKeys(String caseId) {
    try {
      // Version inventory includes orphaned noncurrent bytes and delete markers after interrupted erasure.
      return versions(caseId + "-").stream().map(Version::key).distinct().sorted().toList();
    } catch (RuntimeException failure) { throw new IllegalStateException("Artifact inventory unavailable"); }
  }

  @Override public boolean available() {
    try { client.headBucket(HeadBucketRequest.builder().bucket(bucket).build()); return true; }
    catch (RuntimeException failure) { throw new IllegalStateException("Artifact storage is unavailable"); }
  }

  private record Version(String key, String id) {}

  private List<Version> versions(String prefix) {
    var result = new ArrayList<Version>();
    String keyMarker = null, versionMarker = null;
    do {
      var page = client.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket).prefix(prefix)
          .keyMarker(keyMarker).versionIdMarker(versionMarker).build());
      page.versions().forEach(version -> result.add(new Version(version.key(), version.versionId())));
      page.deleteMarkers().forEach(marker -> result.add(new Version(marker.key(), marker.versionId())));
      if (!Boolean.TRUE.equals(page.isTruncated())) break;
      if (page.nextKeyMarker() == null || (page.nextKeyMarker().equals(keyMarker)
          && java.util.Objects.equals(page.nextVersionIdMarker(), versionMarker)))
        throw new IllegalStateException("Artifact inventory incomplete");
      keyMarker = page.nextKeyMarker(); versionMarker = page.nextVersionIdMarker();
    } while (true);
    return result;
  }
}

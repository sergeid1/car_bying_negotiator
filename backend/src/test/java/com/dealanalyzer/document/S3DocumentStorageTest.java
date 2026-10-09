package com.dealanalyzer.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.dealanalyzer.buyingcase.persistence.CaseWriteGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class S3DocumentStorageTest {
  @TempDir Path objects;

  @Test void adapterSelectionDefaultsToFileAndSelectsOnlyS3WhenConfigured() {
    var runner = new ApplicationContextRunner().withUserConfiguration(EncryptedFileDocumentStorage.class,
        EncryptedS3DocumentStorage.class, S3DocumentStorageConfiguration.class)
        .withBean(CaseWriteGuard.class, () -> mock(CaseWriteGuard.class))
        .withBean(S3Client.class, () -> FakeDocumentS3.create(objects));
    runner.run(context -> {
      assertThat(context).hasNotFailed().hasSingleBean(DocumentObjectStorage.class);
      assertThat(context.getBean(DocumentObjectStorage.class)).isInstanceOf(EncryptedFileDocumentStorage.class);
    });
    runner.withPropertyValues("document.storage.provider=s3", "document.storage.s3.bucket=private",
        "document.storage.s3.region=us-east-1").run(context -> {
      assertThat(context).hasNotFailed().hasSingleBean(DocumentObjectStorage.class);
      assertThat(context.getBean(DocumentObjectStorage.class)).isInstanceOf(EncryptedS3DocumentStorage.class);
      assertThat(context.getBeansOfType(EncryptedFileDocumentStorage.class)).isEmpty();
    });
    runner.withPropertyValues("document.storage.provider=s3").run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest @ValueSource(strings = {"pilot", "production"})
  void guardedDeploymentAcceptsPrivateBucketAndRefusesMissingPublicOrUninspectableBucket(String stage) {
    var client = FakeDocumentS3.create(objects);
    var environment = environment(stage);
    assertThatCode(() -> storage(client, environment)).doesNotThrowAnyException();
    when(client.headBucket(any(HeadBucketRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
    assertUnsafe(client, environment);
    when(client.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
    when(client.getBucketPolicyStatus(any(GetBucketPolicyStatusRequest.class))).thenReturn(GetBucketPolicyStatusResponse.builder()
        .policyStatus(PolicyStatus.builder().isPublic(true).build()).build());
    assertUnsafe(client, environment);
    when(client.getBucketPolicyStatus(any(GetBucketPolicyStatusRequest.class))).thenThrow(S3Exception.builder().statusCode(403).build());
    assertUnsafe(client, environment);
    when(client.getBucketPolicyStatus(any(GetBucketPolicyStatusRequest.class))).thenThrow(S3Exception.builder().statusCode(404)
        .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder().errorCode("NoSuchBucketPolicy").build()).build());
    assertThatCode(() -> storage(client, environment)).doesNotThrowAnyException();
    when(client.getPublicAccessBlock(any(GetPublicAccessBlockRequest.class))).thenReturn(GetPublicAccessBlockResponse.builder()
        .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder().blockPublicAcls(true)
            .ignorePublicAcls(true).blockPublicPolicy(true).restrictPublicBuckets(false).build()).build());
    assertUnsafe(client, environment);
    when(client.getPublicAccessBlock(any(GetPublicAccessBlockRequest.class))).thenThrow(SdkClientException.create("timeout"));
    assertUnsafe(client, environment);
  }

  @Test void publicAclIsRefusedEvenWithBlockPublicAccess() {
    var client = FakeDocumentS3.create(objects);
    when(client.getBucketAcl(any(GetBucketAclRequest.class))).thenReturn(GetBucketAclResponse.builder()
        .grants(Grant.builder().grantee(Grantee.builder().type(Type.GROUP)
            .uri("http://acs.amazonaws.com/groups/global/AllUsers").build()).permission(Permission.READ).build()).build());
    assertUnsafe(client, environment("pilot"));
  }

  @ParameterizedTest @ValueSource(strings = {"Enabled", "Suspended"})
  void versioningRequiresAnEnabledBucketWideOneDayExpiryWithoutRetainedVersions(String status) {
    var client = FakeDocumentS3.create(objects);
    when(client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
        .thenReturn(GetBucketVersioningResponse.builder().status(status).build());
    when(client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
        .thenReturn(GetBucketLifecycleConfigurationResponse.builder().build());
    assertUnsafe(client, environment("production"));
    var valid = LifecycleRule.builder().id("all-noncurrent").status(ExpirationStatus.ENABLED)
        .filter(LifecycleRuleFilter.builder().prefix("").build())
        .noncurrentVersionExpiration(NoncurrentVersionExpiration.builder().noncurrentDays(1).build()).build();
    for (var invalid : java.util.List.of(valid.toBuilder().status(ExpirationStatus.DISABLED).build(),
        valid.toBuilder().filter(LifecycleRuleFilter.builder().prefix("some-cases-").build()).build(),
        valid.toBuilder().filter(LifecycleRuleFilter.builder().tag(Tag.builder().key("only").value("some").build()).build()).build(),
        valid.toBuilder().filter(LifecycleRuleFilter.builder().objectSizeGreaterThan(100L).build()).build(),
        valid.toBuilder().noncurrentVersionExpiration(NoncurrentVersionExpiration.builder().noncurrentDays(2).build()).build(),
        valid.toBuilder().noncurrentVersionExpiration(NoncurrentVersionExpiration.builder().noncurrentDays(1).newerNoncurrentVersions(1).build()).build())) {
      when(client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
          .thenReturn(GetBucketLifecycleConfigurationResponse.builder().rules(invalid).build());
      assertUnsafe(client, environment("production"));
    }
    when(client.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
        .thenReturn(GetBucketLifecycleConfigurationResponse.builder().rules(valid).build());
    assertThatCode(() -> storage(client, environment("production"))).doesNotThrowAnyException();
  }

  @Test void storesOnlyCiphertextWithSseAndIdenticalIdentifierKeysAndFencesWrites() throws Exception {
    var client = FakeDocumentS3.create(objects);
    var guard = mock(CaseWriteGuard.class);
    var storage = new EncryptedS3DocumentStorage(client, "private", "development-secret-at-least-32-bytes", guard, environment("development"));
    byte[] plaintext = "Buyer name and email must not appear in S3".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var original = storage.store("case-id", "document-id", plaintext);
    assertThat(original.objectKey()).isEqualTo("case-id-document-id.enc");
    assertThat(new String(Files.readAllBytes(objects.resolve(original.objectKey())), java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain(new String(plaintext, java.nio.charset.StandardCharsets.UTF_8));
    assertThat(storage.readOriginal("case-id", "document-id", original.objectKey())).isEqualTo(plaintext);
    var page = storage.storeNormalized("case-id", "document-id", 2, plaintext);
    assertThat(page.objectKey()).isEqualTo("case-id-document-id-page-2.png.enc");
    verify(guard, times(2)).lockActive("case-id");
    doThrow(new IllegalStateException("Case is pending deletion")).when(guard).lockActive("case-id");
    assertThatThrownBy(() -> storage.store("case-id", "late", plaintext)).isInstanceOf(IllegalStateException.class);
    verify(client, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test void inventoriesAllPagesAndDeletesVersionsAndMarkersAndFailsClosedOnDeniedErasure() {
    var client = FakeDocumentS3.create(objects);
    var storage = storage(client, environment("development"));
    when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
        .thenReturn(ListObjectVersionsResponse.builder().versions(ObjectVersion.builder().key("case-doc.enc").versionId("v1").build())
            .isTruncated(true).nextKeyMarker("case-doc.enc").nextVersionIdMarker("v1").build())
        .thenReturn(ListObjectVersionsResponse.builder().deleteMarkers(DeleteMarkerEntry.builder().key("case-doc.enc").versionId("marker").build())
            .isTruncated(false).build());
    assertThat(storage.caseObjectKeys("case")).containsExactly("case-doc.enc");
    verify(client).listObjectVersions(argThat((ListObjectVersionsRequest request) -> "case-doc.enc".equals(request.keyMarker()) && "v1".equals(request.versionIdMarker())));
    when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
        .thenReturn(ListObjectVersionsResponse.builder().versions(ObjectVersion.builder().key("case-doc.enc").versionId("v1").build())
            .deleteMarkers(DeleteMarkerEntry.builder().key("case-doc.enc").versionId("marker").build()).isTruncated(false).build())
        .thenReturn(ListObjectVersionsResponse.builder().isTruncated(false).build());
    storage.delete("case-doc.enc");
    verify(client).deleteObject(argThat((DeleteObjectRequest request) -> "v1".equals(request.versionId())));
    verify(client).deleteObject(argThat((DeleteObjectRequest request) -> "marker".equals(request.versionId())));
    doThrow(S3Exception.builder().statusCode(403).message("secret provider diagnostics").build()).when(client).deleteObject(any(DeleteObjectRequest.class));
    assertThatThrownBy(() -> storage.delete("case-doc.enc")).isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("secret").hasNoCause();
  }

  @Test void failedInventoryAndIncompletePaginationNeverProveAbsence() {
    var client = FakeDocumentS3.create(objects);
    var storage = storage(client, environment("development"));
    when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(ListObjectVersionsResponse.builder().isTruncated(true).build());
    assertThatThrownBy(() -> storage.caseObjectKeys("case")).isInstanceOf(IllegalStateException.class);
    when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenThrow(SdkClientException.create("timeout"));
    assertThatThrownBy(() -> storage.caseObjectKeys("case")).isInstanceOf(IllegalStateException.class).hasNoCause();
  }

  private MockEnvironment environment(String stage) {
    byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
    return new MockEnvironment().withProperty("evidence.deployment", stage)
        .withProperty("document.storage.active-key-id", "active")
        .withProperty("document.storage.keys", "active=" + Base64.getEncoder().encodeToString(key))
        .withProperty("document.storage.s3.region", "us-east-1");
  }
  private EncryptedS3DocumentStorage storage(S3Client client, MockEnvironment environment) {
    return new EncryptedS3DocumentStorage(client, "private", "", mock(CaseWriteGuard.class), environment);
  }
  private void assertUnsafe(S3Client client, MockEnvironment environment) {
    assertThatThrownBy(() -> storage(client, environment)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable or unsafe").hasNoCause();
  }
}

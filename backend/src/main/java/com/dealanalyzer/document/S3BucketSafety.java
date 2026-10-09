package com.dealanalyzer.document;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Read-only startup checks. Never changes bucket policy or hides denied inspection permissions. */
final class S3BucketSafety {
  private S3BucketSafety() {}

  static void verify(S3Client client, String bucket) {
    try {
      client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
      var block = client.getPublicAccessBlock(GetPublicAccessBlockRequest.builder().bucket(bucket).build())
          .publicAccessBlockConfiguration();
      if (block == null || !Boolean.TRUE.equals(block.blockPublicAcls())
          || !Boolean.TRUE.equals(block.ignorePublicAcls()) || !Boolean.TRUE.equals(block.blockPublicPolicy())
          || !Boolean.TRUE.equals(block.restrictPublicBuckets())) throw unsafe();
      try {
        var policy = client.getBucketPolicyStatus(GetBucketPolicyStatusRequest.builder().bucket(bucket).build())
            .policyStatus();
        if (policy == null || !Boolean.FALSE.equals(policy.isPublic())) throw unsafe();
      } catch (S3Exception failure) {
        if (failure.awsErrorDetails() == null
            || !"NoSuchBucketPolicy".equals(failure.awsErrorDetails().errorCode())) throw failure;
      }
      var acl = client.getBucketAcl(GetBucketAclRequest.builder().bucket(bucket).build());
      for (var grant : acl.grants()) {
        String uri = grant.grantee() == null ? null : grant.grantee().uri();
        if ("http://acs.amazonaws.com/groups/global/AllUsers".equals(uri)
            || "http://acs.amazonaws.com/groups/global/AuthenticatedUsers".equals(uri)) throw unsafe();
      }
      var versioning = client.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build());
      // Suspended buckets can still contain old versions; they require the same expiry rule.
      if (versioning.status() == BucketVersioningStatus.ENABLED
          || versioning.status() == BucketVersioningStatus.SUSPENDED) {
        var lifecycle = client.getBucketLifecycleConfiguration(
            GetBucketLifecycleConfigurationRequest.builder().bucket(bucket).build());
        if (lifecycle.rules().stream().noneMatch(S3BucketSafety::coversAllNoncurrentVersions)) throw unsafe();
      } else if (versioning.statusAsString() != null && !versioning.statusAsString().isEmpty()) throw unsafe();
    } catch (RuntimeException failure) {
      // Provider errors can carry bucket names, object keys and request metadata.
      throw unsafe();
    }
  }

  private static boolean coversAllNoncurrentVersions(LifecycleRule rule) {
    var expiry = rule.noncurrentVersionExpiration();
    var filter = rule.filter();
    boolean entireBucket = (rule.prefix() == null || rule.prefix().isEmpty())
        && (filter == null || ((filter.prefix() == null || filter.prefix().isEmpty())
            && filter.tag() == null && filter.and() == null && filter.objectSizeGreaterThan() == null
            && filter.objectSizeLessThan() == null));
    return rule.status() == ExpirationStatus.ENABLED && entireBucket && expiry != null
        && expiry.noncurrentDays() != null && expiry.noncurrentDays() > 0 && expiry.noncurrentDays() <= 1
        && expiry.newerNoncurrentVersions() == null;
  }

  private static IllegalStateException unsafe() {
    return new IllegalStateException("S3 document bucket is unavailable or unsafe: require private access and bucket-wide noncurrent expiry within one day when versioned");
  }
}

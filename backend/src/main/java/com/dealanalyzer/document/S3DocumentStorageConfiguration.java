package com.dealanalyzer.document;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "document.storage.provider", havingValue = "s3")
class S3DocumentStorageConfiguration {
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(S3Client.class)
  S3Client documentS3Client(@Value("${document.storage.s3.region:}") String region) {
    if (region.isBlank()) throw new IllegalStateException("document.storage.s3.region is required");
    // No static credentials: the SDK default chain resolves the deployment's instance/task role.
    return S3Client.builder().region(Region.of(region))
        .httpClientBuilder(UrlConnectionHttpClient.builder()
            .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(15)))
        .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(30))
            .apiCallAttemptTimeout(Duration.ofSeconds(20)))
        .build();
  }
}

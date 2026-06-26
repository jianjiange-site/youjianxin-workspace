package com.dating.common.storage;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

// dating.storage.* → S3Client / S3Presigner / ObjectStorage 三个 bean;
// 必填字段任一空白启动期 fail-fast,避免运行时 NPE。
@AutoConfiguration
@EnableConfigurationProperties(ObjectStorageProperties.class)
@ConditionalOnClass(S3Client.class)
public class ObjectStorageAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public S3Client s3Client(ObjectStorageProperties props) {
        validate(props);
        return S3Client.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .region(Region.of(props.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .forcePathStyle(props.isPathStyleAccess())
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public S3Presigner s3Presigner(ObjectStorageProperties props) {
        validate(props);
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .region(Region.of(props.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.isPathStyleAccess())
                        .build())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectStorage objectStorage(S3Client s3, S3Presigner presigner, ObjectStorageProperties props) {
        return new S3ObjectStorage(s3, presigner, props);
    }

    private static void validate(ObjectStorageProperties p) {
        requireNonBlank(p.getEndpoint(), "endpoint");
        requireNonBlank(p.getRegion(), "region");
        requireNonBlank(p.getAccessKey(), "access-key");
        requireNonBlank(p.getSecretKey(), "secret-key");
        requireNonBlank(p.getBucket(), "bucket");
    }

    private static void requireNonBlank(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("dating.storage." + key + " must be set");
        }
    }
}

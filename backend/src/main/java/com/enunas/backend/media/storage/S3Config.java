package com.enunas.backend.media.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Prod = IAM role via {@link DefaultCredentialsProvider}, no static keys anywhere. Dev/test point
 * {@code enunas.media.endpoint} at LocalStack/S3Mock, which also switches on path-style access.
 */
@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final MediaStorageProperties properties;

    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                // Presigning (S3Presigner below) needs no HTTP client — this one is for the real
                // calls S3Client makes: HeadObject, PutObjectTagging, DeleteObject.
                .httpClient(UrlConnectionHttpClient.create());
        if (usesCustomEndpoint()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        // No SdkHttpClient — presigning is pure local SigV4 computation, no network call happens.
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (usesCustomEndpoint()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    private boolean usesCustomEndpoint() {
        return properties.getEndpoint() != null && !properties.getEndpoint().isBlank();
    }
}

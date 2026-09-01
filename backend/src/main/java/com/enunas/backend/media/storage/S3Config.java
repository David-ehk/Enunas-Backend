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
import java.time.Duration;

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
                // calls S3Client makes: HeadObject, DeleteObjectTagging, DeleteObject.
                //
                // Timeouts are set explicitly, and the socket timeout is deliberately tighter than
                // the SDK's own default. The SDK defaults are already finite (2s connect, 30s
                // read — SdkHttpConfigurationOption.DEFAULT_*), so this is not a fix for a hang;
                // it is about where these calls run. HeadObject and DeleteObjectTagging happen
                // inside the user's confirm request, on a same-region object of known small size,
                // and should finish in tens of milliseconds. At the 30s default, one unhealthy S3
                // connection times up to ~90s of a blocked request thread once the SDK's default
                // three attempts are counted in. 10s is still generous and fails visibly sooner.
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(10))
                        .build());
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

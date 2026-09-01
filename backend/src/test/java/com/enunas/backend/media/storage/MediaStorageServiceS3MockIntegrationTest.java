package com.enunas.backend.media.storage;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles({"test", "mock-payments"})
@Testcontainers
class MediaStorageServiceS3MockIntegrationTest {

    private static final String PRODUCT_BUCKET = "enunas-media-test-products";
    private static final String BRAND_BUCKET = "enunas-media-test-brands";

    @Container
    static final S3MockContainer S3_MOCK =
            new S3MockContainer("latest").withInitialBuckets(PRODUCT_BUCKET + "," + BRAND_BUCKET);

    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("enunas.media.buckets.product", () -> PRODUCT_BUCKET);
        registry.add("enunas.media.buckets.brand", () -> BRAND_BUCKET);
        registry.add("enunas.media.endpoint", S3_MOCK::getHttpEndpoint);
        registry.add("enunas.media.cdn-base-url", () -> "https://cdn.it.local");
    }

    @Autowired private MediaStorageService mediaStorageService;
    @Autowired private S3Client s3Client;

    /**
     * S3Config wires real {@code DefaultCredentialsProvider.create()} beans (correct for
     * production — not overridden here). Credential resolution is deferred until the first
     * real presign/S3 call, so setting these system properties up-front satisfies
     * {@code DefaultCredentialsProviderChain} (which checks {@code aws.accessKeyId} /
     * {@code aws.secretAccessKey} before env vars / {@code ~/.aws} / IMDS) without touching
     * S3Config or wiring a hardcoded credentials object into any bean. Dummy values only —
     * S3Mock does not validate SigV4 signatures.
     */
    @BeforeAll
    static void setDummyCredentials() {
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    @AfterAll
    static void clearDummyCredentials() {
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    @Test
    void presignThenRealPutThenConfirm_roundTrips() throws Exception {
        byte[] body = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);

        MediaStorageService.PresignedUpload upload =
                mediaStorageService.presignUpload(MediaPurpose.PRODUCT_IMAGE, 7L, "image/jpeg", body.length);
        assertThat(upload.key()).startsWith("products/7/images/");

        HttpResponse<Void> putResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(upload.uploadUrl()))
                        .header("Content-Type", "image/jpeg")
                        .header("x-amz-tagging", "media-status=pending")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(putResponse.statusCode()).isBetween(200, 299);

        mediaStorageService.verifyUploaded(upload.key(), MediaPurpose.PRODUCT_IMAGE, 7L);

        var tags = s3Client.getObjectTagging(GetObjectTaggingRequest.builder()
                .bucket(PRODUCT_BUCKET).key(upload.key()).build());
        assertThat(tags.tagSet()).isEmpty();

        mediaStorageService.delete(upload.key());
        assertThatThrownBy(() -> s3Client.headObject(HeadObjectRequest.builder()
                        .bucket(PRODUCT_BUCKET).key(upload.key()).build()))
                .isInstanceOfAny(NoSuchKeyException.class, S3Exception.class);
    }
}

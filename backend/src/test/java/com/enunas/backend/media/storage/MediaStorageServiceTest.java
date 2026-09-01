package com.enunas.backend.media.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaStorageServiceTest {

    private static final String PRODUCT_BUCKET = "enunas-clothing-images";
    private static final String BRAND_BUCKET = "enunas-brand-previews";

    @Mock private S3Client s3Client;

    private MediaStorageService service;

    @BeforeEach
    void setUp() {
        // Real presigner (pure local SigV4 computation, no network) so presign tests exercise the
        // actual signing path; only S3Client (real HTTP calls) is mocked.
        S3Presigner s3Presigner = S3Presigner.builder()
                .region(Region.EU_CENTRAL_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-key", "test-secret")))
                .build();

        MediaStorageProperties properties = new MediaStorageProperties();
        properties.getBuckets().setProduct(PRODUCT_BUCKET);
        properties.getBuckets().setBrand(BRAND_BUCKET);
        properties.setRegion("eu-central-1");
        properties.setPresignTtl(Duration.ofMinutes(10));

        service = new MediaStorageService(s3Client, s3Presigner, properties);
    }

    @Test
    void presignUpload_validRequest_returnsServerGeneratedKeyAndSignedUrl() {
        MediaStorageService.PresignedUpload upload =
                service.presignUpload(MediaPurpose.PRODUCT_IMAGE, 42L, "image/jpeg", 1024L);

        assertThat(upload.key()).matches("products/42/images/[0-9a-f-]{36}\\.jpg");
        assertThat(upload.uploadUrl()).contains(PRODUCT_BUCKET).contains(upload.key());

        // TTL precision: expiresAt must fall within the configured 10-minute window, not just
        // "sometime after now" (which a hardcoded-different duration would also satisfy).
        assertThat(upload.expiresAt())
                .isAfter(Instant.now())
                .isBeforeOrEqualTo(Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));

        // The trust-gap binding: contentType and the pending tag must be part of the SIGNED
        // request, not just present on the object — this is what stops a client from swapping
        // either after receiving the URL. A refactor that dropped .tagging(...)/.contentType(...)
        // before presigning would silently reopen this and no other test would catch it.
        assertThat(upload.requiredHeaders()).containsKeys("content-type", "x-amz-tagging");
        assertThat(upload.requiredHeaders().get("content-type")).isEqualTo("image/jpeg");
        assertThat(upload.requiredHeaders().get("x-amz-tagging")).contains("media-status=pending");
    }

    @Test
    void presignUpload_disallowedContentType_throws() {
        assertThatThrownBy(() ->
                service.presignUpload(MediaPurpose.PRODUCT_IMAGE, 42L, "application/pdf", 1024L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void presignUpload_oversize_throws() {
        assertThatThrownBy(() -> service.presignUpload(
                MediaPurpose.PRODUCT_IMAGE, 42L, "image/jpeg", 11L * 1024 * 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyUploaded_foreignPrefix_throwsSecurityException_andNeverCallsS3() {
        assertThatThrownBy(() ->
                service.verifyUploaded("products/99/images/abc.jpg", MediaPurpose.PRODUCT_IMAGE, 42L))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(s3Client);
    }

    @Test
    void verifyUploaded_objectNeverUploaded_throwsIllegalArgument() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("Not Found").build());

        assertThatThrownBy(() ->
                service.verifyUploaded("products/42/images/abc.jpg", MediaPurpose.PRODUCT_IMAGE, 42L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyUploaded_wrongContentType_throwsIllegalArgument_andNeverClearsTag() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentType("application/pdf").contentLength(1024L).build());

        assertThatThrownBy(() ->
                service.verifyUploaded("products/42/images/abc.jpg", MediaPurpose.PRODUCT_IMAGE, 42L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(s3Client, never()).deleteObjectTagging(any(DeleteObjectTaggingRequest.class));
    }

    @Test
    void verifyUploaded_oversizeObject_throwsIllegalArgument() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentType("image/jpeg").contentLength(11L * 1024 * 1024).build());

        assertThatThrownBy(() ->
                service.verifyUploaded("products/42/images/abc.jpg", MediaPurpose.PRODUCT_IMAGE, 42L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyUploaded_validObject_clearsThePendingTag() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentType("image/jpeg").contentLength(1024L).build());

        service.verifyUploaded("products/42/images/abc.jpg", MediaPurpose.PRODUCT_IMAGE, 42L);

        verify(s3Client).deleteObjectTagging(argThat((DeleteObjectTaggingRequest req) ->
                req.bucket().equals(PRODUCT_BUCKET)
                        && req.key().equals("products/42/images/abc.jpg")));
    }

    @Test
    void delete_callsDeleteObjectWithBucketAndKey() {
        service.delete("products/42/images/abc.jpg");

        verify(s3Client).deleteObject(DeleteObjectRequest.builder()
                .bucket(PRODUCT_BUCKET).key("products/42/images/abc.jpg").build());
    }

    @Test
    void delete_nullKey_isNoOp() {
        service.delete(null);
        verifyNoInteractions(s3Client);
    }

    // ===== Bucket routing — product media and brand previews live in different buckets =====

    @Test
    void presignUpload_brandPurpose_signsAgainstTheBrandBucket() {
        MediaStorageService.PresignedUpload upload =
                service.presignUpload(MediaPurpose.BRAND_LOGO, 7L, "image/png", 1024L);

        assertThat(upload.key()).startsWith("brands/7/logo/");
        assertThat(upload.uploadUrl()).contains(BRAND_BUCKET).doesNotContain(PRODUCT_BUCKET);
    }

    @Test
    void verifyUploaded_brandPurpose_headsAndUntagsInTheBrandBucket() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentType("image/png").contentLength(1024L).build());

        service.verifyUploaded("brands/7/logo/abc.png", MediaPurpose.BRAND_LOGO, 7L);

        verify(s3Client).headObject(argThat((HeadObjectRequest req) -> req.bucket().equals(BRAND_BUCKET)));
        verify(s3Client).deleteObjectTagging(
                argThat((DeleteObjectTaggingRequest req) -> req.bucket().equals(BRAND_BUCKET)));
    }

    /** delete() only ever gets a key, so the bucket has to come back out of the key's prefix. */
    @Test
    void delete_brandKey_targetsTheBrandBucket() {
        service.delete("brands/7/logo/abc.png");

        verify(s3Client).deleteObject(DeleteObjectRequest.builder()
                .bucket(BRAND_BUCKET).key("brands/7/logo/abc.png").build());
    }

    @Test
    void delete_keyWithUnknownPrefix_throwsRatherThanGuessingABucket() {
        assertThatThrownBy(() -> service.delete("elsewhere/1/abc.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no known media scope");
        verifyNoInteractions(s3Client);
    }
}

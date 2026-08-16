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
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
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
        properties.setBucket("enunas-media");
        properties.setRegion("eu-central-1");
        properties.setPresignTtl(Duration.ofMinutes(10));

        service = new MediaStorageService(s3Client, s3Presigner, properties);
    }

    @Test
    void presignUpload_validRequest_returnsServerGeneratedKeyAndSignedUrl() {
        MediaStorageService.PresignedUpload upload =
                service.presignUpload(MediaPurpose.PRODUCT_IMAGE, 42L, "image/jpeg", 1024L);

        assertThat(upload.key()).matches("products/42/images/[0-9a-f-]{36}\\.jpg");
        assertThat(upload.uploadUrl()).contains("enunas-media").contains(upload.key());

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
        verify(s3Client, never()).putObjectTagging(any(PutObjectTaggingRequest.class));
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

        verify(s3Client).putObjectTagging(argThat((PutObjectTaggingRequest req) ->
                req.bucket().equals("enunas-media")
                        && req.key().equals("products/42/images/abc.jpg")
                        && req.tagging().tagSet().isEmpty()));
    }

    @Test
    void delete_callsDeleteObjectWithBucketAndKey() {
        service.delete("products/42/images/abc.jpg");

        verify(s3Client).deleteObject(DeleteObjectRequest.builder()
                .bucket("enunas-media").key("products/42/images/abc.jpg").build());
    }

    @Test
    void delete_nullKey_isNoOp() {
        service.delete(null);
        verifyNoInteractions(s3Client);
    }
}

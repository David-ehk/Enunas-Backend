package com.enunas.backend.media.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Closes the presigned-PUT trust gap in three layers (see design doc §4): the presigned request
 * itself binds content-type/length, the presign TTL is short, and {@link #verifyUploaded} re-checks
 * everything server-side via HeadObject before any DB row is written.
 */
@Service
@RequiredArgsConstructor
public class MediaStorageService {

    /** Signed tag every presigned PUT carries; cleared on confirm. A bucket lifecycle rule expires
     *  anything still tagged this way after 24h — see docs/aws-media-setup.md. */
    static final String PENDING_TAG = "media-status=pending";

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final MediaStorageProperties properties;

    public PresignedUpload presignUpload(MediaPurpose purpose, long resourceId, String contentType,
                                          long contentLength) {
        purpose.validate(contentType, contentLength);
        String key = purpose.generateKey(resourceId, contentType);

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType(contentType)
                .contentLength(contentLength)
                .tagging(PENDING_TAG)
                .build();

        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(properties.getPresignTtl())
                .putObjectRequest(putObjectRequest)
                .build());

        Map<String, String> requiredHeaders = presigned.signedHeaders().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> String.join(",", e.getValue())));

        return new PresignedUpload(key, presigned.url().toString(), presigned.expiration(), requiredHeaders);
    }

    /**
     * The confirm step, folded into the caller's existing create/update call (no separate confirm
     * endpoint — see design doc §4/§6). Verifies the key belongs to this resource, the object was
     * really uploaded, and its real content-type/size match the purpose's policy; then clears the
     * pending lifecycle tag.
     */
    public void verifyUploaded(String key, MediaPurpose purpose, long resourceId) {
        String expectedPrefix = purpose.keyPrefix(resourceId);
        if (!key.startsWith(expectedPrefix)) {
            throw new SecurityException("storageKey does not belong to this resource");
        }

        HeadObjectResponse head;
        try {
            head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
        } catch (S3Exception e) {
            // HeadObject on a missing key inconsistently surfaces as NoSuchKeyException or a bare
            // S3Exception depending on SDK version/backend (no XML body on a HEAD 404 to identify
            // the specific error code from) — check the status code instead of the exception type.
            if (e.statusCode() == 404) {
                throw new IllegalArgumentException("No object was uploaded for key: " + key);
            }
            throw e;
        }

        if (!purpose.allowedContentTypes().contains(head.contentType())) {
            throw new IllegalArgumentException(
                    "Uploaded object has unexpected content type: " + head.contentType());
        }
        if (head.contentLength() == null || head.contentLength() > purpose.maxBytes()) {
            throw new IllegalArgumentException(
                    "Uploaded object exceeds the " + purpose.maxBytes() + " byte limit for " + purpose);
        }

        s3Client.putObjectTagging(PutObjectTaggingRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .tagging(Tagging.builder().tagSet(List.of()).build())
                .build());
    }

    public void delete(String key) {
        if (key == null) {
            return;
        }
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .build());
    }

    public record PresignedUpload(String key, String uploadUrl, Instant expiresAt, Map<String, String> requiredHeaders) {}
}

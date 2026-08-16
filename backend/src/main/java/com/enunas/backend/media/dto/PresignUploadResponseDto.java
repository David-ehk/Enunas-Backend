package com.enunas.backend.media.dto;

import com.enunas.backend.media.storage.MediaStorageService;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

@Getter
@Builder
public class PresignUploadResponseDto {

    private String key;
    private String uploadUrl;
    private Instant expiresAt;
    private Map<String, String> requiredHeaders;

    public static PresignUploadResponseDto from(MediaStorageService.PresignedUpload upload) {
        return PresignUploadResponseDto.builder()
                .key(upload.key())
                .uploadUrl(upload.uploadUrl())
                .expiresAt(upload.expiresAt())
                .requiredHeaders(upload.requiredHeaders())
                .build();
    }
}

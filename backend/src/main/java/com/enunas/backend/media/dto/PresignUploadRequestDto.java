package com.enunas.backend.media.dto;

import com.enunas.backend.media.storage.MediaPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class PresignUploadRequestDto {

    @NotNull
    private MediaPurpose purpose;

    @NotBlank
    private String contentType;

    @Positive
    private long contentLength;
}

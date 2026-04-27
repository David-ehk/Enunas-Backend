package com.enunas.backend.media.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProductImageDto {

    @NotBlank
    private String imageUrl;

    private String altText;

    private boolean primary;

    private int displayOrder;
}

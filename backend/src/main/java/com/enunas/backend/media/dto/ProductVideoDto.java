package com.enunas.backend.media.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProductVideoDto {

    @NotBlank
    private String videoUrl;

    private String title;

    private String thumbnailUrl;
}

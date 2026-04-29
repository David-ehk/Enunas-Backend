package com.enunas.backend.wardrobe.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateWardrobeItemDto {

    private String imageUrl;

    @NotBlank
    private String category;

    private String color;
    private String brand;
    private String styleTag;

    private boolean isPublic;
}

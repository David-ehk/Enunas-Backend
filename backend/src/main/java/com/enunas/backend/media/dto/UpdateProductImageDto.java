package com.enunas.backend.media.dto;

import lombok.Data;

/**
 * PATCH body for image metadata. Every field is optional; an omitted field is left unchanged.
 *
 * <p>Colour: send {@code productColorId} (a positive id) to assign, or {@code unassignColor=true}
 * to move the image back to the shared group. {@code updateMyProfile}/{@code updateVariant} have
 * no field-clearing mechanism at all, so an explicit flag is used rather than a magic value.
 */
@Data
public class UpdateProductImageDto {

    private Long productColorId;
    private Boolean unassignColor;
    private Boolean primary;
    private String altText;
    private Integer displayOrder;
}

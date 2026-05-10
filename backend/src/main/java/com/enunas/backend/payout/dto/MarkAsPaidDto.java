package com.enunas.backend.payout.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class MarkAsPaidDto {

    @NotBlank(message = "Bank transfer reference is required")
    private String externalReference;
}

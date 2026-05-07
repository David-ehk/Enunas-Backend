package com.enunas.backend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;

@Getter
public class MollieConnectDto {

    @NotBlank
    @Pattern(regexp = "org_[a-zA-Z0-9]+", message = "must be a valid Mollie organization ID (org_xxxxx)")
    private String mollieOrganizationId;
}

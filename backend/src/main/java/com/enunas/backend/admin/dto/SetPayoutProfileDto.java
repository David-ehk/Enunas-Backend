package com.enunas.backend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;

@Getter
public class SetPayoutProfileDto {

    @NotBlank
    @Pattern(regexp = "[A-Z]{2}[0-9]{2}[A-Z0-9]{1,30}", message = "must be a valid IBAN")
    private String iban;

    @NotBlank
    private String bankAccountHolder;
}

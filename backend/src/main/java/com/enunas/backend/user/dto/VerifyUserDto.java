package com.enunas.backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VerifyUserDto {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String verificationCode;
}

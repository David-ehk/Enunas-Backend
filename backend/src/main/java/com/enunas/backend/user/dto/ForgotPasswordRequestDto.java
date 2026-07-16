package com.enunas.backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class ForgotPasswordRequestDto {

    @NotBlank
    @Email
    private String email;
}

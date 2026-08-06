package com.enunas.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleAuthDto {

    @NotBlank
    private String idToken;
}

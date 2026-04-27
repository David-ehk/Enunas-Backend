package com.enunas.backend.user.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoginResponseDto {
    private String token;
    private long expiresIn;
}

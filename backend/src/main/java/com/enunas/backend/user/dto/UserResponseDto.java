package com.enunas.backend.user.dto;

import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class UserResponseDto {

    private Long id;
    private String email;
    private Role role;
    private boolean enabled;
    private boolean adminApproved;
    private LocalDateTime createdAt;

    public static UserResponseDto from(User user) {
        return UserResponseDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .enabled(user.isEnabled())
                .adminApproved(user.isAdminApproved())
                .createdAt(user.getCreatedAt())
                .build();
    }
}

package com.enunas.backend.order.dto;

import com.enunas.backend.order.ReturnReason;
import jakarta.validation.constraints.NotNull;

public record ReturnRequestDto(
        Long orderItemId,   // null = full order return; set = single-item return
        @NotNull ReturnReason reason,
        String description
) {}

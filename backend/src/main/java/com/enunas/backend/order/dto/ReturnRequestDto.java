package com.enunas.backend.order.dto;

import com.enunas.backend.order.ReturnReason;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReturnRequestDto(
        Long orderItemId,   // null = full order return; set = single-item return
        @NotNull ReturnReason reason,
        @Size(max = 500) @NoHtml String description
) {}

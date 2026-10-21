package com.enunas.backend.order.dto;

import com.enunas.backend.order.ReturnReason;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ReturnRequestDto(
        Long orderItemId,   // null = full order return; set = single-item return
        @Positive Integer quantity,   // null = all remaining units of the item; needs orderItemId
        @NotNull ReturnReason reason,
        @Size(max = 500) @NoHtml String description
) {
    @AssertTrue(message = "quantity requires orderItemId")
    public boolean isQuantityWithItem() {
        return quantity == null || orderItemId != null;
    }
}

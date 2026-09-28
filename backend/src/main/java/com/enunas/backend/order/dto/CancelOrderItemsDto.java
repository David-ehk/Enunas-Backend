package com.enunas.backend.order.dto;

import com.enunas.backend.order.CancelReason;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Body of POST /admin/orders/{id}/cancel-items. Separate from CancelOrderDto on purpose: a
 * @NotEmpty orderItemIds there would break the whole-order cancel endpoint's validation.
 */
@Getter
@Setter
@NoArgsConstructor
public class CancelOrderItemsDto {

    @NotEmpty
    private List<Long> orderItemIds;

    @NotNull
    private CancelReason reason;

    @Size(max = 500)
    @NoHtml
    private String note;
}

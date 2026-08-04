package com.enunas.backend.order.dto;

import com.enunas.backend.order.validation.ExactlyOneAddressSource;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@ExactlyOneAddressSource
public class CreateOrderDto {

    @NotEmpty
    @Valid
    private List<OrderItemRequestDto> items;

    /**
     * A complete inline shipping address. Exactly one of this or {@code savedAddressId} must be
     * set — enforced by {@link ExactlyOneAddressSource}. Validated identically regardless of how
     * the frontend obtained it — this backend does not know or care whether it came from manual
     * entry, an autocomplete widget, or anything else.
     */
    @Valid
    private ShippingAddressDto shippingAddress;

    /** References one of the caller's saved {@code UserAddress} rows. */
    private Long savedAddressId;

    @Size(max = 1000)
    @NoHtml
    private String notes;

    /** Optional single discount code (max one per order — no stacking). */
    @Size(max = 32)
    @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "must contain only letters, digits, - and _")
    private String discountCode;
}

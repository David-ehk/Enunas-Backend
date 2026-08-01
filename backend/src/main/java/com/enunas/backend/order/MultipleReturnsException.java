package com.enunas.backend.order;

import lombok.Getter;

import java.util.List;

/**
 * Thrown by the deprecated order-scoped return endpoints when the order has more than one return —
 * i.e. it spans several brands. Callers must address a specific return via
 * {@code /admin/returns/{returnNumber}/...} instead; picking one silently is precisely the bug the
 * per-brand split removes.
 *
 * <p>Extends {@link IllegalStateException} so GlobalExceptionHandler already maps it to 409
 * Conflict; the message names the available return numbers so the caller can retry against one.
 */
@Getter
public class MultipleReturnsException extends IllegalStateException {

    private final Long orderId;
    private final List<String> returnNumbers;

    public MultipleReturnsException(Long orderId, List<String> returnNumbers) {
        super("Order " + orderId + " has " + returnNumbers.size() + " brand returns "
                + returnNumbers + " — address one explicitly via /admin/returns/{returnNumber}");
        this.orderId = orderId;
        this.returnNumbers = returnNumbers;
    }
}

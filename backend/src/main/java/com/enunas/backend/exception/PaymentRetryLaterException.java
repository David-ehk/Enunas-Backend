package com.enunas.backend.exception;

/**
 * The provider throttled us (HTTP 429): nothing was created, but this is not a business rejection —
 * the same call will succeed shortly, so callers keep their state and retry later.
 */
public class PaymentRetryLaterException extends PaymentRejectedException {

    public PaymentRetryLaterException(String message, Throwable cause) {
        super(message, cause);
    }
}

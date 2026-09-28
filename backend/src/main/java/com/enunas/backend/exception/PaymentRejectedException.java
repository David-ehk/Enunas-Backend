package com.enunas.backend.exception;

/**
 * The payment provider answered and refused (HTTP 4xx): nothing was created at the provider, so the
 * caller may safely undo its own state. Every other {@link PaymentException} is ambiguous — the
 * provider may have acted — and must never be treated as "nothing happened".
 */
public class PaymentRejectedException extends PaymentException {

    public PaymentRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}

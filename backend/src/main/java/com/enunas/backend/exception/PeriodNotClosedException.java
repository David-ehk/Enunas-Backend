package com.enunas.backend.exception;

/**
 * Thrown when an operation requires a fully-closed calendar month but the period is still
 * running (or in the future). Mapped to HTTP 422 Unprocessable Entity.
 */
public class PeriodNotClosedException extends RuntimeException {
    public PeriodNotClosedException(String message) {
        super(message);
    }
}

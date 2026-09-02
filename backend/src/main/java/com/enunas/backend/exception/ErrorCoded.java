package com.enunas.backend.exception;

/**
 * Marks an exception that carries a stable {@link ErrorCode} for clients to branch on.
 *
 * <p>Deliberately an interface rather than a base class: the exceptions that need a code already
 * extend something meaningful ({@link IllegalStateException} maps to 409 through the existing
 * handler, the way {@code MultipleReturnsException} does), and this lets them keep that status
 * mapping while adding the code. {@code GlobalExceptionHandler} checks for it on the way out, so a
 * new coded exception needs no new handler.
 */
public interface ErrorCoded {

    ErrorCode getErrorCode();
}

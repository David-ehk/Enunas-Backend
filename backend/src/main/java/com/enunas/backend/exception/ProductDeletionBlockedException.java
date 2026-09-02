package com.enunas.backend.exception;

/**
 * A product delete that cannot go ahead, with the reason as a branchable {@link ErrorCode}.
 *
 * <p>Extends {@link IllegalStateException} so it keeps the 409 the existing handler already gives
 * that family — the same trick {@code MultipleReturnsException} uses — and implements
 * {@link ErrorCoded} so the response also carries {@code code}.
 *
 * <p>The distinction matters to the caller, not just to the log: PRODUCT_HAS_LISTINGS is something
 * a brand can clear and retry, PRODUCT_HAS_ORDERS never is. A client that cannot tell them apart
 * ends up offering "delete the listings first" for a product that will refuse the delete no matter
 * what is removed.
 */
public class ProductDeletionBlockedException extends IllegalStateException implements ErrorCoded {

    private final ErrorCode errorCode;

    public ProductDeletionBlockedException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ProductDeletionBlockedException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    @Override
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

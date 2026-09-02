package com.enunas.backend.exception;

/**
 * Stable, machine-readable identifiers for the error responses a client has to branch on.
 *
 * <p>The {@code message} field is written for humans and is expected to be reworded; a frontend that
 * branches on its text breaks silently the first time someone improves the copy. These constants are
 * the contract instead: once published, a value's meaning does not change, and the response keeps
 * carrying it under {@code code}.
 *
 * <p>Only errors a client must actually distinguish get a code. Everything else stays codeless
 * rather than accumulating a constant per throw site — the field is absent from those bodies, not
 * null, so the existing {@code {timestamp, status, error, message, path}} shape is unchanged for
 * every response that had no code to give.
 */
public enum ErrorCode {

    /**
     * The product has been ordered, so it can never be hard-deleted — order history depends on its
     * variants. Archiving is the only exit; a client offering "remove the listings and retry" here
     * is offering a dead end.
     */
    PRODUCT_HAS_ORDERS,

    /**
     * The product still has listings. Recoverable: delete the listings, then retry the product
     * delete, or archive instead.
     */
    PRODUCT_HAS_LISTINGS,

    /**
     * Some other record still references the product. Not resolvable by the brand on its own —
     * archiving is the way forward, and this one is worth surfacing to support.
     */
    PRODUCT_REFERENCED
}

package com.enunas.backend.order;

/**
 * Published inside the approveReturn() transaction; handled AFTER_COMMIT so a mail failure
 * can never roll back an already-committed approval. returnShipToAddress is read from the return's
 * own frozen snapshot, so the listener needs no DB access and never touches the (now-closed)
 * transaction.
 *
 * <p>One event per BRAND return: an order spanning two brands produces two approvals and two
 * emails, each naming its brand and its own destination.
 */
public record ReturnApprovedEvent(
        String buyerEmail,
        String orderNumber,
        String returnNumber,
        String brandName,
        String returnShipToAddress
) {}

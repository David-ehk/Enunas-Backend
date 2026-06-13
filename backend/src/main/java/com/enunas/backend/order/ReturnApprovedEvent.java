package com.enunas.backend.order;

/**
 * Published inside the approveReturn() transaction; handled AFTER_COMMIT so a mail failure
 * can never roll back an already-committed approval. returnShipToAddress is pre-built so the
 * listener needs no DB access and never touches the (now-closed) transaction.
 */
public record ReturnApprovedEvent(
        String buyerEmail,
        String orderNumber,
        String returnNumber,
        String returnShipToAddress
) {}

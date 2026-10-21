package com.enunas.backend.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /admin/returns/{returnNumber}/reconcile-refund. */
public record ReconcileReturnRefundDto(@NotNull Action action, @Size(max = 64) String refundId) {

    /**
     * RECORD: a refund exists at Mollie — settle with it. RELEASE: none exists — undo the claim.
     * REFRESH: re-poll a PENDING refund (no refundId).
     */
    public enum Action { RECORD, RELEASE, REFRESH }
}

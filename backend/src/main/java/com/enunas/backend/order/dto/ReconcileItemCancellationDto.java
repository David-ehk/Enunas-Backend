package com.enunas.backend.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Body of POST /admin/orders/{id}/cancel-items/reconcile (spec D26). */
@Getter
@Setter
@NoArgsConstructor
public class ReconcileItemCancellationDto {

    /** RECORD: a refund exists at Mollie — settle with it. RELEASE: none exists — free the items. */
    public enum Action { RECORD, RELEASE }

    @NotBlank
    private String claimKey;

    @NotNull
    private Action action;

    /** Required for RECORD. */
    @Size(max = 64)
    private String refundId;
}

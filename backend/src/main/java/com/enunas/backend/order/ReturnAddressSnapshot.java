package com.enunas.backend.order;

import com.enunas.backend.brandpartner.BrandReturnAddress;

import java.time.LocalDateTime;

/**
 * A brand return address resolved and frozen at a point in time — the output of
 * {@link ReturnAddressSnapshotFactory}, and the only thing {@link ReturnOrder} is allowed to store.
 * {@code snapshotAt} is authoritative for when the resolution happened; the entity must never
 * generate its own timestamp for this, or "when was this frozen" stops meaning anything.
 */
public record ReturnAddressSnapshot(BrandReturnAddress address, LocalDateTime snapshotAt) {

    public String formatted() {
        return address.formatted();
    }

    public boolean isRoutable() {
        return address.isRoutable();
    }
}

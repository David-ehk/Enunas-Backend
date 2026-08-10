package com.enunas.backend.payout;

/**
 * Which ledger revenue stream a Payout represents. Kept as two separate Payout rows
 * (rather than one row with a breakdown) so the brand receives two distinct bank
 * transfers it can reconcile independently against its own books — merchandise
 * proceeds vs. shipping money collected on its behalf.
 */
public enum PayoutType {
    REVENUE,
    SHIPPING
}

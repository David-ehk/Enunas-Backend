package com.enunas.backend.settlement.accounting;

/** EÜR booking positions derivable from a settlement (spec §3). More may be added later — these
 *  are the minimum the spec requires: platform commission income, and Mollie's fee expense. */
public enum BookingType {
    INCOME_COMMISSION,
    EXPENSE_MOLLIE_FEE
}

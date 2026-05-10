package com.enunas.backend.payout;

public enum PayoutStatus {
    PENDING,    // Generated, waiting for admin approval
    APPROVED,   // Admin confirmed it is correct, ready for bank transfer
    PAID,       // Bank transfer confirmed by admin, ledger updated
    CANCELLED   // Voided (e.g. brand onboarding issue, error in generation)
}

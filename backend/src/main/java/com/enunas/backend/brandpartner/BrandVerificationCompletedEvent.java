package com.enunas.backend.brandpartner;

/**
 * Published after a brand applicant's email verification is durably persisted
 * ({@code User.enabled = true}). Carries everything the listener needs to notify both the
 * applicant (pending-approval email) and the admin (approval-needed email) without further DB
 * access.
 */
public record BrandVerificationCompletedEvent(String applicantEmail, Long applicantUserId, String adminEmail) {
}

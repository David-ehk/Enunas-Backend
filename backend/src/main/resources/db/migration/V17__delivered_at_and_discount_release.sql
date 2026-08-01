-- =============================================================================
-- V17: delivered_at (anchor for the 14-day Widerruf window) and a guard flag so
-- discount-code usage is released at most once per order.
--
-- delivered_at is set by OrderService.updateOrderStatus the moment an order first
-- reaches DELIVERED — the only path into that status (SHIPPED -> DELIVERED). It is
-- NOT carrier delivery confirmation; it is when an admin recorded delivery. See the
-- caveat on OrderService.requestReturn.
--
-- discount_usage_released prevents double-releasing a code's usedCount: an order
-- can be cancelled AND later have its (already-reversed) refund path touched again
-- by a retried admin call, and DiscountCodeRepository.releaseUsage is a conditional
-- decrement, not a natural idempotency key the way externalReferenceId is for refunds.
--
-- Additive only, both nullable/defaulted. V0.0.1..V16 are left untouched (Flyway checksum).
-- =============================================================================

ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivered_at timestamp(6);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS discount_usage_released boolean NOT NULL DEFAULT false;

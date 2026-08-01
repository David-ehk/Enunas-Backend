-- =============================================================================
-- V15: Enforce "at most one ACTIVE return per (order, brand)" at the DB layer.
--
-- WHY: OrderService.requestReturn now merges a brand's newly-requested items into
-- that brand's existing REQUESTED return, and rejects further items for a brand
-- whose return has already moved to APPROVED/RECEIVED. That is an application-level
-- rule, and application-level rules race: two concurrent requestReturn calls for
-- the same order+brand can both read "no active return" and both insert one.
--
-- REFUNDED is the terminal state — once a brand's return is refunded, a later,
-- separate return for that same brand on the same order is legitimate (e.g. a
-- second wave of returns), so the constraint is partial: it only applies to rows
-- that are still open (status <> 'REFUNDED'), not to every row ever created for
-- that (order, brand) pair.
--
-- No legacy data to reconcile — this project is pre-launch, and V14 already backfilled
-- brand_partner_id onto every existing row (at most one per order at backfill time).
-- V0.0.1..V14 are left untouched (Flyway checksum).
-- =============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS uq_returns_active_per_brand
    ON returns (order_id, brand_partner_id)
    WHERE status <> 'REFUNDED';

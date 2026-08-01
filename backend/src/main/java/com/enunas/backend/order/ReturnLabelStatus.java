package com.enunas.backend.order;

/**
 * State of the return shipping label for a single brand's {@link ReturnOrder}.
 *
 * <p>{@code UPLOADED_BY_BRAND} is the current (MVP) path: once a return is APPROVED, the brand
 * supplies a label/tracking number it generated externally (its own DHL/UPS/Hermes account) via
 * {@code POST /brand/returns/{returnNumber}/label}. {@code GENERATED} and {@code FAILED} are
 * reserved for a future carrier-API integration that creates the label programmatically — no such
 * integration exists yet, so nothing in this codebase sets them today; they exist so that adding
 * one later is a new code path, not a schema change.
 */
public enum ReturnLabelStatus {
    /** No label yet — the default state from {@code approveReturn} onward. */
    PENDING,
    /** The brand uploaded a label/tracking number it obtained itself. */
    UPLOADED_BY_BRAND,
    /** Reserved: a future carrier-API integration generated the label programmatically. */
    GENERATED,
    /** Reserved: a future carrier-API generation attempt failed. */
    FAILED
}

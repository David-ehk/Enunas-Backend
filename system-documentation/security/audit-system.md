# Enunas — Audit System

## Overview

Enunas maintains an **immutable financial audit trail** via the `ledger_entries` table. Operational audit information is embedded in entity state fields (timestamps, actor emails, reason codes). The two systems together provide traceability for financial events, order lifecycle changes, and administrative actions.

---

## Financial Audit Trail (LedgerEntry — Immutable)

The `LedgerEntry` table is the primary financial audit store. It is **append-only** — no UPDATE or DELETE operations are ever performed on it.

### Entry Types

| Entry Type | Trigger | Who Creates It |
|-----------|---------|---------------|
| `ORDER_PAYMENT` | Mollie webhook confirms payment | `LedgerService.recordOrderPayment()` |
| `REFUND_REVERSAL` | Admin processes refund | `LedgerService.recordRefund()` |
| `PAYOUT_TRANSFER` | Admin marks payout as paid | `LedgerService.recordPayoutTransfer()` |

### Entry Status Lifecycle

```
ORDER_PAYMENT created
    → status = PENDING_RELEASE
    → payoutEligibleAt = now + 7 days (PAYOUT_HOLD_DAYS env var)

Scheduled job runs
    → LedgerEntry.status → AVAILABLE
    → BrandEconomics.pendingBalance → payoutBalance
    → movedToAvailable = true

Admin generates payout → Admin marks paid
    → LedgerEntry.status → PAID_OUT (via Payout lifecycle)
    → LedgerService.recordPayoutTransfer() creates new PAYOUT_TRANSFER entry

Refund issued
    → New REFUND_REVERSAL entry created (status = REVERSED)
    → reversalOfEntryId → links to original ORDER_PAYMENT entry
    → Original entry NOT modified (audit trail preserved)
```

### LedgerEntry Fields (Full Audit Schema)

| Field | Type | Audit Purpose |
|-------|------|--------------|
| `id` | BIGINT PK | Unique identifier |
| `orderId` | BIGINT | Originating order reference |
| `orderItemId` | BIGINT | Originating line item reference |
| `brandPartnerId` | BIGINT | Tenant isolation key |
| `totalAmount` | DECIMAL(19,2) | Gross payment amount |
| `platformFee` | DECIMAL(19,2) | Commission deducted by platform |
| `brandPayout` | DECIMAL(19,2) | Net amount owed to brand |
| `commissionRate` | DECIMAL(5,4) | Rate at time of transaction (snapshotted) |
| `currency` | VARCHAR(3) | EUR (always) |
| `entryType` | VARCHAR | ORDER_PAYMENT / REFUND_REVERSAL / PAYOUT_TRANSFER |
| `status` | VARCHAR | PENDING_RELEASE / AVAILABLE / REVERSED / PAID_OUT |
| `payoutEligibleAt` | TIMESTAMP | When this entry becomes payable |
| `movedToAvailable` | BOOLEAN | Whether scheduled job released it |
| `reversalOfEntryId` | BIGINT | Links REFUND_REVERSAL to its ORDER_PAYMENT |
| `externalReferenceId` | VARCHAR | Mollie payment/refund ID or bank transfer ref (idempotency key) |

### Idempotency Guards

Financial events include idempotency guards to ensure exactly-once semantics:

```java
// LedgerRepository — prevents duplicate ORDER_PAYMENT entries
boolean existsByOrderIdAndEntryType(Long orderId, EntryType type);

// LedgerRepository — prevents duplicate PAYOUT_TRANSFER entries  
boolean existsByExternalReferenceIdAndEntryType(String ref, EntryType type);

// OrderService — prevents double stock decrement
if (payment.getStatus() == PaymentStatus.PAID) return; // idempotent no-op
```

---

## Order Lifecycle Audit

The `Order` and `Payment` entities track full lifecycle history:

| Field | Entity | Audit Data Captured |
|-------|--------|-------------------|
| `createdAt` | Order | When order was placed |
| `updatedAt` | Order | Last status change timestamp |
| `status` | Order | Current lifecycle state |
| `shippedAt` | Order | When brand confirmed shipment |
| `shippingCarrier` | Order | Which carrier was used |
| `trackingNumber` | Order | Carrier tracking reference |
| `problemReportedAt` | Order | When shipping problem was raised |
| `problemReportedBy` | Order | Who raised the problem (email/identifier) |
| `cancellationReason` | Order | Why order was cancelled |
| `cancellationNote` | Order | Additional cancellation context |
| `cancelledByAdminEmail` | Order | Which admin cancelled the order |
| `paidAt` | Payment | When Mollie confirmed payment |
| `transactionId` | Payment | Mollie's payment identifier (`tr_xxx`) |

### Return Order Audit

| Field | Entity | Audit Data Captured |
|-------|--------|-------------------|
| `createdAt` | ReturnOrder | When return was requested |
| `reason` | ReturnOrder | DEFECTIVE / WRONG_SIZE / etc. |
| `approvedAt` | ReturnOrder | When admin approved the return |
| `receivedAt` | ReturnOrder | When goods were physically received back |
| `status` | ReturnOrder | PENDING / APPROVED / RECEIVED |

---

## Product Moderation Audit

| Field | Captures |
|-------|---------|
| `moderatedBy` (FK to users) | Which admin approved or rejected |
| `moderatedAt` | When moderation occurred |
| `rejectionReason` | Why product was rejected (stored for brand notification) |
| `status` | Current moderation state (ACTIVE / REJECTED / SUSPENDED) |

---

## Payout Audit

| Field | Captures |
|-------|---------|
| `createdAt` | When payout was generated by admin |
| `approvedAt` | When admin approved the payout |
| `approvedByAdminEmail` | Which admin approved |
| `paidAt` | When admin marked the payout as disbursed |
| `paidByAdminEmail` | Which admin confirmed disbursement |
| `externalReference` | Bank transfer reference for bank reconciliation |
| `debtAbsorbed` | How much outstanding brand debt was netted from payout |

---

## Brand Application Audit

| Field | Entity | Captures |
|-------|--------|---------|
| `createdAt` | User | When account was registered |
| `enabled` | User | Whether email was verified |
| `adminApproved` | User | Whether admin approved |
| `status` | BrandPartner | Current brand status (PENDING_REVIEW / ACTIVE / SUSPENDED / REJECTED) |
| `createdAt` / `updatedAt` | BrandPartner | Profile creation and last update |

---

## Reconciliation System

`ReconciliationService` provides drift detection between the immutable ledger and the denormalized `BrandEconomics` read model:

| Operation | Endpoint | Description |
|----------|---------|-------------|
| Check all brands | `GET /admin/reconciliation` | Scans all brands; returns drift report (expected vs actual balance) |
| Check single brand | `GET /admin/reconciliation/brand/{id}` | Single brand drift analysis |
| Rebuild economics | `POST /admin/reconciliation/rebuild/{id}` | Recomputes BrandEconomics from LedgerEntries; non-destructive repair |

**Use case:** If BrandEconomics drifts from LedgerEntries due to a bug, failed transaction, or manual intervention, `rebuildBrandEconomics()` restores correctness without deleting any ledger data.

---

## Admin Action Audit (Partial Implementation)

The following admin actions are traceable through entity fields:

| Action | Traceable Via |
|--------|--------------|
| Order cancellation | `Order.cancelledByAdminEmail` |
| Payout approval | `Payout.approvedByAdminEmail`, `Payout.approvedAt` |
| Payout disbursement | `Payout.paidByAdminEmail`, `Payout.paidAt` |
| Product moderation | `Product.moderatedBy`, `Product.moderatedAt`, `Product.rejectionReason` |
| Brand approval | `User.adminApproved = true`, `BrandPartner.status = ACTIVE` |

**Gap:** There is no dedicated `admin_action_log` table. Admin actions are recorded as state changes on entities, not as discrete event records. This means:
- You can see WHAT the current state is and WHEN/WHO last changed specific fields
- You CANNOT replay the full history of state changes for an entity (no event sourcing)
- For example: if a brand is approved, then suspended, then re-approved, only the final state is queryable — intermediate states are not preserved

---

## Login Audit (Not Implemented)

| Gap | Impact |
|----|--------|
| No login audit log | Cannot detect brute-force attacks or suspicious login patterns |
| No failed login counter | No account lockout mechanism |
| JWT issuance is silent | No record of when tokens were issued |
| No logout event recorded | Sessions expire passively — no active termination log |

**Recommended future improvement:** Log authentication events to a separate `auth_events` table or external logging system (e.g., structured logs to CloudWatch).

---

## Stock Operation Audit (Partial)

`ProductVariant.stockQuantity` is updated atomically but without logging:
- The LedgerEntry links to `orderItemId`, enabling inference of when stock was decremented
- No explicit stock change log exists (`stock_quantity` is a counter column, not an event log)
- Stock restores (from returns or cancellations) are not individually logged

**Impact:** Stock audit must be reconstructed from order and ledger data. Not directly queryable.

---

## Audit Coverage Summary

| Domain | Coverage | Type |
|--------|---------|------|
| Financial transactions | Full (immutable LedgerEntry) | Append-only event log |
| Order lifecycle | Full (entity fields) | State + timestamps |
| Return lifecycle | Full (entity fields) | State + timestamps |
| Payout lifecycle | Full (entity fields + ledger) | State + actor email |
| Product moderation | Partial (last moderation only) | State + last actor |
| Brand application | Partial (current state only) | State |
| Admin actions | Partial (specific actions only) | Actor email on certain fields |
| Login/logout events | None | Not implemented |
| Stock changes | Inferred (via order/ledger) | Not directly logged |

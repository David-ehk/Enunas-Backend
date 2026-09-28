# Frontend handoff — per-item cancellation

Backend feature: an admin can cancel one brand's still-unshipped line items on a **paid**
multi-brand order (refund + ledger reversal + stock restore for exactly those items) while the
rest of the order continues. Design: `docs/superpowers/specs/2026-09-28-per-item-cancellation-design.md`.

All endpoints are admin-only (`/admin/**`, same auth as the existing whole-order cancel).

## 1. Cancel items

`POST /admin/orders/{orderId}/cancel-items`

```json
{ "orderItemIds": [41, 42], "reason": "OUT_OF_STOCK", "note": "optional, max 500 chars, no HTML" }
```

- `reason`: `FRAUD_SUSPICION | OUT_OF_STOCK | CUSTOMER_REQUEST | TECHNICAL_ERROR | OTHER`
- Items must belong to **one brand** and one order. Duplicate ids are counted once.
- The call is synchronous (it waits for the Mollie refund). **Disable the submit button while in flight; do not auto-retry.**
- **200** → body is the full updated `OrderResponseDto`. The items are refunded and settled.

| Status | Meaning | UI suggestion |
|---|---|---|
| 400 | Unknown item / item of another order / items from different brands / invalid body | Show message |
| 400 | Mollie **definitively rejected** the refund. Message says items were NOT cancelled. Nothing changed. | Show message; retry is safe |
| 409 | Order status not cancellable, order not paid yet, item has no recorded paid amount (legacy order), the item's brand already shipped, or an item is already cancelled/being cancelled | Show message; refresh the order |
| 409 | **Refund outcome unknown** (timeout/5xx from Mollie). The items are now `PENDING` | Tell the admin to check Mollie, then use reconcile (section 2). Do not retry the cancel |

The refund amount is what the customer actually paid for the items (after discount). If the
cancel empties a brand, that brand's shipping is refunded in the same refund. A partial cancel
never refunds shipping.

## 2. Reconcile a stuck cancellation

`POST /admin/orders/{orderId}/cancel-items/reconcile`

```json
{ "claimKey": "item-cancel-ENS-2026-ABC123-<uuid>", "action": "RECORD", "refundId": "re_..." }
{ "claimKey": "item-cancel-ENS-2026-ABC123-<uuid>", "action": "RELEASE" }
```

- Use only when an item shows `cancellationState = "PENDING"` **and** the claim is 5 minutes or older. The claim key is on the item (`cancellationClaimKey`).
- `RECORD`: the admin confirmed in Mollie that the refund exists → pass its `refundId` (required, max 64). The backend settles the items. No Mollie call.
- `RELEASE`: the admin confirmed in Mollie that **no** refund exists → items become active again. No Mollie call.
- **200** → updated `OrderResponseDto`.
- **409** → claim under 5 minutes old (still in progress), already settled, claim key not on this order, missing `refundId` for `RECORD`, or the `refundId` is already used by another refund.
- Only show this action to admins, behind a confirmation dialog that tells them to verify in Mollie first.

## 3. New fields on order items (`OrderResponseDto.items[]`)

| Field | Type | Notes |
|---|---|---|
| `cancellationState` | `"ACTIVE" \| "PENDING" \| "CANCELLED"` | `PENDING` = claimed, refund in flight or stuck. `CANCELLED` = refunded and settled |
| `cancelledAt` | ISO datetime \| null | Set from the moment the item is claimed |
| `cancellationReason` | enum \| null | Same values as the request |
| `refundTransactionId` | string \| null | Mollie refund id, only once `CANCELLED` |
| `cancellationClaimKey` | string \| null | Needed for reconcile |

**Brand-scoped order views** (brand partner portal) get only `cancellationState`, `cancelledAt`
and `cancellationReason` — never the refund id or claim key. Treat `PENDING` and `CANCELLED`
items as **do not ship** there. The internal `cancellationNote` is exposed nowhere.

Order totals (`subtotal`, `total`, payment amount) are **not** recomputed: they stay at the
original values. Show cancelled items visibly (struck through / badge) and derive the refunded
amount from the items you are displaying, or from the refund details already shown on the order.

## 4. Order status behaviour

- Cancelling some items leaves the order at its current status (`PAID` / `PARTIALLY_SHIPPED`).
- If the cancel empties a brand and every remaining brand has shipped, the order moves to `SHIPPED`.
- If every item on the order is cancelled, the order becomes `CANCELLED` and any discount code usage is released.
- After any item cancel, the payment status reads `REFUNDED` (same as after a return). The whole-order cancel (`POST /admin/orders/{id}/cancel`) then returns **409** for that order — hide or disable it once any item is `PENDING` or `CANCELLED`.
- A brand whose items are all cancelled can no longer confirm shipment or report a shipping problem (409). Hide those actions for such brands.

## 5. Other changes that affect the UI

- **Returns:** a cancelled or pending item cannot be returned (409 if named). Customer return item pickers should exclude items with `cancellationState != "ACTIVE"`.
- **Dispatch emails** list only non-cancelled items.
- **New emails:** customer ("items cancelled and refunded") and the brand ("do not ship these items") are sent when a cancel settles. Nothing to build in the UI.
- **Deprecated:** `PATCH /admin/orders/{id}/status` with `status=CANCELLED` still works through a shim but is marked for removal. Use `POST /admin/orders/{id}/cancel` (whole order) or `.../cancel-items` instead. Any other status transition is unchanged; `CANCELLED` is no longer a valid target of the normal transition table.
- `Vat22f` export excludes cancelled items (no UI change).

## 6. Suggested admin UI

1. On the order detail page, add a per-item checkbox for active items of brands that have not shipped; enable "Cancel selected items" only when all selected items are from one brand. Reason dropdown + optional note.
2. Show a `PENDING` badge with a "Resolve…" button (reconcile dialog) once the claim is 5+ minutes old; before that show "cancellation in progress".
3. Show a `CANCELLED` badge on settled items.
4. Handle 409 on cancel by refreshing the order and showing the message from the response.

## 7. Deployment note

Two Flyway migrations ship with this backend release: `V35__return_order_version.sql` and
`V36__order_item_cancellation.sql` (additive, nullable columns). Deploy the backend before the
frontend; the old frontend keeps working because the new DTO fields are additive.

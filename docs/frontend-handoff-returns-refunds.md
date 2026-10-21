# Frontend handoff — partial returns, refund preview and exact refunds

Backend feature: a customer (or an admin, as goodwill) can return **some units** of an order line.
The brand confirms the goods are back, the admin sees the **exact refund before releasing it**, and
the refund is always exactly what the customer paid for those units (after discount), plus that
brand's shipping once everything from the brand is back. Design:
`docs/superpowers/specs/2026-10-07-partial-returns-exact-refunds-design.md`.

German UI names: **Retoure genehmigen** (approve), **Ware erhalten** (goods received),
**Erstattung freigeben** (release refund).

## 0. Breaking changes — remove from the frontend

| Removed | Replace with |
|---|---|
| `POST /admin/returns/{n}/refund` (and its `refundAmount` param) | `POST /admin/returns/{n}/release-refund` (section 4) |
| `POST /admin/orders/{id}/return/refund` (deprecated alias) | same |
| Any "refund amount" input field in the admin UI | Nothing — the amount is always calculated. Show the preview (section 3) instead |

A return is **not** `REFUNDED` right after the admin clicks release. It becomes `REFUNDED` only once
Mollie confirms (usually within seconds, via webhook). Until then `refundState = "PENDING"`
(section 6).

## 1. Customer: request a return (now with quantity)

`POST /orders/{orderId}/return` (customer) · `POST /admin/orders/{orderId}/return` (admin goodwill, no 14-day limit)

```json
{ "orderItemId": 41, "quantity": 1, "reason": "WRONG_SIZE", "description": "optional, max 500, no HTML" }
{ "reason": "NO_LONGER_WANTED" }
```

- `orderItemId` omitted → every **remaining** unit of every line, split into one return per brand (as before).
- `quantity` optional (≥ 1), **only together with `orderItemId`**. Omitted → all remaining units of that line.
- Remaining units = ordered quantity − units already on any return. Show the customer a quantity selector capped at that number (compute it from `returns[].items[]`, see section 7).
- Asking again while the brand's return is still `REQUESTED` adds the units to it (same return number).
- `reason`: `WRONG_SIZE | WRONG_COLOR | DAMAGED | DEFECTIVE | NOT_AS_DESCRIBED | NO_LONGER_WANTED | OTHER`

| Status | Meaning |
|---|---|
| 200 | Updated `OrderResponseDto` |
| 400 | More units than remaining, `quantity` without `orderItemId`, `quantity` < 1, `orderItemId` not on this order, invalid body |
| 403 | Customer is not the buyer of this order |
| 409 | All units of that line already on a return; the line was cancelled before shipping; order not delivered; 14-day window over; **every** brand touched by the request already has a return past `REQUESTED` (wait until it is refunded) |

**Whole-order requests on multi-brand orders:** if only *some* brands are blocked (their return is
already past `REQUESTED`), the call still returns **200**. The blocked brands are skipped and no return
is created for them. Compare `returns[]` in the response with what the customer selected, and tell them
which brand has to wait.

## 2. Brand portal: confirm goods received

`POST /brand/returns/{returnNumber}/receive` — **new**, brand partner only, own returns only.

- Allowed when the return is `APPROVED` → becomes `RECEIVED`, stock is restored, `receivedBy = "BRAND"`.
- Also allowed on a `REFUNDED` return that was refunded by admin override before the goods arrived (`receivedAt` is null) → stock restored, status stays `REFUNDED`.
- **200** → the brand-scoped order (same shape as `GET /brand/orders`: only this brand's items/returns).
- **403** another brand's return · **409** wrong status (e.g. not yet approved, already received).
- Button label: **Ware erhalten**. Show it on the brand's returns in state `APPROVED` (and on override-refunded ones without `receivedAt`).

Admin fallback (brand doesn't respond): `POST /admin/returns/{n}/receive` — unchanged, now records `receivedBy = "ADMIN"`.

## 3. Admin: see exactly what will be refunded (preview, read-only)

Nothing is saved, no money moves. The numbers come straight from the order snapshot (what the
customer paid per item, the brand's shipping) and are **exactly** what release will send.

### 3a. One return (one brand)

`GET /admin/returns/{returnNumber}/refund-preview`

```json
{
  "returnNumber": "RET-…",
  "returnStatus": "RECEIVED",
  "refundState": null,
  "brandId": 7,
  "brandName": "BrandA",
  "lines": [
    { "orderItemId": 41, "productName": "Shirt", "color": "Black", "size": "M",
      "unitsReturned": 1, "unitsOrdered": 2, "amount": 107.10 }
  ],
  "itemsAmount": 107.10,
  "shippingAmount": 0.00,
  "total": 107.10,
  "stored": false
}
```

- `shippingAmount` > 0 only when this return brings the **last** remaining unit of that brand back and the brand's shipping was not refunded before.
- `stored: false` → not released yet: this is what **will** be sent. Works for `REQUESTED`, `APPROVED` (override case) and `RECEIVED` returns.
- `stored: true` → already released (`refundState` `PENDING`/`FAILED`/`REFUNDED`): these are the amounts that **were** sent.
- **400** unknown return number · **409** a line is from an old order without per-item prices (legacy): show "manual refund needed".

### 3b. Whole order (several brands)

`GET /admin/orders/{orderId}/refund-preview`

```json
{ "orderId": 12, "orderNumber": "ENS-…",
  "returns": [ { …RefundPreviewDto brand A… }, { …RefundPreviewDto brand B… } ],
  "total": 239.10 }
```

- One entry per brand return whose status is not yet `REFUNDED`. Each has its own items and its own shipping. `total` is the sum of all entries.
- An entry can already be released but not yet confirmed (`stored: true`, `refundState: "PENDING"`). Mark those "wird erstattet", and show "still to refund" as the sum of the `stored: false` entries.
- Use it for the order detail view: group by `brandName`, list `lines`, show shipping per brand, show the totals.
- **404** unknown order.

### 3c. Refund dashboard list

`GET /admin/refunds?state=DUE|IN_PROGRESS|PENDING|FAILED|REFUNDED&page=0&size=20` (size max 100)

Row (`RefundOverviewRowDto`): `returnNumber, orderNumber, brandId, brandName, state, itemsAmount,
shippingAmount, total, releasedBy, overrideReason, claimKey, refundTransactionId, receivedAt,
receivedBy, claimedAt, refundedAt`. Spring `Page` wrapper (`content`, `totalElements`, …).

| `state` | Meaning | Dashboard action |
|---|---|---|
| `DUE` | Goods received, refund not released yet. **Amounts are the preview.** (If a row is a legacy order, its amounts are `null` → "manual refund") | **Erstattung freigeben** |
| `IN_PROGRESS` | Released, waiting for Mollie's answer (or stuck) | After 5 min: reconcile (section 5) |
| `PENDING` | Mollie accepted, confirmation pending | Wait / "Status prüfen" (section 5, REFRESH) |
| `FAILED` | Mollie reported the refund failed | **Erneut senden** (= release again, section 4) |
| `REFUNDED` | Done | — |

Missing/unknown `state` → 400.

**Override returns are not in this list.** `DUE` contains only returns the brand or admin has
confirmed as received. To refund a return that is still `APPROVED` (goods not confirmed, admin
override), open it from the order detail view (preview 3a/3b) and release it there with an
`overrideReason`.

## 4. Admin: release the refund

`POST /admin/returns/{returnNumber}/release-refund`

```json
{}                                        // return is RECEIVED (normal case) — body may be omitted
{ "overrideReason": "Kunde hat Einlieferungsbeleg" }   // return still APPROVED (goods not confirmed) — reason required, max 500
```

- Show the preview (section 3a) in the confirmation dialog: items, shipping, total.
- On a `FAILED` refund the same call **resends** it (same amount, no body needed).
- Synchronous (waits for Mollie). **Disable the button while in flight; never auto-retry.**
- **200** → updated `OrderResponseDto`; the return now has `refundState` `PENDING` or already `REFUNDED`.

| Status | Meaning | UI |
|---|---|---|
| 400 | Mollie definitively rejected the refund (nothing was refunded; retry is safe) — or unknown return number | Show message |
| 409 | Already released / in progress; `APPROVED` without `overrideReason`; return not received; an item cancellation on this order is still in progress; would exceed what the customer paid; **refund outcome unknown** (Mollie timeout) | Show message. For "outcome unknown": tell the admin to check Mollie and reconcile after 5 min |
| 503 | Mollie is rate-limiting | "Bitte in einer Minute erneut versuchen" |

Override (from `APPROVED`): stock is **not** restored; when the goods arrive later the brand/admin can still confirm receipt (section 2).

## 5. Admin: reconcile / refresh

`POST /admin/returns/{returnNumber}/reconcile-refund`

```json
{ "action": "RECORD", "refundId": "re_..." }   // refund exists in Mollie → record it
{ "action": "RELEASE" }                         // no refund exists in Mollie → undo the claim
{ "action": "REFRESH" }                         // re-check a PENDING refund at Mollie
```

- `RECORD` / `RELEASE`: only for `IN_PROGRESS` rows older than 5 minutes. `RECORD` checks the refund belongs to this order's payment and matches the amount; a refund Mollie reports as failed is refused.
- `REFRESH`: only for `PENDING`. Result: `REFUNDED`, `FAILED`, or still `PENDING`.
- **200** → updated `OrderResponseDto` · **400** missing `refundId` for `RECORD` · **409** not stuck yet / not pending / mismatch / refund id already used · **503** Mollie rate-limiting.
- Put RECORD/RELEASE behind a confirmation dialog ("check Mollie first").

## 6. Statuses to display

Return `status` (physical): `REQUESTED → APPROVED → RECEIVED → REFUNDED`.
Return `refundState` (money): `null` (not released) · `PENDING` · `REFUNDED` · `FAILED`.
A released-but-unconfirmed refund keeps `status = RECEIVED` (or `APPROVED` for an override) with `refundState = PENDING`.

Suggested customer wording: `PENDING` → "Erstattung wird bearbeitet", `REFUNDED` → "Erstattet: {refundAmount} €".
The customer email goes out only when the refund is confirmed (`REFUNDED`).

Payment status: `REFUNDED` only when everything paid has been refunded; a partial refund leaves it `PAID`.

## 7. New fields on returns (`OrderResponseDto.returns[]`)

| Field | Type | Who sees it |
|---|---|---|
| `items` | `[{ orderItemId, quantityReturned }]` | all — use this, not `orderItemIds`, for partial returns |
| `orderItemIds` | `number[]` | all — kept for compatibility |
| `refundState` | `null \| "PENDING" \| "REFUNDED" \| "FAILED"` | all |
| `refundAmount` | number \| null | all — total (items + shipping) once released |
| `refundItemsAmount`, `refundShippingAmount` | number \| null | all |
| `receivedBy` | `null \| "BRAND" \| "ADMIN"` | all |
| `refundOverrideReason` | string \| null | **admin only** (null for customer and brand views) |

## 8. Suggested dashboard layout

- **Refund dashboard** (`/admin/refunds`): tabs DUE / IN_PROGRESS / PENDING / FAILED / REFUNDED, columns order, brand, items €, shipping €, total €, received by/at. DUE rows: button **Erstattung freigeben** → dialog with the preview (3a) → release (4).
- **Order detail**: "Erstattungsvorschau" section from 3b, grouped by brand.
- **Brand portal**: on returns in `APPROVED`: **Ware erhalten**.
- **Customer order page**: quantity selector per line when requesting a return; per-return refund status.

# Architecture Gaps — Enunas Platform

This is the **living registry** of all known documentation gaps, duplicate references, endpoint inconsistencies, stale references, and pending TODOs in the Enunas system documentation.

**Maintenance rule:** When a gap is resolved, mark it `[RESOLVED — date]`. Never delete entries.

**Related docs:**
- Permissions source of truth → `../roles/permissions-matrix.csv`
- Platform permissions narrative → `../roles/platform-permissions.md`
- Ownership registry → `../roles/ownership-boundaries.md`
- Entity registry → `../database/entity-boundaries.md`
- ADRs → `decision-log.md`

---

## Gap Severity Scale

| Severity | Meaning |
|----------|---------|
| **CRITICAL** | Documentation contradicts live code — will mislead developers |
| **HIGH** | Missing documentation for existing system behavior |
| **MEDIUM** | Structural improvement needed |
| **LOW** | Nice-to-have — no immediate impact |

---

## Endpoint Inconsistencies — permissions-matrix.csv vs AdminController.java

> **Audit date:** 2026-05-25  
> **Source verified against:** `AdminController.java` (git status: modified)

These inconsistencies mean the `permissions-matrix.csv` cannot be relied upon as accurate for the admin domain. Developers integrating with the admin API must read the source controller directly until these are resolved.

### GAP-001 — Brand Moderation Methods: PATCH vs POST

**Severity:** CRITICAL  
**Status:** Open

| In permissions-matrix.csv | Actual in AdminController.java |
|--------------------------|-------------------------------|
| `PATCH /admin/brands/{id}/approve` | `POST /admin/brands/{brandId}/approve` |
| `PATCH /admin/brands/{id}/reject` | `POST /admin/brands/{brandId}/reject` |
| `PATCH /admin/brands/{id}/suspend` | `POST /admin/brands/{brandId}/suspend` |

**Action required:** Update `permissions-matrix.csv` rows to use `POST` for these three endpoints.

---

### GAP-002 — Payout Approval and Cancel: PATCH vs POST

**Severity:** CRITICAL  
**Status:** Open

| In permissions-matrix.csv | Actual in AdminController.java |
|--------------------------|-------------------------------|
| `PATCH /admin/payouts/{id}/approve` | `POST /admin/payouts/{payoutId}/approve` |
| `PATCH /admin/payouts/{id}/cancel` | `POST /admin/payouts/{payoutId}/cancel` |

**Action required:** Update `permissions-matrix.csv` rows to use `POST` for these two endpoints.

---

### GAP-003 — Payout Mark-Paid Path Mismatch

**Severity:** CRITICAL  
**Status:** Open

| In permissions-matrix.csv | Actual in AdminController.java |
|--------------------------|-------------------------------|
| `POST /admin/payouts/{id}/mark-paid` | `POST /admin/payouts/{payoutId}/paid` |

**Action required:** Update `permissions-matrix.csv` to use `/paid` suffix (not `/mark-paid`).

---

### GAP-004 — Customer Brand Spending Path Mismatch

**Severity:** CRITICAL  
**Status:** Open

| In permissions-matrix.csv | Actual in AdminController.java |
|--------------------------|-------------------------------|
| `GET /admin/customers/{id}/spending` | `GET /admin/customers/{id}/brand-spending` |

**Action required:** Update `permissions-matrix.csv` to use `/brand-spending` suffix.

---

### GAP-005 — Reconciliation Path Structure Mismatch

**Severity:** CRITICAL  
**Status:** Open

| In permissions-matrix.csv | Actual in AdminController.java |
|--------------------------|-------------------------------|
| `GET /admin/reconciliation/brand/{id}` | `GET /admin/reconciliation/{brandId}` |
| `POST /admin/reconciliation/rebuild/{id}` | `POST /admin/reconciliation/{brandId}/rebuild` |

**Action required:** Update `permissions-matrix.csv` to match the actual path structure where `{brandId}` is a top-level segment, not under `/brand/` or `/rebuild/` prefixes.

---

### GAP-006 — Admin Product Update: PUT vs PATCH

**Severity:** HIGH  
**Status:** Open

| In permissions-matrix.csv | Actual in AdminController.java |
|--------------------------|-------------------------------|
| `PUT /admin/products/{id}` | `PATCH /admin/products/{productId}` |

**Action required:** Update `permissions-matrix.csv` to use `PATCH`.

---

### GAP-007 — Missing: GET /admin/orders/{orderId}

**Severity:** HIGH  
**Status:** Open

`GET /admin/orders/{orderId}` is implemented in `AdminController.java` but absent from `permissions-matrix.csv`.

**Action required:** Add row to `permissions-matrix.csv`:
```
Admin Order,Order,View order by ID,GET /admin/orders/{orderId},No,No,No,Yes,None,Full order detail
```

---

### GAP-008 — Orders By Status: Query Param vs Path Variable

**Severity:** HIGH  
**Status:** Open

| In permissions-matrix.csv | Actual in AdminController.java |
|--------------------------|-------------------------------|
| `GET /admin/orders?status=X` (query param filter) | `GET /admin/orders/status/{status}` (path variable) |

The CSV documents this as a query parameter on the list endpoint. The actual implementation is a separate dedicated endpoint with a path variable.

**Action required:** Replace the `GET /admin/orders?status=X` row in `permissions-matrix.csv` with:
```
Admin Order,Order,Filter orders by status,GET /admin/orders/status/{status},No,No,No,Yes,None,Path-variable status filter; separate from list-all endpoint
```

---

## Missing Documentation — Existing System Features

### GAP-009 — BrandAnalytics Entity Undocumented

**Severity:** HIGH  
**Status:** Open

The `BrandAnalytics` entity exists at:
```
backend/src/main/java/com/enunas/backend/brandpartner/brandanalytics/BrandAnalytics.java
```

**Fields:** `id`, `brand` (OneToOne → BrandPartner), `totalViews`, `totalSales`, `revenue`, `conversionRate`

**Missing from:**
1. `database/entity-boundaries.md` — `BrandAnalytics` is not listed in the BRAND DOMAIN section
2. `roles/ownership-boundaries.md` — Ownership of `BrandAnalytics` not declared (presumed: owned by the `BrandPartner`, admin-managed like `BrandEconomics`)
3. `roles/permissions-matrix.csv` — No analytics-read endpoint is documented for brand partners or admin

**analytics-flow.puml** exists in `flows/` but the entity backing it is undocumented in the core reference files.

**Action required:**
- Add `brand_analytics (1:1)` to the BRAND DOMAIN block in `database/entity-boundaries.md`
- Add `BrandAnalytics` row to `roles/ownership-boundaries.md` ownership table
- Verify whether `GET /brand/analytics` or similar endpoint exists and add to `permissions-matrix.csv`

---

### GAP-010 — BrandAnalytics Ownership Not Declared

**Severity:** MEDIUM  
**Status:** Open

Follows from GAP-009. `BrandAnalytics` is a OneToOne satellite of `BrandPartner`, mirroring the pattern of `BrandEconomics`. Presumed ownership:

| Resource | Owner | Assigned By | Immutable? |
|---------|-------|-------------|:---:|
| `BrandAnalytics` | `BrandPartner` | Created alongside `BrandPartner` | ✅ (link immutable; fields updated by system events) |

**Action required:** Add this row to the ownership table in `roles/ownership-boundaries.md` once confirmed.

---

### GAP-011 — No Moderator Role Capability Split Document

**Severity:** LOW  
**Status:** Open (deferred — see ADR-014)

ADR-014 references `backend-documentation/roles/moderator-capabilities.md` as a location for the proposed MODERATOR role capability split. This file does not exist.

**Action required (when MODERATOR role is planned):** Create `roles/moderator-capabilities.md` with the proposed permission split between moderation authority (products, brands) and financial authority (payouts, ledger).

---

## Stale References

### GAP-012 — `backend-documentation/` Folder Does Not Exist

**Severity:** MEDIUM  
**Status:** Open

`README.md` references a sibling folder `backend-documentation/` in two places:
- "This folder is the primary documentation system. `backend-documentation/` (sibling folder) contains backend-specific lower-level detail..."
- `backend-documentation/api/` as the source of truth for API request/response schemas

**Reality:** No `backend-documentation/` directory exists on disk. The reference is stale.

**Action required:** Either:
1. Create `system-documentation/api/` with API schema documentation and remove the stale reference, OR
2. Remove the stale reference and note that API schemas are derived from source code DTOs

---

### GAP-013 — ADR-014 References Non-Existent File

**Severity:** LOW  
**Status:** Open

`decisions/decision-log.md` → ADR-014 states:
> "If MODERATOR role is added: See `backend-documentation/roles/moderator-capabilities.md`"

This path does not exist (see GAP-012). The reference is forward-looking but currently stale.

**Action required:** Update when/if the MODERATOR role is planned.

---

## Structural TODOs

### GAP-014 — Target Folder Structure Partially Unmet

**Severity:** LOW  
**Status:** Deferred

The intended target structure includes folders not yet present:
- `api/` — API documentation (currently addressed by note to `backend-documentation/api/` which doesn't exist)
- `frontend/` — Frontend architecture (currently merged into `architecture/platform-overview.md`)
- `ownership/` — Ownership documentation (currently in `roles/`)
- `diagrams/` — Diagram index (currently distributed across `flows/`, `architecture/`, `cross-system/`, `database/`)

The current structure is fully documented in `README.md` and is functionally coherent. Rearranging would require updating all cross-references. Deferred unless team alignment changes.

**Action required:** Revisit when the documentation grows beyond current scale or team onboarding reveals navigation problems.

---

### GAP-015 — Public Catalog / Backend Auth Tension Unresolved

**Severity:** HIGH  
**Status:** Open (see ADR-017)

ADR-017 documents that the frontend serves catalog pages publicly but the backend `GET /products` requires a JWT. Three resolution options are listed. No decision has been recorded.

**Action required:** When the frontend catalog is launched publicly, record the chosen resolution as a new ADR (ADR-018) and update `security/security-boundaries.md` → Layer 2 to reflect how unauthenticated catalog requests are handled.

---

### GAP-016 — CORS Production Origin Placeholder Not Updated

**Severity:** HIGH  
**Status:** Open

`security/security-boundaries.md` documents:
> "Production `allowedOrigins` must be updated to the actual Vercel domain. The current placeholder `deine-domain.com` must NOT be deployed to production as-is."

No ADR records when/how this was resolved. Before production deployment:
1. Update `SecurityConfiguration.java` CORS origin to actual Vercel domain
2. Add ADR recording the final production domain decision
3. Mark this gap RESOLVED

---

## Resolved Gaps

*(None yet — populate as gaps are closed)*

---

## Gap Summary Table

| Gap ID | Area | Severity | Status |
|--------|------|----------|--------|
| GAP-001 | permissions-matrix.csv — brand moderation HTTP methods | CRITICAL | Open |
| GAP-002 | permissions-matrix.csv — payout HTTP methods | CRITICAL | Open |
| GAP-003 | permissions-matrix.csv — payout mark-paid path | CRITICAL | Open |
| GAP-004 | permissions-matrix.csv — customer brand spending path | CRITICAL | Open |
| GAP-005 | permissions-matrix.csv — reconciliation path structure | CRITICAL | Open |
| GAP-006 | permissions-matrix.csv — admin product update HTTP method | HIGH | Open |
| GAP-007 | permissions-matrix.csv — GET /admin/orders/{id} missing | HIGH | Open |
| GAP-008 | permissions-matrix.csv — orders status filter endpoint type | HIGH | Open |
| GAP-009 | BrandAnalytics entity undocumented in three core files | HIGH | Open |
| GAP-010 | BrandAnalytics ownership not declared | MEDIUM | Open |
| GAP-011 | Moderator capability document missing | LOW | Open (deferred) |
| GAP-012 | backend-documentation/ folder stale reference | MEDIUM | Open |
| GAP-013 | ADR-014 references non-existent file | LOW | Open |
| GAP-014 | Target folder structure partially unmet | LOW | Deferred |
| GAP-015 | Public catalog / backend auth tension unresolved | HIGH | Open |
| GAP-016 | CORS production origin placeholder not updated | HIGH | Open |

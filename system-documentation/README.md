# Enunas — System Documentation

## Purpose

This directory is the **authoritative architecture knowledge base** for the Enunas multi-vendor fashion marketplace. It documents the full platform — frontend, backend, security model, database, ownership rules, API contracts, and flows — as a unified, cross-system reference.

**Source of truth for:**
- Architecture decisions and their rationale
- Security and ownership boundaries
- Role permissions and access control
- Database entity relationships
- System flows and state machines
- Deployment topology

**Not the source of truth for:**
- Implementation detail → read source code
- Deployment runbooks → read `backend/HELP.md` and infrastructure configs
- API request/response schemas → read DTO source files under `backend/src/main/java/com/enunas/backend/*/dto/`

---

## Documentation Hierarchy

This folder is the **primary and sole documentation system**.

> **Note:** `backend-documentation/` was referenced as a planned sibling folder but does not exist on disk. All documentation lives here. See `decisions/architecture-gaps.md` → GAP-012 for resolution options.

**Not the source of truth for API request/response schemas** — read the DTO source files under `backend/src/main/java/com/enunas/backend/*/dto/` directly.

See [decisions/architecture-gaps.md](decisions/architecture-gaps.md) for the complete gap and inconsistency registry.

---

## Architecture Workflow

```
1. Architecture Decision
       ↓
   decisions/decision-log.md     ← record WHY before HOW
       ↓
2. Ownership Validation
       ↓
   roles/ownership-boundaries.md
   security/ownership-enforcement.md
       ↓
3. Flow Design
       ↓
   flows/                        ← sequence diagrams for each domain
       ↓
4. Security Boundary Review
       ↓
   security/security-boundaries.md
   security/tenant-isolation.md
       ↓
5. Implementation
       ↓
   (source code)
       ↓
6. Documentation Update
       ↓
   Update relevant .md, .puml, .mmd files
   Update permissions-matrix.csv if endpoints changed
   Add ADR to decision-log.md if a decision changed
```

---

## Feature Development Workflow

When adding a new feature:

1. **Check ownership rules** — who owns the new resource? → `roles/ownership-boundaries.md`
2. **Check permissions** — which roles need access? → `roles/platform-permissions.md`
3. **Check security boundaries** — which layer enforces it? → `security/security-boundaries.md`
4. **Add to permissions-matrix.csv** — every new endpoint must appear here
5. **Add/update flow diagram** — in `flows/` if the feature has a multi-step lifecycle
6. **Record the decision** — if an architectural choice was made, add an ADR → `decisions/decision-log.md`
7. **Check for cross-system impact** → `cross-system/` diagrams may need updating

---

## Folder Structure

```
system-documentation/
├── README.md                          ← YOU ARE HERE — navigation hub
│
├── architecture/                      ← Platform architecture (frontend + backend)
│   ├── platform-overview.md           ← PRIMARY ENTRY POINT for new team members
│   ├── service-boundaries.md          ← What each service owns and does NOT own
│   ├── system-components.puml         ← Full component diagram
│   ├── deployment-overview.puml       ← Deployment topology
│   └── distributed-system-map.puml    ← Cross-system communication map
│
├── security/                          ← Security model (all six layers)
│   ├── security-boundaries.md         ← PRIMARY SECURITY REFERENCE (6 layers)
│   ├── tenant-isolation.md            ← Multi-tenant isolation per domain
│   ├── ownership-enforcement.md       ← Horizontal privilege escalation prevention
│   └── audit-system.md               ← Financial and operational audit trail
│
├── roles/                             ← RBAC — roles, permissions, ownership
│   ├── platform-permissions.md        ← PRIMARY PERMISSIONS REFERENCE (unified frontend+backend)
│   ├── ownership-boundaries.md        ← Resource ownership map + WHY ownership is immutable
│   ├── role-capabilities.md           ← Detailed capability list per role
│   └── permissions-matrix.csv         ← Machine-readable endpoint × role matrix
│
├── database/                          ← Data model and entity boundaries
│   ├── entity-boundaries.md           ← Domain boundaries and inter-entity rules
│   ├── platform-domain-model.puml     ← Full entity diagram (frontend + backend entities)
│   └── ownership-relations.puml       ← Who owns what at entity level
│
├── flows/                             ← Sequence and state diagrams per business flow
│   ├── full-authentication-flow.puml  ← Login, signup, brand verification, admin approval
│   ├── complete-checkout-flow.puml    ← Order creation → payment → webhook → stock
│   ├── complete-order-lifecycle.puml  ← Order state machine (PENDING → REFUNDED)
│   ├── vendor-product-lifecycle.puml  ← Product creation → moderation → listing
│   ├── admin-management-flow.puml     ← Admin brand/product/order/payout flows
│   ├── notification-system-flow.puml  ← All email notification triggers
│   ├── ownership-flow.puml            ← Resource creation → ownership assertion chain
│   └── analytics-flow.puml           ← Analytics and reporting flows
│
├── cross-system/                      ← Frontend ↔ Backend integration diagrams
│   ├── frontend-backend-sequence.puml ← Page-level API call sequences
│   ├── api-dependency-map.puml        ← Which frontend pages depend on which APIs
│   ├── service-communication.puml     ← Backend service-to-service calls
│   └── infrastructure-map.puml        ← Infrastructure topology (Vercel, AWS, Mollie, SMTP)
│
├── miro-export/                       ← Mermaid diagrams (importable to Miro / GitHub)
│   ├── complete-system.mmd            ← Full system overview
│   ├── auth-platform.mmd             ← Authentication flow
│   ├── ownership.mmd                  ← Ownership model
│   ├── payment-platform.mmd          ← Payment and payout system
│   └── deployment.mmd                ← Deployment architecture
│
└── decisions/                         ← Architecture Decision Records
    ├── decision-log.md                ← All ADRs — WHY decisions were made (ADR-001 → ADR-017)
    └── architecture-gaps.md           ← Live registry: gaps, endpoint mismatches, stale refs, TODOs
```

---

## Entry Points by Role

### New Developer (first week)
1. `architecture/platform-overview.md` — understand the full platform
2. `architecture/service-boundaries.md` — understand what each service owns
3. `roles/platform-permissions.md` — understand who can do what
4. `security/security-boundaries.md` — understand the security model

### Frontend Developer
1. `architecture/platform-overview.md` → Frontend section
2. `cross-system/frontend-backend-sequence.puml` — API call patterns
3. `cross-system/api-dependency-map.puml` — which pages call which endpoints
4. `roles/platform-permissions.md` → Public and CUSTOMER sections

### Backend Developer
1. `architecture/service-boundaries.md` — service ownership rules
2. `database/entity-boundaries.md` — entity domain rules
3. `security/ownership-enforcement.md` — how ownership is enforced in code
4. `flows/complete-checkout-flow.puml` — most complex flow in the system

### Security Reviewer
1. `security/security-boundaries.md` — all six layers
2. `security/tenant-isolation.md` — isolation model
3. `roles/ownership-boundaries.md` — WHY ownership is immutable
4. `security/audit-system.md` — what is and isn't audited

### Admin / Operator
1. `roles/platform-permissions.md` → ADMIN section
2. `flows/admin-management-flow.puml` — admin operation flows
3. `security/audit-system.md` — what actions are traceable
4. `decisions/decision-log.md` — ADR-011: why ledger is append-only

---

## Ownership Model (Summary)

Every resource on the platform has an immutable owner set at creation time. Ownership cannot be transferred because:

1. **Financial audit integrity** — `LedgerEntry` records are indexed by `brandPartnerId`. Moving a product to another brand does not move its ledger history.
2. **Snapshot immutability** — `OrderItem` captures `brandSnapshotName`, `commissionRate`, and `brandPayoutAmount` at checkout. These reference the original owner.
3. **Entity relationship integrity** — `User ↔ BrandPartner` and `User ↔ Customer` are OneToOne, created once, immutable.

**Primary reference:** `roles/ownership-boundaries.md`  
**Enforcement code:** `security/ownership-enforcement.md`  
**ADR:** `decisions/decision-log.md` → ADR-002

---

## Role System (Summary)

Three flat roles. No hierarchy. No role inheritance.

| Role | Entry Point | Login Gate | Scope |
|------|------------|-----------|-------|
| `CUSTOMER` | `/auth/signup` | auto-enabled | shopping, orders, wardrobe |
| `BRAND_PARTNER` | `/brandpartner/apply` | email verified + admin approved | catalog, inventory, own orders |
| `ADMIN` | seeded at startup | auto-enabled + approved | platform-wide, no ownership restrictions |

**Primary reference:** `roles/platform-permissions.md`  
**Machine-readable:** `roles/permissions-matrix.csv`  
**ADR:** `decisions/decision-log.md` → ADR-009

---

## Documentation Maintenance Process

### When to update this documentation

| Event | Files to Update |
|-------|----------------|
| New API endpoint added | `roles/permissions-matrix.csv`, `roles/platform-permissions.md` |
| New role permission added | `roles/platform-permissions.md`, `roles/role-capabilities.md` |
| New entity added | `database/entity-boundaries.md`, `database/platform-domain-model.puml` |
| Ownership rule changed | `roles/ownership-boundaries.md`, `security/ownership-enforcement.md`, `decisions/decision-log.md` (new ADR) |
| Security layer added/changed | `security/security-boundaries.md` |
| New flow implemented | `flows/` (add or update relevant .puml) |
| Architecture decision made | `decisions/decision-log.md` (add ADR) |
| Known gap resolved | `decisions/architecture-gaps.md` (mark RESOLVED) |

### Maintenance rules

1. **Never delete an ADR** — mark it SUPERSEDED with a link to the replacement ADR
2. **Never edit permissions-matrix.csv without checking platform-permissions.md** — they must stay in sync
3. **Never add an endpoint** without adding a row to `permissions-matrix.csv`
4. **Never change an ownership rule** without adding a new ADR
5. **Gaps file is living** — update `decisions/architecture-gaps.md` as issues are resolved
6. **PuML and .mmd files are renderable** — verify diagrams still render after updating

### Rendering PlantUML

All `.puml` files can be rendered via:
- [PlantUML online editor](https://www.plantuml.com/plantuml/uml/)
- IntelliJ IDEA PlantUML plugin
- VS Code PlantUML extension

### Rendering Mermaid

All `.mmd` files can be rendered via:
- GitHub (renders inline in markdown preview)
- [Mermaid live editor](https://mermaid.live)
- Miro (import via Mermaid plugin)

---

## Known Platform Gaps (Current MVP State)

Tracked in: `architecture/platform-overview.md` → Known Platform Gaps section  
Security-specific gaps: `security/security-boundaries.md` → Known Security Gaps section  
Audit gaps: `security/audit-system.md` → Audit Coverage Summary section  
Documentation gaps: `decisions/architecture-gaps.md`

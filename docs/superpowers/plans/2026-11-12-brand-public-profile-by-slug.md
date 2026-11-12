# Brand Public Profile by Slug Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve the storefront brand page `/marken/{slug}` (SEO-friendly, id-free URL) by adding `GET /brands/slug/{slug}/public-profile`, a slug-keyed twin of the existing `GET /brands/{id}/public-profile`.

**Architecture:** Pure reuse. Same controller (`BrandController`), same DTO (`BrandPublicProfileDto`), same ACTIVE-only gate, same `MediaUrlResolver`. The only new lookup is the existing `BrandPartnerRepository.findBySlug`. No security change: `GET /brands/**` is already `permitAll` (`SecurityConfiguration.java:91`) and already micro-cached (`MicroCacheFilter.java:28`).

**Tech Stack:** Java 21, Spring Boot 4, Spring Data JPA, JUnit 5 + AssertJ integration tests (Testcontainers).

**Spec:** This conversation. The frontend's requirements were: public, ACTIVE-only (others 404), storefront-safe fields only, `null` (not `""`) for missing images, logo/hero URLs from the brand-previews bucket. The existing id endpoint already satisfies every one of these.

## Global Constraints

- Run under `ponytail`: shortest diff, no new DTO/controller/resolver/security rule.
- Non-ACTIVE and unknown slugs → **404**, indistinguishable from each other (same `BrandNotFoundException` path as the id route).
- Response body is exactly `BrandPublicProfileDto`, built with `BrandPublicProfileDto.from(brand, mediaUrlResolver)`.
- No schema change and no Flyway migration.

## Review Focus

1. **Path collision:** `/brands/slug/{slug}/public-profile` must not be swallowed by `/brands/{id}/public-profile`. The id route's `{id}` is a `Long` and `slug` sits in a different segment position, so they can't collide. A test on the new route proves it routes.
2. **Suspended or pending brand by slug** → 404, never 200. Covered by the pending test in Task 1.
3. **Sensitive fields** must stay absent on the slug route too. Covered by a field-absence test in Task 1.
4. **Case / unknown slug** (`/brands/slug/does-not-exist/public-profile`) → 404, not 500. Covered by a test in Task 1.
5. **Missing images** → `logoUrl`/`heroImageUrl` are JSON `null`. The seeded brand has no storage keys, so assert both are `null` in the happy-path test.

---

### Task 1: Slug-keyed public profile endpoint

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/brandpartner/BrandController.java` (add one `@GetMapping`)
- Modify: `backend/src/main/java/com/enunas/backend/brandpartner/BrandPartnerService.java:323` (add a sibling method next to `getPublicProfile(Long)`)
- Test: `backend/src/test/java/com/enunas/backend/brandpartner/integration/BrandPublicProfileIntegrationTest.java` (add tests to the existing class; reuse `seedActiveBrand()`, which seeds brandName `"Alpha"`, slug `"alpha"`)

**Interfaces:**
- Consumes: `BrandPartnerRepository.findBySlug(String): Optional<BrandPartner>` (exists), `BrandPublicProfileDto.from(BrandPartner, MediaUrlResolver)` (exists), `BrandNotFoundException(String)` (exists, maps to 404).
- Produces: `BrandPartnerService.getPublicProfileBySlug(String slug): BrandPublicProfileDto`, and `GET /brands/slug/{slug}/public-profile` → `ResponseEntity<BrandPublicProfileDto>`.

- [ ] **Step 1: Write the failing tests** (in `BrandPublicProfileIntegrationTest`)

```java
@Test
void activeBrand_publicProfileBySlugIsReachableAnonymously() {
    long brandId = seedActiveBrand().getId();
    ResponseEntity<Map> response =
            rest.getForEntity("/brands/slug/{slug}/public-profile", Map.class, "alpha");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> body = response.getBody();
    assertThat(((Number) body.get("id")).longValue()).isEqualTo(brandId);
    assertThat(body.get("brandName")).isEqualTo("Alpha");
    assertThat(body.get("slug")).isEqualTo("alpha");
    assertThat(body).containsEntry("logoUrl", null).containsEntry("heroImageUrl", null);
}

@Test
void activeBrand_publicProfileBySlugNeverExposesSensitiveFields() {
    seedActiveBrand();
    Map<String, Object> body =
            rest.getForEntity("/brands/slug/{slug}/public-profile", Map.class, "alpha").getBody();
    // same key list as activeBrand_publicProfileNeverExposesSensitiveFields
    assertThat(body).doesNotContainKeys("vatId", "taxNumber", "legalName", "contactEmail",
            "userEmail", "userId", "addressStreet", "addressPostalCode", "addressCity",
            "addressCountry", "returnStreet", "returnPostalCode", "returnCity", "returnCountry",
            "returnRecipient", "returnInstructions", "effectiveReturnAddress", "approved", "status");
}

@Test
void pendingReviewBrand_publicProfileBySlugIsNotFound() {
    // seed exactly like pendingReviewBrand_publicProfileIsNotFound (slug "pending-co")
    // GET /brands/slug/pending-co/public-profile → 404
}

@Test
void unknownSlug_publicProfileIsNotFound() {
    // GET /brands/slug/does-not-exist/public-profile → 404
}
```

(Add `@SuppressWarnings("unchecked")` where the existing tests use it.)

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `backend/`): `./mvnw test -Dtest=BrandPublicProfileIntegrationTest`
Expected: the 2 happy-path tests FAIL. Without the route the request falls through to a 404 or 500. The two 404 tests may already pass, which is fine: they become regression guards.

- [ ] **Step 3: Implement**

- `BrandPartnerService.getPublicProfileBySlug(String slug)`: `@Transactional(readOnly = true)`, `findBySlug(slug).filter(b -> b.getStatus() == BrandStatus.ACTIVE).orElseThrow(() -> new BrandNotFoundException("Brand not found with slug: " + slug))`, return `BrandPublicProfileDto.from(brand, mediaUrlResolver)`. Mirrors `getPublicProfile(Long)` line for line.
- `BrandController`: `@GetMapping("/slug/{slug}/public-profile")` → `ResponseEntity.ok(brandPartnerService.getPublicProfileBySlug(slug))`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=BrandPublicProfileIntegrationTest`
Expected: all tests PASS, including the 5 pre-existing ones.

Then the full suite: `./mvnw test`. Expected: BUILD SUCCESS. (On a mass `NoClassDefFoundError`, suspect OneDrive file locks, not this change. Re-run without `clean`.)

- [ ] **Step 5: Hand back for commit**

Do NOT commit. The user stages, commits and pushes. Suggested message:

```
feat(brand): public brand profile lookup by slug

GET /brands/slug/{slug}/public-profile — slug-keyed twin of the id route so the
storefront can serve SEO-friendly /marken/{slug} URLs. Same ACTIVE-only gate,
same storefront-safe DTO, already public under /brands/**.
```

## After deploy (manual check)

`curl -s https://api.enunas.com/brands/slug/claude-testmarke-0109/public-profile` with no token → 200, with `logoUrl`/`heroImageUrl` on `enunas-brand-previews-…`. Then the frontend switches `/marken/{slug}` to this route and removes its stub.

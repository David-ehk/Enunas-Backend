# Backend request: public brand-profile endpoint

**Status:** open — not yet implemented (documented, no code changed).

## Symptom

`GET /brandpartner/5` → `401 Unauthorized` when called without auth (a customer-facing brand
page, a logged-out visitor, etc.).

## Root cause

There is no public brand-profile read path. `/brandpartner/{id}` is the only by-ID lookup that
exists, and it's locked down at two layers:

- `SecurityConfiguration` maps the whole prefix to brand/admin roles only:
  `.requestMatchers("/brandpartner/**").hasAnyRole("BRAND_PARTNER", "ADMIN")` — so even a logged-in
  `CUSTOMER` gets `403`, not just an anonymous visitor getting `401`.
- The controller method itself additionally declares `@PreAuthorize("isAuthenticated()")`
  (`BrandPartnerController.getBrandById`), which would still block anonymous callers even if the
  filter-chain rule were loosened.

So today there is no role, authenticated or not, that can read a brand's profile through a public
storefront-style call — this isn't a config oversight in one place, it's two independent gates.

## Why not just relax the existing endpoint

`BrandPartnerResponseDto` (what `/brandpartner/{id}` returns) is the brand-partner's own full
profile shape and carries fields that must never be public:

- `vatId`, `taxNumber`, `legalName`
- `addressStreet` / `addressPostalCode` / `addressCity` / `addressCountry`
- `returnStreet` / `returnPostalCode` / `returnCity` / `returnCountry` / `returnRecipient` /
  `returnInstructions` / `effectiveReturnAddress`
- `contactEmail`, `userId`, `userEmail`
- `approved`, `status`

Dropping `@PreAuthorize`/the role restriction on this endpoint would leak all of that. A public
endpoint needs its own narrower response shape, not a loosened guard on this one.

## Requested change

A new, genuinely public endpoint with its own DTO:

```
GET /brands/{id}/public-profile   (or GET /brands/{id} — either is fine, pick whichever reads
                                    better alongside the existing /brandpartner and /brands* routes)
```

**Suggested fields** (`BrandPublicProfileDto` or similar):

```
id, brandName, slug, description, logoUrl, heroImageUrl,
websiteUrl, instagramHandle, tiktokHandle, country
```

Everything else in the list above stays off the public DTO.

**Visibility gate:** only return brands that are `approved == true` and in an active/live status —
mirror the pattern already used for storefront product/listing visibility (e.g.
`ProductRepository.search`'s `EXISTS` gate on active listings) rather than inventing a new rule.
A pending/rejected/unapproved brand ID should 404, not leak its existence.

**Routing note:** don't just add this under `/brandpartner/**` — that prefix is already mapped to
`hasAnyRole("BRAND_PARTNER", "ADMIN")` in `SecurityConfiguration`, so a new sub-path there would
inherit that restriction and need an explicit carve-out ordered *above* it (first-match-wins, same
pattern already used for `/auth/set-password` above the `/auth/**` permitAll). Using a distinct
`/brands/**` prefix with its own `permitAll()` entry avoids that ordering trap entirely.

## Where this lands

- New DTO: `brandpartner/dto/BrandPublicProfileDto.java`
- New read method: `BrandPartnerService` (or a new `BrandController`/`BrandService` if the team
  wants public brand reads kept out of the brand-partner-authenticated package)
- New route + `permitAll()` entry in `SecurityConfiguration`

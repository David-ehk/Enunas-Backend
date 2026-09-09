# Frontend Integration Guide — S3 Media Storage

What the brand-partner (and, minimally, admin) dashboards need to change to work with the new
presigned-upload media backend. Companion to `docs/superpowers/specs/2026-08-13-s3-media-storage-design.md`
and `docs/aws-media-setup.md`.

## The paradigm shift

**Before:** the brand typed/pasted a free-text image URL into a form field; the backend stored that
string verbatim.

**After:** the browser uploads bytes **directly to S3** using a short-lived presigned URL the backend
hands out. The backend never sees the file. Every existing "paste a URL" field is replaced by a
3-step upload flow, and every existing "read a URL" field is **unchanged in shape** — `imageUrl`,
`videoUrl`, `thumbnailUrl`, `logoUrl` all still come back as full resolved URLs on every GET response,
you just can't write them directly anymore.

**Scope:** only the **brand-partner dashboard** uploads media. The **admin dashboard** is read-only
here — nothing breaks, one field (`heroImageUrl`) is newly available if you want to surface it.

---

## The 3-step upload flow (same shape everywhere)

1. **Ask for a presigned URL** — `POST` one of the two `upload-url` endpoints below with the file's
   `purpose`, `contentType`, and `contentLength` (byte size). Get back `{ key, uploadUrl, expiresAt,
   requiredHeaders }`.
2. **PUT the file bytes straight to S3** — `fetch(uploadUrl, { method: 'PUT', body: file, headers:
   requiredHeaders })`. **You must send every header in `requiredHeaders` exactly as given**, or S3
   rejects the upload with an opaque `403 SignatureDoesNotMatch` (a naive `fetch` with no headers
   object will NOT automatically send `Content-Type` or `x-amz-tagging` — this is the #1 thing that
   will silently break if skipped). `uploadUrl` expires 10 minutes after issuance (`expiresAt`).
3. **Confirm** — call the existing create/update endpoint (`POST .../media/images`, `PATCH
   /brandpartner/me`, etc.) with the `key` from step 1 as `storageKey` (not a URL). The backend
   verifies the object was really uploaded before writing the DB row. There is no separate "confirm"
   endpoint — this step reuses the endpoints you already call today, just with a different field.

```ts
// Generic helper, reusable for every upload case below
async function uploadToS3(uploadUrl: string, file: File, requiredHeaders: Record<string, string>) {
  const res = await fetch(uploadUrl, { method: 'PUT', body: file, headers: requiredHeaders });
  if (!res.ok) throw new Error(`S3 upload failed: ${res.status}`);
}
```

## `purpose` values — pick the right one, client-side-validate against these before presigning

| `purpose` | Used for | Allowed `contentType` | Max `contentLength` |
|---|---|---|---|
| `PRODUCT_IMAGE` | product gallery images | `image/jpeg`, `image/png`, `image/webp` | 10 MB |
| `PRODUCT_VIDEO` | product videos | `video/mp4`, `video/webm` | 200 MB |
| `VIDEO_THUMB` | video thumbnail image | `image/jpeg`, `image/png`, `image/webp` | 2 MB |
| `BRAND_LOGO` | brand logo | `image/jpeg`, `image/png`, `image/webp` | 5 MB |
| `BRAND_HERO` | brand hero banner | `image/jpeg`, `image/png`, `image/webp` | 10 MB |

Sending a disallowed `contentType` or an over-limit `contentLength` gets a `400` from the presign
call itself (before any S3 interaction) — validate client-side too so the user isn't surprised by a
network round-trip failure on an obviously-wrong file.

---

## Product images (brand-partner dashboard)

### Get a presigned URL
```
POST /products/{productId}/media/upload-url
Authorization: Bearer <token>   (BRAND_PARTNER, must own the product)

{ "purpose": "PRODUCT_IMAGE", "contentType": "image/jpeg", "contentLength": 482913 }

200 → { "key": "products/42/images/9c2e...-4a1b.jpg",
        "uploadUrl": "https://enunas-media.s3.eu-central-1.amazonaws.com/...(signed)...",
        "expiresAt": "2026-08-14T22:10:00Z",
        "requiredHeaders": { "content-type": "image/jpeg", "x-amz-tagging": "media-status=pending", ... } }
```

### Confirm — create the image row
```
POST /products/{productId}/media/images
Authorization: Bearer <token>

{ "storageKey": "products/42/images/9c2e...-4a1b.jpg",   // was: "imageUrl": "<pasted url>"
  "altText": "Front view",
  "primary": true,
  "displayOrder": 0 }

201 → { "id": 7, "imageUrl": "https://cdn.enunas.com/products/42/images/9c2e...-4a1b.jpg",
         "altText": "Front view", "primary": true, "displayOrder": 0, "createdAt": "..." }
```
**`GET /products/{productId}/media/images` is unchanged** — still returns `imageUrl` as a full URL,
just now CDN-resolved server-side instead of whatever string was originally pasted. No read-side
changes needed.

`DELETE /products/{productId}/media/images/{imageId}` — unchanged, no request/response shape change
(now also deletes the underlying S3 object server-side, invisible to the frontend).

## Colourway-specific product images

An image can now be tagged to one colourway, or left **shared** (shown for every colourway). The
PDP gallery for a selected swatch = *its tagged images* + *all shared images*.

### New / changed fields

- `POST /products/{productId}/media/images` — body accepts optional `productColorId` (a
  `ProductColor` id from `GET /products/{id}` → `colors[]`). Omit it for a shared image.
- `PATCH /products/{productId}/media/images/{imageId}` — **new.** Partial update of image
  metadata. Body: `{ productColorId?, unassignColor?, primary?, altText?, displayOrder? }`.
  - `productColorId: <id>` reassigns the image to that colourway.
  - `unassignColor: true` moves it back to the shared group.
  - `primary: true` makes it the cover for its (post-update) colour group, demoting the previous
    one in that group only.
  - Omitted fields are unchanged. Sending a `productColorId` from another product → `403`.
  - Changing the colour drops `primary` unless `primary: true` is also sent.
- `GET /products/{productId}/media/images?colorId={id}` — optional filter. Returns the
  colourway's own images **plus** shared images, primary-first. No param → all images (use this
  in the vendor dashboard).
- `GET /products/{productId}/media/images` responses now include `productColorId` (nullable) and
  `color` (nullable) on each image.
- `GET /products/{id}` (`ProductResponseDto`) gains `colors: [{ id, color, colorFamily, sku }]`.
  Each entry in `images[]` carries `productColorId` + `primary`. `variants[]` and listing
  responses gain `colorId`.

### Vendor dashboard UX (specified — do not redesign)

1. **Grouped upload.** The "Produktbilder" section renders as colour groups: `Alle Farben
   (geteilt)` plus one group per entry in `colors[]`. An upload targets the active group — confirm
   with that group's `productColorId` (or none for `Alle Farben`). Moving an image between groups
   (drag / dropdown) → `PATCH` with `productColorId: <id>` or `unassignColor: true`. Each group has
   its own "Als Titelbild setzen" → `PATCH { primary: true }`.
2. **Existing products.** Untagged images appear under `Alle Farben (geteilt)` with the hint
   *"Diese Bilder werden für alle Farbvarianten angezeigt."* No migration — splitting them is
   drag-into-a-colour-group. When a product has ≥2 colourways and ≥1 shared image, show a
   one-line, non-blocking nudge to assign colour-specific photos.
3. **Shared is acceptable.** A colourway with no own images is valid and shippable — it shows the
   shared set. You MAY show an advisory hint; you MUST NOT block listing activation or checkout on
   it.

### PDP

Filter client-side: show an image when `img.productColorId === selectedColorId || img.productColorId == null`.
The listing / search card picks the primary whose `productColorId` matches the card's colour
(fall back to the shared primary, then the first image).

## Product videos (brand-partner dashboard)

Same pattern, two presign calls if you have a thumbnail (`purpose: "PRODUCT_VIDEO"` for the video,
`purpose: "VIDEO_THUMB"` for the thumbnail — two separate `upload-url` calls, two separate S3 PUTs).

```
POST /products/{productId}/media/videos
{ "storageKey": "products/42/videos/ab12...-t.mp4",        // was: "videoUrl"
  "title": "Runway clip",
  "thumbnailStorageKey": "products/42/videos/ab12...-t.jpg" }   // was: "thumbnailUrl", still optional

201 → { "id": 3, "videoUrl": "https://cdn.enunas.com/products/42/videos/ab12....mp4",
         "title": "Runway clip",
         "thumbnailUrl": "https://cdn.enunas.com/products/42/videos/ab12...-t.jpg",
         "createdAt": "..." }
```
`GET .../videos` and `DELETE .../videos/{videoId}` — unchanged shapes, same as images.

## Brand logo & hero (brand-partner dashboard, `PATCH /brandpartner/me`)

### Get a presigned URL
```
POST /brandpartner/media/upload-url
Authorization: Bearer <token>   (BRAND_PARTNER)

{ "purpose": "BRAND_LOGO", "contentType": "image/png", "contentLength": 51200 }
// or "purpose": "BRAND_HERO" for the hero banner

200 → { "key": "brands/7/logo/f0a1...-2b3c.png", "uploadUrl": "...", "expiresAt": "...",
        "requiredHeaders": { ... } }
```

### Confirm — merged into the existing profile-update call
```
PATCH /brandpartner/me
{ "logoStorageKey": "brands/7/logo/f0a1...-2b3c.png" }   // was: "logoUrl": "<pasted url>"
// and/or:
{ "heroStorageKey": "brands/7/hero/aa22...-9f8e.jpg" }   // net-new field, no prior equivalent

200 → { ..., "logoUrl": "https://cdn.enunas.com/brands/7/logo/f0a1...-2b3c.png",
             "heroImageUrl": "https://cdn.enunas.com/brands/7/hero/aa22...-9f8e.jpg", ... }
```
`logoStorageKey` and `heroStorageKey` are independent — send one, both, or neither (omitted field =
unchanged, same partial-update semantics as every other field on this endpoint already).

`GET /brandpartner/me` and `GET /brandpartner/{id}` — **response gains one new field,
`heroImageUrl`** (resolved the same way as `logoUrl`; `null` until a hero is set). Everything else on
this DTO is unchanged. Both endpoints resolve through the same DTO, so admin's brand-detail view
picks up `heroImageUrl` for free if you choose to render it — nothing to change there unless you want
to display it.

### ⚠️ Breaking change: `POST /brandpartner/apply` no longer accepts `logoUrl`

The public brand-application/signup form **can no longer set a logo at signup time**. There's no
brand id yet at that point, so there's no ownership-checked resource to upload under. If your signup
form currently has a logo field, remove it — the brand sets their logo afterward via the dashboard's
`PATCH /brandpartner/me` flow above, once their account is approved and they can log in. Every other
field on `RegisterBrandPartnerDto` is unchanged.

---

## Error handling — one behavior change worth a look

Cross-tenant/ownership rejections (e.g. confirming a `storageKey` that doesn't belong to the caller's
own product/brand) now correctly return **`403 Forbidden`** instead of `500`. If your API client has
generic 500-handling ("something went wrong, try again") but no 403-specific handling on these
endpoints, add one — a 403 here means the request was structurally invalid (wrong key, not owned by
you), not a transient server error, and retrying won't help.

Standard `400 Bad Request` still covers: disallowed content type, oversized file, or confirming a
`storageKey` that was never actually uploaded to S3 (same validation shape as every other endpoint in
this API — `{ timestamp, status, error, message }`).

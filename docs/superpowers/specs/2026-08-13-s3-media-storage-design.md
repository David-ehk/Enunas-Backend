# S3 Media Storage — Presigned PUT + CloudFront/OAC over a Private Bucket

**Status:** Settled — architecture is not up for renegotiation; implementation questions only from here.
**Region:** eu-central-1 (Frankfurt), per requirement.

## Confirmed facts (product owner)

- `product_images` and `product_videos` are **empty** in the current database. Hard cutover is
  destructive-safe: no backup, no dual-read compatibility path needed.
- No `hero_image_url` free-text column is to be created. Brand hero is **key-only from birth** —
  it's a net-new field, so it carries no legacy-URL debt.

## Architecture (settled)

- **Writes:** presigned PUT. The browser uploads bytes directly to S3; the backend never touches
  file bytes.
- **Reads:** private bucket + **CloudFront with Origin Access Control (OAC)**. `image_url` and
  equivalents store **keys**, resolved to a CDN URL when DTOs are built — never a stored URL.
- **Auth:** `DefaultCredentialsProvider` (prod = IAM role, **no static keys** anywhere). Dev/test
  = **LocalStack** via Testcontainers, endpoint overridden by config.
- **Scope:** product images + videos (+ thumbnails), brand logo, brand hero. Wardrobe images and
  return labels are explicitly out of scope — both are person-scoped data needing the private
  presigned-GET path, deliberately deferred until that data actually exists.

## 1. Dependencies (`backend/pom.xml`)

- Replace the current malformed `<dependencies>` block (invalid XML — `s3:2.20.26` nested where
  a single `<dependency>` belongs) with the AWS SDK BOM in `<dependencyManagement>`, pinned to
  **`2.53.0`** — confirmed current GA on Maven Central (bom/s3/url-connection-client jars all
  resolve at that version; do not downgrade to a remembered version without re-checking, SDK v2
  moves fast — 2.52.1 was current hours earlier the same day).
- `s3` unversioned, inherited from the BOM.
- **Design correction, recorded explicitly so it isn't silently reintroduced:** `S3Presigner`
  needs no `SdkHttpClient`. Presigning is pure local SigV4 computation — no network call happens.
  `url-connection-client` is needed only for the S3 calls the backend actually executes over HTTP
  (`HeadObject`, `PutObjectTagging`, `DeleteObject`), not for presigning itself.
- Add `url-connection-client`; exclude `apache-client` from `s3` (consistent with the existing
  Apache HTTP transport exclusion on `google-api-client`).
- Test scope: `org.testcontainers:localstack`.

## 2. Config (`enunas.media`)

```yaml
enunas:
  media:
    bucket: ${S3_BUCKET:enunas-media}
    region: ${AWS_REGION:eu-central-1}
    cdn-base-url: ${MEDIA_CDN_BASE_URL:}
    endpoint: ${S3_ENDPOINT:}          # empty = real AWS; set = LocalStack
    presign-ttl: ${MEDIA_PRESIGN_TTL:PT10M}
```

Credentials never appear here. **Fail-fast:** startup aborts if `cdn-base-url` is blank while
`endpoint` is also blank — i.e. real AWS configured with no CDN, which would otherwise silently
emit broken image URLs.

## 3. Package `com.enunas.backend.media.storage`

| Component | Responsibility |
|---|---|
| `MediaStorageProperties` | `@ConfigurationProperties("enunas.media")` |
| `S3Config` | `S3Client` + `S3Presigner` beans; endpoint override + path-style only when `endpoint` is set |
| `MediaPurpose` (enum) | Per-purpose policy: key prefix, allowed content types, max bytes |
| `MediaStorageService` | `presignUpload(...)`, `verifyUploaded(key, purpose)`, `delete(key)` |
| `MediaUrlResolver` | The **only** place that knows the CDN host: `key → cdnBase + "/" + key` |

`MediaPurpose` policy table:

| Purpose | Key pattern | Allowed content types | Max size |
|---|---|---|---|
| `PRODUCT_IMAGE` | `products/{id}/images/{uuid}.{ext}` | image/jpeg, png, webp | 10 MB |
| `PRODUCT_VIDEO` | `products/{id}/videos/{uuid}.{ext}` | video/mp4, webm | 200 MB |
| `VIDEO_THUMB` | `products/{id}/videos/{uuid}-t.{ext}` | image/jpeg, png, webp | 2 MB |
| `BRAND_LOGO` | `brands/{id}/logo/{uuid}.{ext}` | image/jpeg, png, webp | 5 MB |
| `BRAND_HERO` | `brands/{id}/hero/{uuid}.{ext}` | image/jpeg, png, webp | 10 MB |

Keys are **server-generated**: the resource id comes from the authenticated ownership check, the
filename is a UUID. No client-supplied path segment ever reaches the key — path traversal and
cross-tenant writes are structurally impossible, not merely validated against.

## 4. Closing the presigned-PUT trust gap (three layers)

Since bytes bypass the backend entirely, trust is rebuilt at three points:

1. **Signature binds the upload.** `contentType` and a `contentLength` range are baked into the
   presigned request — a URL signed for a 2 MB JPEG cannot be used to PUT a 2 GB MP4; S3 itself
   rejects it.
2. **Short TTL.** 10 minutes.
3. **`HeadObject` at confirm.** When the client calls the create endpoint with a `storageKey`, the
   backend verifies the object exists, sits under the prefix this caller is authorized for, and
   matches the expected content-type and size ceiling. Only then is the row written. A key that
   was never uploaded, or was uploaded under someone else's prefix, is rejected.

## 5. Endpoints

```
POST /products/{productId}/media/upload-url   BRAND_PARTNER, ownership checked
POST /brandpartner/media/upload-url            BRAND_PARTNER
  body:  { purpose, contentType, contentLength }
  200:   { key, uploadUrl, expiresAt }
```

Existing create endpoints (`.../images`, `.../videos`, brand update) take `storageKey` instead of
a free-text URL. No `SecurityConfiguration` change needed — `POST /products/**` is already
`hasRole('BRAND_PARTNER')` and `/brandpartner/**` is already role-gated.

## 6. Schema — `V25__media_storage_keys.sql` (hard cutover, key-only)

```sql
-- Hard cutover: product owner confirmed product_images/product_videos are EMPTY.
-- No dual-read path; storage_key is the single source from this migration forward.
DELETE FROM product_images;
DELETE FROM product_videos;

ALTER TABLE product_images DROP COLUMN image_url;
ALTER TABLE product_images ADD COLUMN storage_key varchar(512) NOT NULL;

ALTER TABLE product_videos DROP COLUMN video_url;
ALTER TABLE product_videos DROP COLUMN thumbnail_url;
ALTER TABLE product_videos ADD COLUMN storage_key varchar(512) NOT NULL;
ALTER TABLE product_videos ADD COLUMN thumbnail_storage_key varchar(512);

ALTER TABLE brand_partners DROP COLUMN logo_url;
ALTER TABLE brand_partners ADD COLUMN logo_storage_key varchar(512);  -- nullable, optional
ALTER TABLE brand_partners ADD COLUMN hero_storage_key varchar(512); -- nullable, key-only from birth
```

`DELETE` before `ADD COLUMN ... NOT NULL` is what makes the NOT NULL addition legal without a
default (empty table, no constraint violation). This is a new migration — no edit to an applied
one.

**DTO naming rule:** response fields (`imageUrl`, `logoUrl`, `heroImageUrl`) keep their `...Url`
names for API/frontend compatibility, but they are **computed by the resolver from `storageKey`
at read time, never persisted**. This is not a violation of "key-only" — that rule applies to
stored columns, not to a response DTO's resolved output field.

## 7. Orphan cleanup — lifecycle-by-tag, not age-on-prefix

Keys are organized by resource id, not by lifecycle stage, so an age-only rule on a prefix cannot
distinguish "orphaned" from "legitimately old and confirmed" — it risks deleting live objects.
Tag-based lifecycle is the correct fit instead:

- The presigned PUT request bakes in a **signed** `x-amz-tagging: media-status=pending` header —
  the client cannot omit or alter it without invalidating the signature.
- On confirm, after `HeadObject` succeeds, the backend calls `PutObjectTagging` to clear the tag.
- A bucket lifecycle rule filters on `media-status=pending` and expires objects after 24h —
  generous past the 10-minute presign TTL to tolerate backend hiccups, tight enough that abandoned
  uploads don't linger.
- Backend IAM needs `s3:PutObjectTagging` in addition to `PutObject`/`HeadObject`/`DeleteObject`.

## 8. Tests (LocalStack via Testcontainers)

- Presign → real PUT → confirm round-trip.
- Rejection of a foreign brand's/product's key.
- Content-type rejection; oversize rejection.
- Confirm-without-upload rejection.
- Key → CDN-URL resolution.

**Explicitly documented limitation:** LocalStack proves the *logic*, not SigV4 signature
acceptance by real S3 — LocalStack has had known gaps there historically. That is a separate,
manual gate (§10) that no automated test substitutes for.

## 9. Runbook — `docs/aws-media-setup.md`

- Bucket in `eu-central-1`, all four Block Public Access flags **on**.
- CloudFront distribution + **OAC**; bucket policy grants `s3:GetObject` **only** to the
  distribution, scoped via `AWS:SourceArn` on the distribution ARN — not `*`.
- S3 CORS allows **PUT from the frontend origin** (otherwise the browser upload fails with opaque
  CORS errors).
- **Least-privilege IAM** for the backend: `PutObject`/`HeadObject`/`DeleteObject`/
  `PutObjectTagging`, scoped to the 5 prefixes on this one bucket only — no broad S3 access.
- HTTPS-only. Runbook ends by emitting the CloudFront hostname, which must go into both (a)
  `MEDIA_CDN_BASE_URL` and (b) Next.js `next.config.ts` `remotePatterns`.

## 10. Manual acceptance gates — not simulable, hard blockers

To be run once by the owner after the runbook is applied. No test replaces these:

1. **One real presigned PUT + confirm against the actual `eu-central-1` bucket.** Proves SigV4
   canonicalization, region correctness, and bucket ownership/permissions — none of which
   LocalStack can fully validate.
2. **One real GET through CloudFront, checked in both directions:** the CDN serves the image
   *and* a direct S3 URL returns `AccessDenied`. The OAC deny is the actual security property
   being verified, not merely "loads via CDN."

## Sequencing note

Code + LocalStack tests can proceed now, independent of the Gewerbe track. The two gates in §10
are hard-blocked on the owner applying the runbook (bucket, CloudFront, OAC, IAM role) — that has
its own lead time and can run in parallel with development, but the feature cannot go live until
both gates pass.

## Acceptance criteria

- [ ] Build green with SDK 2.53.0.
- [ ] LocalStack tests green: round-trip, foreign-key rejection, content-type rejection, oversize
      rejection, confirm-without-upload rejection, URL resolution.
- [ ] `V25` runs cleanly against the (empty) tables; schema is key-only; response DTOs return
      resolved CDN URLs.
- [ ] Keys are server-generated; no client path segment reaches a key; 3-layer trust gap is active
      (signed content-type/length, 10-min TTL, `HeadObject` confirm).
- [ ] Lifecycle is tag-based; IAM includes `PutObjectTagging`; IAM is least-privilege on the 5
      prefixes only.
- [ ] Fail-fast on `cdn-base-url` empty + `endpoint` empty is confirmed working.
- [ ] The two real-AWS gates (§10) are marked as manual, blocking steps in the implementation
      plan — never reported as satisfied by the automated test suite.
- [ ] No static AWS keys anywhere; credentials only via `DefaultCredentialsProvider`.

## Out of scope (deferred, not forgotten)

- Wardrobe item images (`WardrobeItem.imageUrl`) — mixed public/private via `is_public`, needs the
  presigned-GET path this design intentionally doesn't build yet.
- Return labels (`ReturnOrder.labelUrl`) — currently carrier-hosted (DHL/UPS generate the label),
  nothing of ours stored there yet.

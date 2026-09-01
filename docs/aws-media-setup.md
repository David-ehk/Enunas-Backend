# AWS Media Setup Runbook

One-time infrastructure setup for the S3 media storage feature
(`docs/superpowers/specs/2026-08-13-s3-media-storage-design.md`). Run once by the account/infra
owner; the app code (already shipped) is ready the moment these env vars are set.

## 1. S3 buckets — there are two

Region **eu-central-1** (Frankfurt) for both.

| Bucket | Holds | `MediaPurpose` values | Key prefixes |
|---|---|---|---|
| `enunas-clothing-images-926583575598-eu-central-1-an` | product media | `PRODUCT_IMAGE`, `PRODUCT_VIDEO`, `VIDEO_THUMB` | `products/*/images/`, `products/*/videos/` |
| `enunas-brand-previews-926583575598-eu-central-1-an` | brand previews | `BRAND_LOGO`, `BRAND_HERO` | `brands/*/logo/`, `brands/*/hero/` |

The split is `MediaPurpose.Scope` (`PRODUCT` / `BRAND`). Every key is server-generated and starts
with `products/` or `brands/`, so a stored key alone identifies its bucket — that is what lets
`MediaStorageService.delete` and `MediaUrlResolver`, which only ever receive a key, find the right
bucket without a schema change.

## 2. Public read — no CloudFront

There is **no CloudFront distribution**. `MediaUrlResolver` therefore emits direct S3 URLs:

```
https://<bucket>.s3.eu-central-1.amazonaws.com/<key>
```

For a browser to load those, the objects must be anonymously readable. Each bucket needs a bucket
policy granting `s3:GetObject` to `*`, and Block Public Access relaxed enough to permit it
(`BlockPublicPolicy` and `RestrictPublicBuckets` off; the two ACL flags can stay on — this grants
access by policy, not by ACL):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "PublicReadMediaObjects",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": [
        "arn:aws:s3:::enunas-clothing-images-926583575598-eu-central-1-an/products/*",
        "arn:aws:s3:::enunas-clothing-images-926583575598-eu-central-1-an/brands/*"
      ]
    }
  ]
}
```

(One such policy per bucket, with that bucket's own prefixes.) Scoping the `Resource` to the media
prefixes rather than `/*` keeps anything else that ever lands in the bucket private.

This is a deliberate trade-off, and worth re-reading before it becomes permanent: public objects
are enumerable by anyone who can guess a key (keys are random UUIDs, so guessing is impractical,
but a leaked URL is a permanent one — there is no expiry to fall back on) and every request is
billed as S3 GET egress with no CDN caching in front of it. Putting CloudFront + OAC in front later
requires no code change: set `MEDIA_CDN_BASE_URL`, and `MediaUrlResolver` switches to it for all
keys in both buckets.

## 3. S3 CORS (required for browser uploads)

Apply this to **both** buckets — a presigned PUT goes straight to the bucket that holds the key,
so CORS on only one of them breaks brand uploads or product uploads, whichever was missed.

Without this, every presigned PUT from the frontend fails with an opaque CORS error, not a clear
S3 error. Allow `PUT` from the frontend origin(s):

```json
[
  {
    "AllowedOrigins": ["https://your-frontend-domain.com"],
    "AllowedMethods": ["PUT"],
    "AllowedHeaders": ["Content-Type", "x-amz-tagging"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3000
  }
]
```

## 4. Frontend upload contract

The presign response (`POST .../media/upload-url`) includes a `requiredHeaders` map
(`{headerName: headerValue}`) alongside the `uploadUrl`. The frontend **must** send every one of
those headers, with those exact values, on the PUT request to `uploadUrl` — omitting any of them
causes S3 to reject the upload with `403 SignatureDoesNotMatch` and no further diagnostic detail.

This exists to prevent a specific failure mode: a naive
`fetch(uploadUrl, { method: 'PUT', body: file })` will **not** automatically send `Content-Type` or
`x-amz-tagging`, both of which are part of the signed request. `requiredHeaders` makes that
requirement explicit and machine-readable, instead of something the frontend has to discover by
trial and error against opaque S3 signature errors.

## 5. Bucket lifecycle rule (tag-based, not age-on-prefix)

Needed on **both** buckets — abandoned uploads accumulate in whichever bucket the presign targeted.

Keys are organized by resource id, not lifecycle stage, so an age-only rule can't tell "orphaned"
from "legitimately old and confirmed" apart. Add a lifecycle rule instead:

- **Filter:** tag `media-status = pending`
- **Action:** expire (delete) after **24 hours**

This is generous past the 10-minute presign TTL (tolerates backend hiccups) but tight enough that
abandoned uploads don't linger. The backend clears this tag via `DeleteObjectTagging` the moment a
`storageKey` is confirmed (see `MediaStorageService.verifyUploaded`) — confirmed objects are never
tagged `pending` and are never touched by this rule.

## 6. IAM — least privilege, backend role

Scope to exactly these four prefixes across the two buckets, nothing broader
(VIDEO_THUMB shares PRODUCT_VIDEO's prefix — 5 MediaPurpose values, 4 distinct S3 key prefixes):

```
arn:aws:s3:::enunas-clothing-images-926583575598-eu-central-1-an/products/*/images/*
arn:aws:s3:::enunas-clothing-images-926583575598-eu-central-1-an/products/*/videos/*
arn:aws:s3:::enunas-brand-previews-926583575598-eu-central-1-an/brands/*/logo/*
arn:aws:s3:::enunas-brand-previews-926583575598-eu-central-1-an/brands/*/hero/*
```

Only object ARNs (`/…`), no bare bucket ARN: a bucket-level ARN is only needed for `s3:ListBucket`,
which this role deliberately does not have.

Actions: `s3:PutObject`, `s3:PutObjectTagging`, `s3:HeadObject` (technically `s3:GetObject` —
HeadObject uses the same permission), `s3:DeleteObject`, `s3:DeleteObjectTagging`. No
`s3:ListBucket`, no `s3:*`.

Both tagging actions are required, for two different steps, and neither is optional:

- `s3:PutObjectTagging` — the presigned PUT carries `x-amz-tagging: media-status=pending` (see
  `MediaStorageService.presignUpload`). S3 requires `s3:PutObjectTagging` *in addition to*
  `s3:PutObject` whenever a PUT sets a tag set, and it is evaluated against the **presigning**
  identity — this role — not the browser doing the upload. Drop it and every upload fails.
- `s3:DeleteObjectTagging` — confirm removes the whole pending tag set rather than overwriting it
  (see `MediaStorageService.verifyUploaded`).

Attach as an IAM role to the backend's compute (EC2/ECS/Lambda role) — credentials resolve via
`DefaultCredentialsProvider` automatically. **No static access keys, ever.**

## 7. Environment variables

| Variable | Default | Description |
|---|---|---|
| `S3_BUCKET_PRODUCTS` | — (**required**) | Product-media bucket from step 1. No default: the app refuses to start without it, rather than failing per-upload with `NoSuchBucket`. |
| `S3_BUCKET_BRAND_PREVIEWS` | — (**required**) | Brand-preview bucket from step 1, same fail-fast rule. |
| `AWS_REGION` | `eu-central-1` | Must match both buckets' region — it is part of the direct S3 hostname. |
| `MEDIA_CDN_BASE_URL` | *(empty)* | Optional. Empty is correct today (no CloudFront) — URLs resolve straight to each bucket's S3 host. Set it to a CDN hostname to route every key through that instead, with no data change. |
| `S3_ENDPOINT` | *(empty)* | Leave empty for real AWS. Set to a local LocalStack/S3Mock endpoint (e.g. `http://localhost:4566`) for local dev without touching real AWS. |
| `MEDIA_PRESIGN_TTL` | `PT10M` | ISO-8601 duration a presigned upload URL stays valid. |

Both bucket hostnames must be listed in the frontend's Next.js `next.config.ts`
`images.remotePatterns` — they are two distinct hosts, so allowing only one silently breaks half
the images. If a CDN is introduced later, its hostname goes into `MEDIA_CDN_BASE_URL` **and** into
`images.remotePatterns`.

## 8. Local dev without real AWS

Point `S3_ENDPOINT` at a local S3-compatible server (e.g. run `com.adobe.testing:s3mock` as a
Docker container, or LocalStack if you have a compliant license — see the plan's Design Decision 1
for why this project's automated tests use S3Mock, not LocalStack). `MEDIA_CDN_BASE_URL` stays
empty; with `S3_ENDPOINT` set, `MediaUrlResolver` emits path-style URLs
(`<endpoint>/<bucket>/<key>`) to match the S3 client's path-style access. `S3_BUCKET_PRODUCTS` and
`S3_BUCKET_BRAND_PREVIEWS` are still required — create both buckets in the local S3 server.

## 9. Manual acceptance gates — not simulable, hard blockers

Run once by the owner after the infra setup steps above (1–3, 5–6) are applied. **No automated test substitutes for
these** — they are the only proof of real SigV4 signature enforcement, which neither S3Mock nor
LocalStack fully validates:

1. **One real presigned PUT + confirm against the actual `eu-central-1` bucket.** Proves SigV4
   canonicalization, region correctness, and bucket ownership/permissions.
2. **One real anonymous GET per bucket, checked in both directions:** the object URL loads in a
   logged-out browser (proving the public-read bucket policy from step 2 actually applies), *and*
   a key outside the media prefixes still returns `AccessDenied` (proving the policy was scoped to
   `products/*` / `brands/*` and did not open the whole bucket). Do this for **both** buckets — a
   policy applied to only one of them breaks exactly half the images, which is easy to miss.
3. **Both bucket hostnames reachable from the frontend**, i.e. present in `images.remotePatterns`.

The feature is not live until all three gates pass, independent of how green the automated suite is.

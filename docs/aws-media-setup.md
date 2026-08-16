# AWS Media Setup Runbook

One-time infrastructure setup for the S3 media storage feature
(`docs/superpowers/specs/2026-08-13-s3-media-storage-design.md`). Run once by the account/infra
owner; the app code (already shipped) is ready the moment these env vars are set.

## 1. S3 bucket

- Region: **eu-central-1** (Frankfurt).
- Name: e.g. `enunas-media` (matches `S3_BUCKET` below — any name works, just keep them in sync).
- **Block Public Access: all four flags ON.** The bucket is never public — CloudFront/OAC is the
  only reader.

## 2. CloudFront + Origin Access Control (OAC)

- Create a CloudFront distribution with the bucket as origin, using **OAC** (not the older OAI).
- Bucket policy: grant `s3:GetObject` **only** to the distribution, scoped via `AWS:SourceArn` to
  that specific distribution's ARN — never `*`, never account-wide.
- HTTPS-only (redirect HTTP → HTTPS, or reject HTTP entirely).

## 3. S3 CORS (required for browser uploads)

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

Keys are organized by resource id, not lifecycle stage, so an age-only rule can't tell "orphaned"
from "legitimately old and confirmed" apart. Add a lifecycle rule instead:

- **Filter:** tag `media-status = pending`
- **Action:** expire (delete) after **24 hours**

This is generous past the 10-minute presign TTL (tolerates backend hiccups) but tight enough that
abandoned uploads don't linger. The backend clears this tag via `PutObjectTagging` the moment a
`storageKey` is confirmed (see `MediaStorageService.verifyUploaded`) — confirmed objects are never
tagged `pending` and are never touched by this rule.

## 6. IAM — least privilege, backend role

Scope to exactly these four prefixes on this one bucket, nothing broader
(VIDEO_THUMB shares PRODUCT_VIDEO's prefix — 5 MediaPurpose values, 4 distinct S3 key prefixes):

```
arn:aws:s3:::enunas-media/products/*/images/*
arn:aws:s3:::enunas-media/products/*/videos/*
arn:aws:s3:::enunas-media/brands/*/logo/*
arn:aws:s3:::enunas-media/brands/*/hero/*
```

Actions: `s3:PutObject`, `s3:HeadObject` (technically `s3:GetObject` — HeadObject uses the same
permission), `s3:DeleteObject`, `s3:PutObjectTagging`. No `s3:ListBucket`, no `s3:*`.

Attach as an IAM role to the backend's compute (EC2/ECS/Lambda role) — credentials resolve via
`DefaultCredentialsProvider` automatically. **No static access keys, ever.**

## 7. Environment variables

| Variable | Default | Description |
|---|---|---|
| `S3_BUCKET` | `enunas-media` | Bucket name from step 1 |
| `AWS_REGION` | `eu-central-1` | Must match the bucket's region |
| `MEDIA_CDN_BASE_URL` | *(empty)* | CloudFront hostname from step 2, e.g. `https://d123abc.cloudfront.net`. **Required in production** — the app refuses to start without it unless `S3_ENDPOINT` is set (see below). |
| `S3_ENDPOINT` | *(empty)* | Leave empty for real AWS. Set to a local LocalStack/S3Mock endpoint (e.g. `http://localhost:4566`) for local dev without touching real AWS. |
| `MEDIA_PRESIGN_TTL` | `PT10M` | ISO-8601 duration a presigned upload URL stays valid. |

When the CloudFront hostname is issued in step 2, it must go into **both**:
(a) this backend's `MEDIA_CDN_BASE_URL`, and
(b) the frontend's Next.js `next.config.ts` `images.remotePatterns`.

## 8. Local dev without real AWS

Point `S3_ENDPOINT` at a local S3-compatible server (e.g. run `com.adobe.testing:s3mock` as a
Docker container, or LocalStack if you have a compliant license — see the plan's Design Decision 1
for why this project's automated tests use S3Mock, not LocalStack). `MEDIA_CDN_BASE_URL` can stay
empty in that case — the fail-fast check only requires *one* of the two.

## 9. Manual acceptance gates — not simulable, hard blockers

Run once by the owner after the infra setup steps above (1–3, 5–6) are applied. **No automated test substitutes for
these** — they are the only proof of real SigV4 signature enforcement, which neither S3Mock nor
LocalStack fully validates:

1. **One real presigned PUT + confirm against the actual `eu-central-1` bucket.** Proves SigV4
   canonicalization, region correctness, and bucket ownership/permissions.
2. **One real GET through CloudFront, checked in both directions:** the CDN serves the image
   *and* a direct S3 URL returns `AccessDenied`. The OAC deny is the actual security property
   being verified, not merely "loads via CDN."

The feature is not live until both gates pass, independent of how green the automated suite is.

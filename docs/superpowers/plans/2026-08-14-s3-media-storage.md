# S3 Media Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace free-text media URLs (product images/videos, brand logo, brand hero) with a presigned-PUT + CloudFront/OAC architecture: the browser uploads bytes directly to a private S3 bucket, the backend never touches file bytes, and `image_url`/`logoUrl`/etc. response fields are resolved from stored `storageKey`s at read time.

**Architecture:** A new `com.enunas.backend.media.storage` package owns everything S3: config, per-purpose upload policy, presign/confirm/delete, and the one place that knows the CDN host. Existing `MediaService`/`BrandPartnerService` gain a `storageKey`-based create/update path (no separate confirm endpoint — confirmation is folded into the existing create/update calls per the design doc). `V25` is a hard, destructive cutover on empty tables.

**Tech Stack:** AWS SDK for Java v2 (`software.amazon.awssdk:s3` 2.53.0 + `url-connection-client`), Spring Boot 4 / JPA / Flyway (existing stack). Test-only: `com.adobe.testing:s3mock-testcontainers` (see Design Decision 1 — **not** `org.testcontainers:localstack` as the spec literally names).

**Spec:** `docs/superpowers/specs/2026-08-13-s3-media-storage-design.md`

## Global Constraints

- AWS SDK BOM pinned to **2.53.0** (confirmed current GA on Maven Central) — do not silently downgrade to a remembered version.
- `S3Presigner` gets **no** `SdkHttpClient` — presigning is pure local SigV4 computation, no network call.
- Credentials: `DefaultCredentialsProvider` only, **never** static keys, anywhere, including tests (tests use dummy `StaticCredentialsProvider`/`AwsBasicCredentials` only against the test double, never against real AWS).
- Region default: `eu-central-1` (Frankfurt).
- Keys are **server-generated only** — the resource id comes from the authenticated ownership check, the filename is a UUID. No client-supplied path segment ever reaches a key.
- DTO naming: response fields keep their `...Url` names (`imageUrl`, `logoUrl`, `heroImageUrl`) but are computed by `MediaUrlResolver` from `storageKey` at read time — never persisted as URLs.
- Lifecycle is tag-based (`media-status=pending` cleared on confirm via `PutObjectTagging`), not age-on-prefix.
- The two real-AWS gates (spec §10) are manual, blocking steps — **never** reported as satisfied by the automated test suite.
- No `SecurityConfiguration` changes — `POST /products/**` is already `hasRole('BRAND_PARTNER')` and `/brandpartner/**` is already role-gated; the two new `upload-url` endpoints fall under those existing rules.

## Design Decisions (read before implementing)

These resolve gaps between the spec and reality, confirmed with the user during planning.

1. **`com.adobe.testing:s3mock-testcontainers` (Apache-2.0) replaces `org.testcontainers:localstack`.** As of March 23, 2026, LocalStack's Docker image requires a `LOCALSTACK_AUTH_TOKEN`; the free "Hobby" tier is explicitly **non-commercial-use only**, which Enunas (a business) cannot rely on. S3Mock is permissively licensed, needs no account/token, and covers everything these tests actually need: `PutObject` (via presigned URL — S3Mock accepts the request but, like LocalStack, does **not** validate the SigV4 signature itself), `HeadObject`, `PutObjectTagging`, `DeleteObject`, `GetObjectTagging`. The spec's own caveat ("LocalStack proves the logic, not SigV4 signature acceptance by real S3") applies identically to S3Mock — the manual real-AWS gate (spec §10) remains the *only* proof of real signature enforcement, unchanged by this substitution.
2. **Rejection-path tests run against a Mockito-mocked `S3Client`, not a container.** Foreign-key-prefix rejection is pure logic (never reaches S3). Content-type/oversize/confirm-without-upload rejection is tested by stubbing `S3Client.headObject(...)` to return a mismatched/absent object — deterministic, no Docker dependency, fast. Only two test classes use the real S3Mock container: one proving the actual presign→PUT→confirm plumbing works against an S3-shaped HTTP server, and one full HTTP-layer end-to-end test.
3. **`RegisterBrandPartnerDto.logoUrl` is dropped, not converted to `logoStorageKey`.** At `POST /brandpartner/apply` time there is no brand id yet (pre-auth, pre-persist) and keys are structurally server-generated from an authenticated-and-ownership-checked resource id — there is nothing to embed a key under. Brand logo (and hero) are set **after** approval via `PATCH /brandpartner/me`, using the same presign+confirm flow as everything else. This was always an optional field; no onboarding requirement is lost.
4. **No separate `/media/confirm` endpoint.** Per spec §4's literal wording ("When the client calls the create endpoint with a `storageKey`..."), confirmation (`HeadObject` + tag-clear) is folded directly into the existing `addImage`/`addVideo`/`updateMyProfile` calls.
5. **`MediaStorageService.delete(key)` is wired into `deleteImage`/`deleteVideo`** (the object is actually removed from S3, not just the DB row) since spec §3 lists `delete(...)` as a first-class `MediaStorageService` responsibility. It is **not** auto-wired into brand logo/hero *replacement* in `updateMyProfile` — the spec doesn't ask for that, and adding it silently would be scope creep. Known consequence: replacing a brand logo leaves the old object orphaned in S3 (cleaned up eventually by nothing — a future, explicitly-scoped pass). Flagged here so it isn't mistaken for an oversight.
6. **Fail-fast changes local dev requirements.** Once this ships, the app refuses to start unless `S3_ENDPOINT` (LocalStack/S3Mock) or `MEDIA_CDN_BASE_URL` is set — real AWS with neither would silently emit broken image URLs, which is the whole point of the check. `application-test.yaml` gets dummy values so the *existing* test suite (which never touches media) is unaffected. Documented in the new runbook for real local dev.

## File Structure

```
backend/pom.xml                                                    [modify]

backend/src/main/resources/
  application.yaml                                                 [modify] + enunas.media block
  db/migration/V25__media_storage_keys.sql                         [create]

backend/src/test/resources/
  application-test.yaml                                            [modify] + enunas.media dummy block

backend/src/main/java/com/enunas/backend/media/storage/
  MediaStorageProperties.java                                      [create]
  S3Config.java                                                    [create]
  MediaPurpose.java                                                [create]
  MediaUrlResolver.java                                             [create]
  MediaStorageService.java                                         [create]

backend/src/main/java/com/enunas/backend/media/
  ProductImage.java                                                [modify] imageUrl -> storageKey
  ProductVideo.java                                                [modify] videoUrl/thumbnailUrl -> storageKey/thumbnailStorageKey
  MediaService.java                                                [modify] storageKey confirm/resolve, presignUpload
  MediaController.java                                             [modify] + POST .../media/upload-url
  dto/ProductImageDto.java                                         [modify] imageUrl -> storageKey
  dto/ProductImageResponseDto.java                                 [modify] resolver-based from()
  dto/ProductVideoDto.java                                         [modify] videoUrl/thumbnailUrl -> storageKey/thumbnailStorageKey
  dto/ProductVideoResponseDto.java                                 [modify] resolver-based from()
  dto/PresignUploadRequestDto.java                                 [create]
  dto/PresignUploadResponseDto.java                                [create]

backend/src/main/java/com/enunas/backend/brandpartner/
  BrandPartner.java                                                [modify] logoUrl -> logoStorageKey, + heroStorageKey
  BrandPartnerService.java                                         [modify] storageKey confirm, presignMediaUpload, drop apply-time logo
  BrandPartnerController.java                                      [modify] + POST /brandpartner/media/upload-url
  dto/RegisterBrandPartnerDto.java                                 [modify] drop logoUrl
  dto/UpdateBrandPartnerDto.java                                   [modify] logoUrl -> logoStorageKey, + heroStorageKey
  dto/BrandPartnerResponseDto.java                                 [modify] resolver-based from(), + heroImageUrl

backend/src/main/java/com/enunas/backend/admin/
  AdminService.java                                                [modify] inject MediaUrlResolver, pass to from()

backend/src/test/java/com/enunas/backend/media/storage/
  MediaStorageServiceTest.java                                     [create] unit (mocked S3Client)
  MediaStorageServiceS3MockIntegrationTest.java                    [create] real S3Mock round-trip
  MediaPurposeTest.java                                            [create] unit
  MediaUrlResolverTest.java                                        [create] unit
  MediaStoragePropertiesTest.java                                  [create] unit (fail-fast)

backend/src/test/java/com/enunas/backend/media/
  ProductMediaStorageKeyTest.java                                  [create] @DataJpaTest
  ProductMediaDtoTest.java                                         [create] unit (from() + resolver)
  MediaServiceTest.java                                            [create] unit (mocked collaborators)
  MediaEndToEndIntegrationTest.java                                [create] full HTTP, real S3Mock

backend/src/test/java/com/enunas/backend/brandpartner/
  BrandPartnerDtoValidationTest.java                                [modify] drop logoUrl test
  BrandPartnerMediaServiceTest.java                                [create] unit (mocked collaborators)

docs/aws-media-setup.md                                            [create]
backend/CLAUDE.md                                                  [modify] + env var table entries
```

---

### Task 1: Dependencies (`backend/pom.xml`)

**Files:**
- Modify: `backend/pom.xml`

**Interfaces:**
- Produces: `software.amazon.awssdk:s3`, `software.amazon.awssdk:url-connection-client` on the main classpath; `com.adobe.testing:s3mock-testcontainers` on the test classpath.

- [ ] **Step 1: Replace the malformed AWS block with a BOM-managed, correctly-scoped dependency set**

Remove the current invalid block:

```xml
		<dependencies>
			<groupId>software.amazon.awssdk</groupId>
			<artifactId>s3</artifactId>
			<version>2.20.26</version>
		</dependencies>
	</dependencies>
```

Replace the closing of the main `<dependencies>` block with:

```xml
		<dependency>
			<groupId>software.amazon.awssdk</groupId>
			<artifactId>s3</artifactId>
			<exclusions>
				<!-- Presigning needs no HTTP transport (pure local SigV4 computation); the S3Client
				     that DOES make real calls (HeadObject/PutObjectTagging/DeleteObject) uses
				     url-connection-client below, consistent with the Apache exclusion on google-api-client. -->
				<exclusion>
					<groupId>software.amazon.awssdk</groupId>
					<artifactId>apache-client</artifactId>
				</exclusion>
			</exclusions>
		</dependency>
		<dependency>
			<groupId>software.amazon.awssdk</groupId>
			<artifactId>url-connection-client</artifactId>
		</dependency>
		<dependency>
			<groupId>com.adobe.testing</groupId>
			<artifactId>s3mock-testcontainers</artifactId>
			<version>5.1.0</version>
			<scope>test</scope>
		</dependency>
	</dependencies>
```

Add the BOM in `<dependencyManagement>` (create this element as a sibling of `<dependencies>`, directly under `<project>`, after `</dependencies>` and before `<build>`):

```xml
	<dependencyManagement>
		<dependencies>
			<dependency>
				<groupId>software.amazon.awssdk</groupId>
				<artifactId>bom</artifactId>
				<version>2.53.0</version>
				<type>pom</type>
				<scope>import</scope>
			</dependency>
		</dependencies>
	</dependencyManagement>
```

- [ ] **Step 2: Verify dependency resolution**

Run: `cd backend && ./mvnw -q dependency:tree -Dincludes=software.amazon.awssdk,com.adobe.testing`
Expected: `software.amazon.awssdk:s3:jar:2.53.0`, `software.amazon.awssdk:url-connection-client:jar:2.53.0`, `com.adobe.testing:s3mock-testcontainers:jar:5.1.0` all resolve with no errors, and `apache-client` does NOT appear under the `s3` node.

- [ ] **Step 3: Verify the project still compiles**

Run: `cd backend && ./mvnw -q compile`
Expected: BUILD SUCCESS (no code references these libraries yet — this only proves the POM is valid and dependencies resolve).

- [ ] **Step 4: Commit**

```bash
git add backend/pom.xml
git commit -m "build: add AWS SDK v2 S3 (2.53.0) + s3mock-testcontainers, fix malformed dependency block"
```

---

### Task 2: Config — `MediaStorageProperties` + `application.yaml` + fail-fast

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/media/storage/MediaStorageProperties.java`
- Modify: `backend/src/main/resources/application.yaml`
- Modify: `backend/src/test/resources/application-test.yaml`
- Test: `backend/src/test/java/com/enunas/backend/media/storage/MediaStoragePropertiesTest.java`

**Interfaces:**
- Produces: `MediaStorageProperties` (getters/setters: `getBucket()`, `getRegion()`, `getCdnBaseUrl()`, `getEndpoint()`, `getPresignTtl(): Duration`) — bound from `enunas.media.*`. Package-private `void validate()` runs `@PostConstruct`.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.media.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaStoragePropertiesTest {

    private MediaStorageProperties properties() {
        MediaStorageProperties p = new MediaStorageProperties();
        p.setBucket("enunas-media");
        p.setRegion("eu-central-1");
        p.setPresignTtl(Duration.ofMinutes(10));
        return p;
    }

    @Test
    void bothBlank_failsFast() {
        MediaStorageProperties p = properties();
        p.setCdnBaseUrl("");
        p.setEndpoint("");

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cdn-base-url");
    }

    @Test
    void endpointSet_cdnBlank_isFine() {
        // LocalStack/S3Mock dev/test: endpoint marks a non-production target, no CDN needed.
        MediaStorageProperties p = properties();
        p.setEndpoint("http://localhost:4566");
        p.setCdnBaseUrl("");

        assertThatCode(p::validate).doesNotThrowAnyException();
    }

    @Test
    void cdnSet_endpointBlank_isFine() {
        // Real AWS with a CDN configured — the production-shaped case.
        MediaStorageProperties p = properties();
        p.setEndpoint("");
        p.setCdnBaseUrl("https://cdn.enunas.com");

        assertThatCode(p::validate).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MediaStoragePropertiesTest -q`
Expected: FAIL — compile error, `MediaStorageProperties` does not exist.

- [ ] **Step 3: Write the properties class**

```java
package com.enunas.backend.media.storage;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Binds {@code enunas.media.*}. Credentials never appear here — see {@link S3Config} for
 * {@code DefaultCredentialsProvider}.
 */
@Component
@ConfigurationProperties("enunas.media")
@Getter
@Setter
public class MediaStorageProperties {

    private String bucket;
    private String region;
    private String cdnBaseUrl;
    /** Empty = real AWS. Set (e.g. LocalStack/S3Mock) = endpoint override + path-style access. */
    private String endpoint;
    private Duration presignTtl;

    /**
     * Fail-fast: real AWS (no endpoint override) configured with no CDN base URL would silently
     * resolve every stored key to a broken "cdnBase/key" URL with a blank host. LocalStack/S3Mock
     * dev/test runs are exempt — {@code endpoint} being set is what marks a non-production target.
     */
    @PostConstruct
    void validate() {
        boolean realAws = endpoint == null || endpoint.isBlank();
        boolean noCdn = cdnBaseUrl == null || cdnBaseUrl.isBlank();
        if (realAws && noCdn) {
            throw new IllegalStateException(
                    "enunas.media.cdn-base-url must be set when enunas.media.endpoint is empty " +
                    "(real AWS with no CDN would silently emit broken image URLs). " +
                    "Set MEDIA_CDN_BASE_URL for production, or S3_ENDPOINT for LocalStack/S3Mock dev/test.");
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=MediaStoragePropertiesTest -q`
Expected: PASS (3 tests).

- [ ] **Step 5: Wire the config block into `application.yaml`**

Add this block after the existing `enunas:` block's `shipping:` entry in `backend/src/main/resources/application.yaml`:

```yaml
  media:
    bucket: ${S3_BUCKET:enunas-media}
    region: ${AWS_REGION:eu-central-1}
    cdn-base-url: ${MEDIA_CDN_BASE_URL:}
    endpoint: ${S3_ENDPOINT:}
    presign-ttl: ${MEDIA_PRESIGN_TTL:PT10M}
```

- [ ] **Step 6: Give the test profile dummy values so the existing suite is unaffected**

Add to `backend/src/test/resources/application-test.yaml`, after the existing `enunas:` block's `shipping:` entry:

```yaml
  media:
    bucket: test-media-bucket
    region: eu-central-1
    cdn-base-url: http://localhost:1/cdn
    # Dead endpoint by default — plain @SpringBootTest/@DataJpaTest classes never call S3, so this
    # only needs to be a non-blank string to satisfy the fail-fast check. Media-specific test
    # classes override enunas.media.* via @DynamicPropertySource to point at a real S3Mock container.
    endpoint: http://localhost:1
    presign-ttl: PT10M
```

- [ ] **Step 7: Run the full existing suite to confirm nothing else broke**

Run: `cd backend && ./mvnw test -Dtest=BackendApplicationTests -q`
Expected: PASS — context still loads with the new fail-fast check active.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/media/storage/MediaStorageProperties.java \
        backend/src/main/resources/application.yaml \
        backend/src/test/resources/application-test.yaml \
        backend/src/test/java/com/enunas/backend/media/storage/MediaStoragePropertiesTest.java
git commit -m "feat(media): add MediaStorageProperties with fail-fast CDN/endpoint check"
```

---

### Task 3: `MediaPurpose` enum

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/media/storage/MediaPurpose.java`
- Test: `backend/src/test/java/com/enunas/backend/media/storage/MediaPurposeTest.java`

**Interfaces:**
- Produces: `MediaPurpose` enum constants `PRODUCT_IMAGE`, `PRODUCT_VIDEO`, `VIDEO_THUMB`, `BRAND_LOGO`, `BRAND_HERO`; `MediaPurpose.Scope` enum (`PRODUCT`, `BRAND`); instance methods `scope(): Scope`, `allowedContentTypes(): Set<String>`, `maxBytes(): long`, `keyPrefix(long resourceId): String`, `validate(String contentType, long contentLength)` (throws `IllegalArgumentException`), `generateKey(long resourceId, String contentType): String`.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.media.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaPurposeTest {

    @Test
    void productImage_keyPrefix_matchesSpecPattern() {
        assertThat(MediaPurpose.PRODUCT_IMAGE.keyPrefix(42)).isEqualTo("products/42/images/");
    }

    @Test
    void productVideo_keyPrefix_matchesSpecPattern() {
        assertThat(MediaPurpose.PRODUCT_VIDEO.keyPrefix(42)).isEqualTo("products/42/videos/");
    }

    @Test
    void videoThumb_sharesPrefixWithProductVideo_butSuffixesTheFilename() {
        assertThat(MediaPurpose.VIDEO_THUMB.keyPrefix(42)).isEqualTo("products/42/videos/");
        String key = MediaPurpose.VIDEO_THUMB.generateKey(42, "image/jpeg");
        assertThat(key).matches("products/42/videos/[0-9a-f-]{36}-t\\.jpg");
    }

    @Test
    void brandLogo_keyPrefix_matchesSpecPattern() {
        assertThat(MediaPurpose.BRAND_LOGO.keyPrefix(7)).isEqualTo("brands/7/logo/");
    }

    @Test
    void brandHero_keyPrefix_matchesSpecPattern() {
        assertThat(MediaPurpose.BRAND_HERO.keyPrefix(7)).isEqualTo("brands/7/hero/");
    }

    @Test
    void generateKey_noSuffix_hasNoDashTBeforeExtension() {
        String key = MediaPurpose.PRODUCT_IMAGE.generateKey(42, "image/png");
        assertThat(key).matches("products/42/images/[0-9a-f-]{36}\\.png");
    }

    @Test
    void generateKey_resolvesExtensionFromContentType() {
        assertThat(MediaPurpose.PRODUCT_VIDEO.generateKey(1, "video/mp4")).endsWith(".mp4");
        assertThat(MediaPurpose.PRODUCT_VIDEO.generateKey(1, "video/webm")).endsWith(".webm");
    }

    @Test
    void scopes_areAssignedCorrectly() {
        assertThat(MediaPurpose.PRODUCT_IMAGE.scope()).isEqualTo(MediaPurpose.Scope.PRODUCT);
        assertThat(MediaPurpose.PRODUCT_VIDEO.scope()).isEqualTo(MediaPurpose.Scope.PRODUCT);
        assertThat(MediaPurpose.VIDEO_THUMB.scope()).isEqualTo(MediaPurpose.Scope.PRODUCT);
        assertThat(MediaPurpose.BRAND_LOGO.scope()).isEqualTo(MediaPurpose.Scope.BRAND);
        assertThat(MediaPurpose.BRAND_HERO.scope()).isEqualTo(MediaPurpose.Scope.BRAND);
    }

    @Test
    void maxBytes_matchSpecTable() {
        assertThat(MediaPurpose.PRODUCT_IMAGE.maxBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(MediaPurpose.PRODUCT_VIDEO.maxBytes()).isEqualTo(200L * 1024 * 1024);
        assertThat(MediaPurpose.VIDEO_THUMB.maxBytes()).isEqualTo(2L * 1024 * 1024);
        assertThat(MediaPurpose.BRAND_LOGO.maxBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(MediaPurpose.BRAND_HERO.maxBytes()).isEqualTo(10L * 1024 * 1024);
    }

    @Test
    void validate_disallowedContentType_throws() {
        assertThatThrownBy(() -> MediaPurpose.PRODUCT_IMAGE.validate("application/pdf", 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_oversize_throws() {
        assertThatThrownBy(() ->
                MediaPurpose.PRODUCT_IMAGE.validate("image/jpeg", 11L * 1024 * 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_zeroOrNegativeLength_throws() {
        assertThatThrownBy(() -> MediaPurpose.PRODUCT_IMAGE.validate("image/jpeg", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_allowedTypeWithinLimit_doesNotThrow() {
        MediaPurpose.PRODUCT_IMAGE.validate("image/webp", 1024);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MediaPurposeTest -q`
Expected: FAIL — compile error, `MediaPurpose` does not exist.

- [ ] **Step 3: Write the enum**

```java
package com.enunas.backend.media.storage;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-purpose upload policy: where a key lives, what content types are allowed, and the max size.
 * Keys are always {@code <prefix><uuid>[suffix].<ext>} — server-generated, never client-supplied.
 */
public enum MediaPurpose {

    PRODUCT_IMAGE(Scope.PRODUCT, "products/%d/images/", "",
            Set.of("image/jpeg", "image/png", "image/webp"), 10L * 1024 * 1024),
    PRODUCT_VIDEO(Scope.PRODUCT, "products/%d/videos/", "",
            Set.of("video/mp4", "video/webm"), 200L * 1024 * 1024),
    VIDEO_THUMB(Scope.PRODUCT, "products/%d/videos/", "-t",
            Set.of("image/jpeg", "image/png", "image/webp"), 2L * 1024 * 1024),
    BRAND_LOGO(Scope.BRAND, "brands/%d/logo/", "",
            Set.of("image/jpeg", "image/png", "image/webp"), 5L * 1024 * 1024),
    BRAND_HERO(Scope.BRAND, "brands/%d/hero/", "",
            Set.of("image/jpeg", "image/png", "image/webp"), 10L * 1024 * 1024);

    public enum Scope { PRODUCT, BRAND }

    private static final Map<String, String> EXTENSIONS_BY_CONTENT_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "video/mp4", "mp4",
            "video/webm", "webm"
    );

    private final Scope scope;
    private final String keyPrefixTemplate;
    private final String keySuffix;
    private final Set<String> allowedContentTypes;
    private final long maxBytes;

    MediaPurpose(Scope scope, String keyPrefixTemplate, String keySuffix,
                 Set<String> allowedContentTypes, long maxBytes) {
        this.scope = scope;
        this.keyPrefixTemplate = keyPrefixTemplate;
        this.keySuffix = keySuffix;
        this.allowedContentTypes = allowedContentTypes;
        this.maxBytes = maxBytes;
    }

    public Scope scope() {
        return scope;
    }

    public Set<String> allowedContentTypes() {
        return allowedContentTypes;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public String keyPrefix(long resourceId) {
        return keyPrefixTemplate.formatted(resourceId);
    }

    /** Rejects an upload request before any key is generated or any presigning happens. */
    public void validate(String contentType, long contentLength) {
        if (!allowedContentTypes.contains(contentType)) {
            throw new IllegalArgumentException("Unsupported content type '" + contentType
                    + "' for " + this + ". Allowed: " + allowedContentTypes);
        }
        if (contentLength <= 0 || contentLength > maxBytes) {
            throw new IllegalArgumentException("contentLength must be between 1 and " + maxBytes
                    + " bytes for " + this + ", got " + contentLength);
        }
    }

    /** Server-generated key: {@code <prefix><uuid><suffix>.<ext>}. Call {@link #validate} first. */
    public String generateKey(long resourceId, String contentType) {
        String extension = EXTENSIONS_BY_CONTENT_TYPE.get(contentType);
        if (extension == null) {
            throw new IllegalArgumentException("Unsupported content type: " + contentType);
        }
        return keyPrefix(resourceId) + UUID.randomUUID() + keySuffix + "." + extension;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=MediaPurposeTest -q`
Expected: PASS (13 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/media/storage/MediaPurpose.java \
        backend/src/test/java/com/enunas/backend/media/storage/MediaPurposeTest.java
git commit -m "feat(media): add MediaPurpose per-purpose upload policy enum"
```

---

### Task 4: `S3Config` — `S3Client` + `S3Presigner` beans

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/media/storage/S3Config.java`

**Interfaces:**
- Consumes: `MediaStorageProperties` (Task 2).
- Produces: Spring beans `S3Client`, `S3Presigner`.

- [ ] **Step 1: Write the config class**

No dedicated unit test — this is bean wiring exercised end-to-end by Task 8's container test and every `@SpringBootTest` from here on (if it's wired wrong, the context fails to start, which is its own fast failure signal).

```java
package com.enunas.backend.media.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Prod = IAM role via {@link DefaultCredentialsProvider}, no static keys anywhere. Dev/test point
 * {@code enunas.media.endpoint} at LocalStack/S3Mock, which also switches on path-style access.
 */
@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final MediaStorageProperties properties;

    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                // Presigning (S3Presigner below) needs no HTTP client — this one is for the real
                // calls S3Client makes: HeadObject, PutObjectTagging, DeleteObject.
                .httpClient(UrlConnectionHttpClient.create());
        if (usesCustomEndpoint()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        // No SdkHttpClient — presigning is pure local SigV4 computation, no network call happens.
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (usesCustomEndpoint()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    private boolean usesCustomEndpoint() {
        return properties.getEndpoint() != null && !properties.getEndpoint().isBlank();
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd backend && ./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/media/storage/S3Config.java
git commit -m "feat(media): add S3Client/S3Presigner beans with LocalStack/S3Mock endpoint override"
```

---

### Task 5: `MediaUrlResolver`

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/media/storage/MediaUrlResolver.java`
- Test: `backend/src/test/java/com/enunas/backend/media/storage/MediaUrlResolverTest.java`

**Interfaces:**
- Consumes: `MediaStorageProperties` (Task 2).
- Produces: `MediaUrlResolver.resolve(String key): String` — the **only** place that knows the CDN host.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.media.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaUrlResolverTest {

    private MediaUrlResolver resolver(String cdnBaseUrl) {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setCdnBaseUrl(cdnBaseUrl);
        return new MediaUrlResolver(properties);
    }

    @Test
    void resolve_prependsCdnBase() {
        assertThat(resolver("https://cdn.enunas.com").resolve("products/1/images/abc.jpg"))
                .isEqualTo("https://cdn.enunas.com/products/1/images/abc.jpg");
    }

    @Test
    void resolve_nullKey_returnsNull() {
        assertThat(resolver("https://cdn.enunas.com").resolve(null)).isNull();
    }

    @Test
    void resolve_blankKey_returnsNull() {
        assertThat(resolver("https://cdn.enunas.com").resolve("")).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MediaUrlResolverTest -q`
Expected: FAIL — compile error, `MediaUrlResolver` does not exist.

- [ ] **Step 3: Write the resolver**

```java
package com.enunas.backend.media.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The only place in the codebase that knows the CDN host. */
@Component
@RequiredArgsConstructor
public class MediaUrlResolver {

    private final MediaStorageProperties properties;

    /**
     * Returns {@code null} for a null/blank key (e.g. an optional brand logo/hero that was never
     * set) so DTOs never emit a broken "cdnBase/null" URL.
     */
    public String resolve(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return properties.getCdnBaseUrl() + "/" + key;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=MediaUrlResolverTest -q`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/media/storage/MediaUrlResolver.java \
        backend/src/test/java/com/enunas/backend/media/storage/MediaUrlResolverTest.java
git commit -m "feat(media): add MediaUrlResolver — key to CDN URL"
```

---

### Task 6: `V25` migration + entity updates

**Files:**
- Create: `backend/src/main/resources/db/migration/V25__media_storage_keys.sql`
- Modify: `backend/src/main/java/com/enunas/backend/media/ProductImage.java`
- Modify: `backend/src/main/java/com/enunas/backend/media/ProductVideo.java`
- Modify: `backend/src/main/java/com/enunas/backend/brandpartner/BrandPartner.java`
- Test: `backend/src/test/java/com/enunas/backend/media/ProductMediaStorageKeyTest.java`

**Interfaces:**
- Produces: `ProductImage.getStorageKey()/setStorageKey(String)`; `ProductVideo.getStorageKey()/setStorageKey(String)` + `getThumbnailStorageKey()/setThumbnailStorageKey(String)`; `BrandPartner.getLogoStorageKey()/setLogoStorageKey(String)` + `getHeroStorageKey()/setHeroStorageKey(String)`.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.media;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class ProductMediaStorageKeyTest {

    @Autowired private ProductImageRepository imageRepository;
    @Autowired private ProductVideoRepository videoRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private BrandPartnerRepository brandPartnerRepository;
    @Autowired private UserRepository userRepository;

    private Product seedProduct() {
        User user = userRepository.save(User.builder()
                .email("brand@it.local").password("x").role(Role.BRAND_PARTNER).enabled(true).build());
        BrandPartner brand = brandPartnerRepository.save(
                BrandPartner.builder().user(user).brandName("Acme").slug("acme").build());
        return productRepository.save(Product.builder()
                .name("Tee").slug("tee").brand(brand).creator(user).build());
    }

    @Test
    void savesAndReadsProductImageStorageKey() {
        Product product = seedProduct();
        String key = "products/" + product.getId() + "/images/abc.jpg";
        ProductImage saved = imageRepository.save(ProductImage.builder()
                .product(product).storageKey(key).primary(true).displayOrder(0).build());

        assertThat(imageRepository.findById(saved.getId()).orElseThrow().getStorageKey()).isEqualTo(key);
    }

    @Test
    void productImage_nullStorageKey_violatesNotNullConstraint() {
        Product product = seedProduct();
        assertThatThrownBy(() -> imageRepository.saveAndFlush(
                ProductImage.builder().product(product).storageKey(null).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void savesAndReadsProductVideoStorageKeys() {
        Product product = seedProduct();
        String key = "products/" + product.getId() + "/videos/abc.mp4";
        String thumbKey = "products/" + product.getId() + "/videos/abc-t.jpg";
        ProductVideo saved = videoRepository.save(ProductVideo.builder()
                .product(product).storageKey(key).thumbnailStorageKey(thumbKey).build());

        ProductVideo found = videoRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStorageKey()).isEqualTo(key);
        assertThat(found.getThumbnailStorageKey()).isEqualTo(thumbKey);
    }

    @Test
    void brandPartner_logoAndHeroStorageKeys_areNullableAndPersist() {
        User user = userRepository.save(User.builder()
                .email("brand2@it.local").password("x").role(Role.BRAND_PARTNER).enabled(true).build());
        BrandPartner brand = brandPartnerRepository.save(BrandPartner.builder()
                .user(user).brandName("Beta").slug("beta").build());
        assertThat(brand.getLogoStorageKey()).isNull();
        assertThat(brand.getHeroStorageKey()).isNull();

        brand.setLogoStorageKey("brands/" + brand.getId() + "/logo/abc.png");
        brand.setHeroStorageKey("brands/" + brand.getId() + "/hero/def.jpg");
        BrandPartner saved = brandPartnerRepository.save(brand);

        BrandPartner found = brandPartnerRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getLogoStorageKey()).isEqualTo("brands/" + brand.getId() + "/logo/abc.png");
        assertThat(found.getHeroStorageKey()).isEqualTo("brands/" + brand.getId() + "/hero/def.jpg");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ProductMediaStorageKeyTest -q`
Expected: FAIL — compile error (`storageKey`/`thumbnailStorageKey`/`logoStorageKey`/`heroStorageKey` don't exist yet) and/or schema validation failure.

- [ ] **Step 3: Write the migration**

```sql
-- =============================================================================
-- V25: S3 media storage keys — hard cutover (key-only, presigned PUT + CDN read)
--
-- product_images/product_videos are confirmed EMPTY (product owner). Hard cutover: DELETE before
-- adding the NOT NULL columns is what makes that legal without a default. No dual-read path —
-- storage_key is the single source from this migration forward. brand_partners.logo_url is
-- likewise dropped; hero is net-new and key-only from birth (no legacy hero_image_url ever existed).
-- =============================================================================

DELETE FROM product_images;
DELETE FROM product_videos;

ALTER TABLE product_images DROP COLUMN image_url;
ALTER TABLE product_images ADD COLUMN storage_key varchar(512) NOT NULL;

ALTER TABLE product_videos DROP COLUMN video_url;
ALTER TABLE product_videos DROP COLUMN thumbnail_url;
ALTER TABLE product_videos ADD COLUMN storage_key varchar(512) NOT NULL;
ALTER TABLE product_videos ADD COLUMN thumbnail_storage_key varchar(512);

ALTER TABLE brand_partners DROP COLUMN logo_url;
ALTER TABLE brand_partners ADD COLUMN logo_storage_key varchar(512);
ALTER TABLE brand_partners ADD COLUMN hero_storage_key varchar(512);
```

- [ ] **Step 4: Update `ProductImage`**

In `backend/src/main/java/com/enunas/backend/media/ProductImage.java`, replace:

```java
    @Column(nullable = false)
    private String imageUrl;
```

with:

```java
    @Column(nullable = false)
    private String storageKey;
```

- [ ] **Step 5: Update `ProductVideo`**

In `backend/src/main/java/com/enunas/backend/media/ProductVideo.java`, replace:

```java
    @Column(nullable = false)
    private String videoUrl;

    private String title;

    private String thumbnailUrl;
```

with:

```java
    @Column(nullable = false)
    private String storageKey;

    private String title;

    private String thumbnailStorageKey;
```

- [ ] **Step 6: Update `BrandPartner`**

In `backend/src/main/java/com/enunas/backend/brandpartner/BrandPartner.java`, replace:

```java
    private String logoUrl;
```

with:

```java
    private String logoStorageKey;

    /** Net-new, key-only from birth — no legacy free-text hero_image_url ever existed. */
    private String heroStorageKey;
```

- [ ] **Step 7: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ProductMediaStorageKeyTest -q`
Expected: PASS (4 tests). Note: this will still fail to *compile the rest of the module* until Tasks 9–12 finish updating every other reference to the old field names — that's expected; run this test class in isolation for now (`-Dtest=ProductMediaStorageKeyTest`), the full `./mvnw test` run comes back green only after Task 13.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V25__media_storage_keys.sql \
        backend/src/main/java/com/enunas/backend/media/ProductImage.java \
        backend/src/main/java/com/enunas/backend/media/ProductVideo.java \
        backend/src/main/java/com/enunas/backend/brandpartner/BrandPartner.java \
        backend/src/test/java/com/enunas/backend/media/ProductMediaStorageKeyTest.java
git commit -m "feat(media): V25 hard-cutover migration + storage_key entity fields"
```

---

### Task 7: `MediaStorageService` — presign, verify, delete

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/media/storage/MediaStorageService.java`
- Test: `backend/src/test/java/com/enunas/backend/media/storage/MediaStorageServiceTest.java`

**Interfaces:**
- Consumes: `S3Client`, `S3Presigner` (Task 4), `MediaStorageProperties` (Task 2), `MediaPurpose` (Task 3).
- Produces: `MediaStorageService.PresignedUpload` record (`key(): String`, `uploadUrl(): String`, `expiresAt(): Instant`); `presignUpload(MediaPurpose, long resourceId, String contentType, long contentLength): PresignedUpload`; `verifyUploaded(String key, MediaPurpose, long resourceId)` (throws `SecurityException` on foreign-prefix, `IllegalArgumentException` on missing/mismatched object); `delete(String key)`.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.media.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaStorageServiceTest {

    @Mock private S3Client s3Client;

    private MediaStorageService service;

    @BeforeEach
    void setUp() {
        // Real presigner (pure local SigV4 computation, no network) so presign tests exercise the
        // actual signing path; only S3Client (real HTTP calls) is mocked.
        S3Presigner s3Presigner = S3Presigner.builder()
                .region(Region.EU_CENTRAL_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-key", "test-secret")))
                .build();

        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setBucket("enunas-media");
        properties.setRegion("eu-central-1");
        properties.setPresignTtl(Duration.ofMinutes(10));

        service = new MediaStorageService(s3Client, s3Presigner, properties);
    }

    @Test
    void presignUpload_validRequest_returnsServerGeneratedKeyAndSignedUrl() {
        MediaStorageService.PresignedUpload upload =
                service.presignUpload(MediaPurpose.PRODUCT_IMAGE, 42L, "image/jpeg", 1024L);

        assertThat(upload.key()).matches("products/42/images/[0-9a-f-]{36}\\.jpg");
        assertThat(upload.uploadUrl()).contains("enunas-media").contains(upload.key());
        assertThat(upload.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void presignUpload_disallowedContentType_throws() {
        assertThatThrownBy(() ->
                service.presignUpload(MediaPurpose.PRODUCT_IMAGE, 42L, "application/pdf", 1024L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void presignUpload_oversize_throws() {
        assertThatThrownBy(() -> service.presignUpload(
                MediaPurpose.PRODUCT_IMAGE, 42L, "image/jpeg", 11L * 1024 * 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyUploaded_foreignPrefix_throwsSecurityException_andNeverCallsS3() {
        assertThatThrownBy(() ->
                service.verifyUploaded("products/99/images/abc.jpg", MediaPurpose.PRODUCT_IMAGE, 42L))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(s3Client);
    }

    @Test
    void verifyUploaded_objectNeverUploaded_throwsIllegalArgument() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("Not Found").build());

        assertThatThrownBy(() ->
                service.verifyUploaded("products/42/images/abc.jpg", MediaPurpose.PRODUCT_IMAGE, 42L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyUploaded_wrongContentType_throwsIllegalArgument_andNeverClearsTag() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentType("application/pdf").contentLength(1024L).build());

        assertThatThrownBy(() ->
                service.verifyUploaded("products/42/images/abc.jpg", MediaPurpose.PRODUCT_IMAGE, 42L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(s3Client, never()).putObjectTagging(any(PutObjectTaggingRequest.class));
    }

    @Test
    void verifyUploaded_oversizeObject_throwsIllegalArgument() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentType("image/jpeg").contentLength(11L * 1024 * 1024).build());

        assertThatThrownBy(() ->
                service.verifyUploaded("products/42/images/abc.jpg", MediaPurpose.PRODUCT_IMAGE, 42L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyUploaded_validObject_clearsThePendingTag() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentType("image/jpeg").contentLength(1024L).build());

        service.verifyUploaded("products/42/images/abc.jpg", MediaPurpose.PRODUCT_IMAGE, 42L);

        verify(s3Client).putObjectTagging(argThat((PutObjectTaggingRequest req) ->
                req.bucket().equals("enunas-media")
                        && req.key().equals("products/42/images/abc.jpg")
                        && req.tagging().tagSet().isEmpty()));
    }

    @Test
    void delete_callsDeleteObjectWithBucketAndKey() {
        service.delete("products/42/images/abc.jpg");

        verify(s3Client).deleteObject(DeleteObjectRequest.builder()
                .bucket("enunas-media").key("products/42/images/abc.jpg").build());
    }

    @Test
    void delete_nullKey_isNoOp() {
        service.delete(null);
        verifyNoInteractions(s3Client);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MediaStorageServiceTest -q`
Expected: FAIL — compile error, `MediaStorageService` does not exist.

- [ ] **Step 3: Write the service**

```java
package com.enunas.backend.media.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Instant;
import java.util.List;

/**
 * Closes the presigned-PUT trust gap in three layers (see design doc §4): the presigned request
 * itself binds content-type/length, the presign TTL is short, and {@link #verifyUploaded} re-checks
 * everything server-side via HeadObject before any DB row is written.
 */
@Service
@RequiredArgsConstructor
public class MediaStorageService {

    /** Signed tag every presigned PUT carries; cleared on confirm. A bucket lifecycle rule expires
     *  anything still tagged this way after 24h — see docs/aws-media-setup.md. */
    static final String PENDING_TAG = "media-status=pending";

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final MediaStorageProperties properties;

    public PresignedUpload presignUpload(MediaPurpose purpose, long resourceId, String contentType,
                                          long contentLength) {
        purpose.validate(contentType, contentLength);
        String key = purpose.generateKey(resourceId, contentType);

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType(contentType)
                .contentLength(contentLength)
                .tagging(PENDING_TAG)
                .build();

        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(properties.getPresignTtl())
                .putObjectRequest(putObjectRequest)
                .build());

        return new PresignedUpload(key, presigned.url().toString(), presigned.expiration());
    }

    /**
     * The confirm step, folded into the caller's existing create/update call (no separate confirm
     * endpoint — see design doc §4/§6). Verifies the key belongs to this resource, the object was
     * really uploaded, and its real content-type/size match the purpose's policy; then clears the
     * pending lifecycle tag.
     */
    public void verifyUploaded(String key, MediaPurpose purpose, long resourceId) {
        String expectedPrefix = purpose.keyPrefix(resourceId);
        if (!key.startsWith(expectedPrefix)) {
            throw new SecurityException("storageKey does not belong to this resource");
        }

        HeadObjectResponse head;
        try {
            head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
        } catch (S3Exception e) {
            // HeadObject on a missing key inconsistently surfaces as NoSuchKeyException or a bare
            // S3Exception depending on SDK version/backend (no XML body on a HEAD 404 to identify
            // the specific error code from) — check the status code instead of the exception type.
            if (e.statusCode() == 404) {
                throw new IllegalArgumentException("No object was uploaded for key: " + key);
            }
            throw e;
        }

        if (!purpose.allowedContentTypes().contains(head.contentType())) {
            throw new IllegalArgumentException(
                    "Uploaded object has unexpected content type: " + head.contentType());
        }
        if (head.contentLength() == null || head.contentLength() > purpose.maxBytes()) {
            throw new IllegalArgumentException(
                    "Uploaded object exceeds the " + purpose.maxBytes() + " byte limit for " + purpose);
        }

        s3Client.putObjectTagging(PutObjectTaggingRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .tagging(Tagging.builder().tagSet(List.of()).build())
                .build());
    }

    public void delete(String key) {
        if (key == null) {
            return;
        }
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .build());
    }

    public record PresignedUpload(String key, String uploadUrl, Instant expiresAt) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=MediaStorageServiceTest -q`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/media/storage/MediaStorageService.java \
        backend/src/test/java/com/enunas/backend/media/storage/MediaStorageServiceTest.java
git commit -m "feat(media): add MediaStorageService — presign, verify, delete"
```

---

### Task 8: S3Mock round-trip integration test

**Files:**
- Test: `backend/src/test/java/com/enunas/backend/media/storage/MediaStorageServiceS3MockIntegrationTest.java`

**Interfaces:**
- Consumes: `MediaStorageService`, `S3Client` (Spring-wired, Tasks 4/7).

- [ ] **Step 1: Write the test**

This is the one test proving the plumbing actually works against an S3-shaped HTTP backend (see Design Decision 1/2 — this is *not* proof of real SigV4 acceptance; that remains the manual gate in `docs/aws-media-setup.md`).

```java
package com.enunas.backend.media.storage;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles({"test", "mock-payments"})
@Testcontainers
class MediaStorageServiceS3MockIntegrationTest {

    private static final String BUCKET = "enunas-media-test";

    @Container
    static final S3MockContainer S3_MOCK = new S3MockContainer("latest").withInitialBuckets(BUCKET);

    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("enunas.media.bucket", () -> BUCKET);
        registry.add("enunas.media.endpoint", S3_MOCK::getHttpEndpoint);
        registry.add("enunas.media.cdn-base-url", () -> "https://cdn.it.local");
    }

    @Autowired private MediaStorageService mediaStorageService;
    @Autowired private S3Client s3Client;

    @Test
    void presignThenRealPutThenConfirm_roundTrips() throws Exception {
        byte[] body = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);

        MediaStorageService.PresignedUpload upload =
                mediaStorageService.presignUpload(MediaPurpose.PRODUCT_IMAGE, 7L, "image/jpeg", body.length);
        assertThat(upload.key()).startsWith("products/7/images/");

        HttpResponse<Void> putResponse = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(upload.uploadUrl()))
                        .header("Content-Type", "image/jpeg")
                        .header("x-amz-tagging", "media-status=pending")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(putResponse.statusCode()).isBetween(200, 299);

        mediaStorageService.verifyUploaded(upload.key(), MediaPurpose.PRODUCT_IMAGE, 7L);

        var tags = s3Client.getObjectTagging(GetObjectTaggingRequest.builder()
                .bucket(BUCKET).key(upload.key()).build());
        assertThat(tags.tagSet()).isEmpty();

        mediaStorageService.delete(upload.key());
        assertThatThrownBy(() -> s3Client.headObject(HeadObjectRequest.builder()
                        .bucket(BUCKET).key(upload.key()).build()))
                .isInstanceOfAny(NoSuchKeyException.class, S3Exception.class);
    }
}
```

- [ ] **Step 2: Run it**

Run: `cd backend && ./mvnw test -Dtest=MediaStorageServiceS3MockIntegrationTest -q`
Expected: PASS. Requires Docker running locally/in CI (S3Mock pulls `s3mock:latest`, no auth token needed).

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/enunas/backend/media/storage/MediaStorageServiceS3MockIntegrationTest.java
git commit -m "test(media): S3Mock round-trip — presign, real PUT, confirm, tag-clear, delete"
```

---

### Task 9: Product media DTOs + presign DTOs

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/media/dto/ProductImageDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/media/dto/ProductImageResponseDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/media/dto/ProductVideoDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/media/dto/ProductVideoResponseDto.java`
- Create: `backend/src/main/java/com/enunas/backend/media/dto/PresignUploadRequestDto.java`
- Create: `backend/src/main/java/com/enunas/backend/media/dto/PresignUploadResponseDto.java`
- Test: `backend/src/test/java/com/enunas/backend/media/ProductMediaDtoTest.java`

**Interfaces:**
- Consumes: `ProductImage`/`ProductVideo` (Task 6), `MediaUrlResolver` (Task 5), `MediaPurpose` (Task 3), `MediaStorageService.PresignedUpload` (Task 7).
- Produces: `ProductImageResponseDto.from(ProductImage, MediaUrlResolver)`; `ProductVideoResponseDto.from(ProductVideo, MediaUrlResolver)`; `PresignUploadResponseDto.from(MediaStorageService.PresignedUpload)`.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.media;

import com.enunas.backend.media.dto.ProductImageResponseDto;
import com.enunas.backend.media.dto.ProductVideoResponseDto;
import com.enunas.backend.media.storage.MediaStorageProperties;
import com.enunas.backend.media.storage.MediaUrlResolver;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMediaDtoTest {

    private MediaUrlResolver resolver() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setCdnBaseUrl("https://cdn.it.local");
        return new MediaUrlResolver(properties);
    }

    @Test
    void productImageResponseDto_resolvesImageUrlFromStorageKey() {
        ProductImage image = ProductImage.builder()
                .id(1L).storageKey("products/1/images/abc.jpg").altText("front")
                .primary(true).displayOrder(0).createdAt(LocalDateTime.now()).build();

        ProductImageResponseDto dto = ProductImageResponseDto.from(image, resolver());

        assertThat(dto.getImageUrl()).isEqualTo("https://cdn.it.local/products/1/images/abc.jpg");
        assertThat(dto.getAltText()).isEqualTo("front");
    }

    @Test
    void productVideoResponseDto_resolvesVideoUrlAndThumbnailUrl() {
        ProductVideo video = ProductVideo.builder()
                .id(1L).storageKey("products/1/videos/abc.mp4")
                .thumbnailStorageKey("products/1/videos/abc-t.jpg")
                .title("Demo").createdAt(LocalDateTime.now()).build();

        ProductVideoResponseDto dto = ProductVideoResponseDto.from(video, resolver());

        assertThat(dto.getVideoUrl()).isEqualTo("https://cdn.it.local/products/1/videos/abc.mp4");
        assertThat(dto.getThumbnailUrl()).isEqualTo("https://cdn.it.local/products/1/videos/abc-t.jpg");
    }

    @Test
    void productVideoResponseDto_noThumbnail_resolvesToNull() {
        ProductVideo video = ProductVideo.builder()
                .id(1L).storageKey("products/1/videos/abc.mp4").createdAt(LocalDateTime.now()).build();

        assertThat(ProductVideoResponseDto.from(video, resolver()).getThumbnailUrl()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ProductMediaDtoTest -q`
Expected: FAIL — compile error (DTOs don't have the new fields/signatures yet).

- [ ] **Step 3: Update `ProductImageDto`**

```java
package com.enunas.backend.media.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProductImageDto {

    @NotBlank
    private String storageKey;

    private String altText;

    private boolean primary;

    private int displayOrder;
}
```

- [ ] **Step 4: Update `ProductImageResponseDto`**

```java
package com.enunas.backend.media.dto;

import com.enunas.backend.media.ProductImage;
import com.enunas.backend.media.storage.MediaUrlResolver;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ProductImageResponseDto {

    private Long id;
    private String imageUrl;
    private String altText;
    private boolean primary;
    private int displayOrder;
    private LocalDateTime createdAt;

    public static ProductImageResponseDto from(ProductImage image, MediaUrlResolver resolver) {
        return ProductImageResponseDto.builder()
                .id(image.getId())
                .imageUrl(resolver.resolve(image.getStorageKey()))
                .altText(image.getAltText())
                .primary(image.isPrimary())
                .displayOrder(image.getDisplayOrder())
                .createdAt(image.getCreatedAt())
                .build();
    }
}
```

- [ ] **Step 5: Update `ProductVideoDto`**

```java
package com.enunas.backend.media.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProductVideoDto {

    @NotBlank
    private String storageKey;

    private String title;

    private String thumbnailStorageKey;
}
```

- [ ] **Step 6: Update `ProductVideoResponseDto`**

```java
package com.enunas.backend.media.dto;

import com.enunas.backend.media.ProductVideo;
import com.enunas.backend.media.storage.MediaUrlResolver;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ProductVideoResponseDto {

    private Long id;
    private String videoUrl;
    private String title;
    private String thumbnailUrl;
    private LocalDateTime createdAt;

    public static ProductVideoResponseDto from(ProductVideo video, MediaUrlResolver resolver) {
        return ProductVideoResponseDto.builder()
                .id(video.getId())
                .videoUrl(resolver.resolve(video.getStorageKey()))
                .title(video.getTitle())
                .thumbnailUrl(resolver.resolve(video.getThumbnailStorageKey()))
                .createdAt(video.getCreatedAt())
                .build();
    }
}
```

- [ ] **Step 7: Create `PresignUploadRequestDto`**

```java
package com.enunas.backend.media.dto;

import com.enunas.backend.media.storage.MediaPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class PresignUploadRequestDto {

    @NotNull
    private MediaPurpose purpose;

    @NotBlank
    private String contentType;

    @Positive
    private long contentLength;
}
```

- [ ] **Step 8: Create `PresignUploadResponseDto`**

```java
package com.enunas.backend.media.dto;

import com.enunas.backend.media.storage.MediaStorageService;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class PresignUploadResponseDto {

    private String key;
    private String uploadUrl;
    private Instant expiresAt;

    public static PresignUploadResponseDto from(MediaStorageService.PresignedUpload upload) {
        return PresignUploadResponseDto.builder()
                .key(upload.key())
                .uploadUrl(upload.uploadUrl())
                .expiresAt(upload.expiresAt())
                .build();
    }
}
```

- [ ] **Step 9: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ProductMediaDtoTest -q`
Expected: PASS (3 tests).

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/media/dto/ \
        backend/src/test/java/com/enunas/backend/media/ProductMediaDtoTest.java
git commit -m "feat(media): storageKey-based product media DTOs + presign request/response DTOs"
```

---

### Task 10: `MediaService` + `MediaController` rewiring

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/media/MediaService.java`
- Modify: `backend/src/main/java/com/enunas/backend/media/MediaController.java`
- Test: `backend/src/test/java/com/enunas/backend/media/MediaServiceTest.java`

**Interfaces:**
- Consumes: `MediaStorageService`, `MediaUrlResolver` (Tasks 5/7), `MediaPurpose` (Task 3), `PresignUploadRequestDto`/`ResponseDto` (Task 9).
- Produces: `MediaService.presignUpload(Long productId, PresignUploadRequestDto, User): PresignUploadResponseDto`; `addImage`/`addVideo` now confirm via `MediaStorageService.verifyUploaded`; `deleteImage`/`deleteVideo` now also call `MediaStorageService.delete`.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.media;

import com.enunas.backend.exception.ProductNotFoundException;
import com.enunas.backend.media.dto.PresignUploadRequestDto;
import com.enunas.backend.media.dto.ProductImageDto;
import com.enunas.backend.media.dto.ProductVideoDto;
import com.enunas.backend.media.storage.MediaPurpose;
import com.enunas.backend.media.storage.MediaStorageProperties;
import com.enunas.backend.media.storage.MediaStorageService;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock private ProductImageRepository imageRepository;
    @Mock private ProductVideoRepository videoRepository;
    @Mock private ProductRepository productRepository;
    @Mock private MediaStorageService mediaStorageService;
    @Mock private MediaUrlResolver mediaUrlResolver;

    @InjectMocks private MediaService mediaService;

    private User owner;
    private Product product;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(1L).build();
        product = Product.builder().id(42L).creator(owner).build();
    }

    @Test
    void addImage_confirmsUploadBeforeSaving() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.save(any(ProductImage.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductImageDto dto = new ProductImageDto();
        dto.setStorageKey("products/42/images/abc.jpg");

        mediaService.addImage(42L, dto, owner);

        verify(mediaStorageService).verifyUploaded("products/42/images/abc.jpg", MediaPurpose.PRODUCT_IMAGE, 42L);
    }

    @Test
    void addImage_wrongOwner_throwsSecurityException_beforeTouchingStorage() {
        Product othersProduct = Product.builder().id(42L)
                .creator(User.builder().id(999L).build()).build();
        when(productRepository.findById(42L)).thenReturn(Optional.of(othersProduct));

        ProductImageDto dto = new ProductImageDto();
        dto.setStorageKey("products/42/images/abc.jpg");

        assertThatThrownBy(() -> mediaService.addImage(42L, dto, owner))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(mediaStorageService);
    }

    @Test
    void addVideo_withThumbnail_confirmsBothKeys() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(videoRepository.save(any(ProductVideo.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductVideoDto dto = new ProductVideoDto();
        dto.setStorageKey("products/42/videos/abc.mp4");
        dto.setThumbnailStorageKey("products/42/videos/abc-t.jpg");

        mediaService.addVideo(42L, dto, owner);

        verify(mediaStorageService).verifyUploaded("products/42/videos/abc.mp4", MediaPurpose.PRODUCT_VIDEO, 42L);
        verify(mediaStorageService).verifyUploaded("products/42/videos/abc-t.jpg", MediaPurpose.VIDEO_THUMB, 42L);
    }

    @Test
    void deleteImage_deletesFromStorageAndRepository() {
        ProductImage image = ProductImage.builder().id(5L).product(product)
                .storageKey("products/42/images/abc.jpg").build();
        when(imageRepository.findById(5L)).thenReturn(Optional.of(image));

        mediaService.deleteImage(5L, owner);

        verify(mediaStorageService).delete("products/42/images/abc.jpg");
        verify(imageRepository).delete(image);
    }

    @Test
    void presignUpload_wrongScopePurpose_throwsIllegalArgument() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));

        PresignUploadRequestDto dto = new PresignUploadRequestDto();
        dto.setPurpose(MediaPurpose.BRAND_LOGO);
        dto.setContentType("image/jpeg");
        dto.setContentLength(1024);

        assertThatThrownBy(() -> mediaService.presignUpload(42L, dto, owner))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mediaStorageService);
    }

    @Test
    void presignUpload_delegatesToMediaStorageServiceWithProductIdAsResourceId() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(mediaStorageService.presignUpload(MediaPurpose.PRODUCT_IMAGE, 42L, "image/jpeg", 1024L))
                .thenReturn(new MediaStorageService.PresignedUpload(
                        "products/42/images/x.jpg", "https://signed.example/x", java.time.Instant.now()));

        PresignUploadRequestDto dto = new PresignUploadRequestDto();
        dto.setPurpose(MediaPurpose.PRODUCT_IMAGE);
        dto.setContentType("image/jpeg");
        dto.setContentLength(1024);

        assertThat(mediaService.presignUpload(42L, dto, owner).getKey()).isEqualTo("products/42/images/x.jpg");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MediaServiceTest -q`
Expected: FAIL — compile error (`MediaService` doesn't yet take these collaborators/methods).

- [ ] **Step 3: Rewrite `MediaService`**

```java
package com.enunas.backend.media;

import com.enunas.backend.exception.ProductNotFoundException;
import com.enunas.backend.media.dto.*;
import com.enunas.backend.media.storage.MediaPurpose;
import com.enunas.backend.media.storage.MediaStorageService;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MediaService {

    private final ProductImageRepository imageRepository;
    private final ProductVideoRepository videoRepository;
    private final ProductRepository productRepository;
    private final MediaStorageService mediaStorageService;
    private final MediaUrlResolver mediaUrlResolver;

    public PresignUploadResponseDto presignUpload(Long productId, PresignUploadRequestDto dto, User owner) {
        findProductAndVerifyOwnership(productId, owner);
        if (dto.getPurpose().scope() != MediaPurpose.Scope.PRODUCT) {
            throw new IllegalArgumentException(
                    "purpose " + dto.getPurpose() + " is not valid for product media upload");
        }
        MediaStorageService.PresignedUpload upload = mediaStorageService.presignUpload(
                dto.getPurpose(), productId, dto.getContentType(), dto.getContentLength());
        return PresignUploadResponseDto.from(upload);
    }

    @Transactional
    public ProductImageResponseDto addImage(Long productId, ProductImageDto dto, User owner) {
        Product product = findProductAndVerifyOwnership(productId, owner);
        mediaStorageService.verifyUploaded(dto.getStorageKey(), MediaPurpose.PRODUCT_IMAGE, productId);

        if (dto.isPrimary()) {
            imageRepository.findByProductIdAndPrimary(productId, true)
                    .ifPresent(img -> {
                        img.setPrimary(false);
                        imageRepository.save(img);
                    });
        }

        ProductImage image = ProductImage.builder()
                .product(product)
                .storageKey(dto.getStorageKey())
                .altText(dto.getAltText())
                .primary(dto.isPrimary())
                .displayOrder(dto.getDisplayOrder())
                .build();

        return ProductImageResponseDto.from(imageRepository.save(image), mediaUrlResolver);
    }

    public List<ProductImageResponseDto> getImages(Long productId) {
        return imageRepository.findByProductIdOrderByDisplayOrderAsc(productId).stream()
                .map(image -> ProductImageResponseDto.from(image, mediaUrlResolver))
                .toList();
    }

    @Transactional
    public void deleteImage(Long imageId, User owner) {
        ProductImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new ProductNotFoundException("Image not found with id: " + imageId));
        verifyProductOwnership(image.getProduct(), owner);
        mediaStorageService.delete(image.getStorageKey());
        imageRepository.delete(image);
    }

    @Transactional
    public ProductVideoResponseDto addVideo(Long productId, ProductVideoDto dto, User owner) {
        Product product = findProductAndVerifyOwnership(productId, owner);
        mediaStorageService.verifyUploaded(dto.getStorageKey(), MediaPurpose.PRODUCT_VIDEO, productId);
        if (dto.getThumbnailStorageKey() != null) {
            mediaStorageService.verifyUploaded(dto.getThumbnailStorageKey(), MediaPurpose.VIDEO_THUMB, productId);
        }

        ProductVideo video = ProductVideo.builder()
                .product(product)
                .storageKey(dto.getStorageKey())
                .title(dto.getTitle())
                .thumbnailStorageKey(dto.getThumbnailStorageKey())
                .build();

        return ProductVideoResponseDto.from(videoRepository.save(video), mediaUrlResolver);
    }

    public List<ProductVideoResponseDto> getVideos(Long productId) {
        return videoRepository.findByProductId(productId).stream()
                .map(video -> ProductVideoResponseDto.from(video, mediaUrlResolver))
                .toList();
    }

    @Transactional
    public void deleteVideo(Long videoId, User owner) {
        ProductVideo video = videoRepository.findById(videoId)
                .orElseThrow(() -> new ProductNotFoundException("Video not found with id: " + videoId));
        verifyProductOwnership(video.getProduct(), owner);
        mediaStorageService.delete(video.getStorageKey());
        if (video.getThumbnailStorageKey() != null) {
            mediaStorageService.delete(video.getThumbnailStorageKey());
        }
        videoRepository.delete(video);
    }

    private Product findProductAndVerifyOwnership(Long productId, User owner) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + productId));
        verifyProductOwnership(product, owner);
        return product;
    }

    private void verifyProductOwnership(Product product, User creator) {
        if (!product.getCreator().getId().equals(creator.getId())) {
            throw new SecurityException("You do not own this product");
        }
    }
}
```

- [ ] **Step 4: Add the upload-url endpoint to `MediaController`**

Add this method to `backend/src/main/java/com/enunas/backend/media/MediaController.java`, alongside the other endpoints:

```java
    @PostMapping("/upload-url")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<PresignUploadResponseDto> presignUpload(
            @PathVariable Long productId,
            @Valid @RequestBody PresignUploadRequestDto dto,
            @AuthenticationPrincipal User owner) {
        return ResponseEntity.ok(mediaService.presignUpload(productId, dto, owner));
    }
```

(The existing `import com.enunas.backend.media.dto.*;` wildcard import already covers `PresignUploadRequestDto`/`PresignUploadResponseDto` — no new import needed.)

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=MediaServiceTest -q`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/media/MediaService.java \
        backend/src/main/java/com/enunas/backend/media/MediaController.java \
        backend/src/test/java/com/enunas/backend/media/MediaServiceTest.java
git commit -m "feat(media): storageKey confirm/delete wiring + product media upload-url endpoint"
```

---

### Task 11: Brand partner DTOs

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/brandpartner/dto/RegisterBrandPartnerDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/brandpartner/dto/UpdateBrandPartnerDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/brandpartner/dto/BrandPartnerResponseDto.java`

**Interfaces:**
- Consumes: `BrandPartner` (Task 6), `MediaUrlResolver` (Task 5).
- Produces: `BrandPartnerResponseDto.from(BrandPartner, MediaUrlResolver)`.

- [ ] **Step 1: Drop `logoUrl` from `RegisterBrandPartnerDto`**

In `backend/src/main/java/com/enunas/backend/brandpartner/dto/RegisterBrandPartnerDto.java`, remove:

```java
    @Size(max = 255)
    @URL
    private String logoUrl;

```

Remove the now-unused `import org.hibernate.validator.constraints.URL;` only if `websiteUrl`'s `@URL` is the sole remaining user — it is not (see the field right below `logoUrl` in this same class), so **keep** the `URL` import; `websiteUrl` still uses it.

- [ ] **Step 2: Replace `logoUrl` with `logoStorageKey`/`heroStorageKey` in `UpdateBrandPartnerDto`**

In `backend/src/main/java/com/enunas/backend/brandpartner/dto/UpdateBrandPartnerDto.java`, replace:

```java
    @Size(max = 255)
    @URL
    private String logoUrl;
```

with:

```java
    @Size(max = 512)
    private String logoStorageKey;

    @Size(max = 512)
    private String heroStorageKey;
```

The `websiteUrl` field right below still uses `@URL`, so keep the `import org.hibernate.validator.constraints.URL;` line.

- [ ] **Step 3: Update `BrandPartnerResponseDto`**

Replace the class body with a resolver-based `from()`:

```java
package com.enunas.backend.brandpartner.dto;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandReturnAddress;
import com.enunas.backend.brandpartner.BrandStatus;
import com.enunas.backend.media.storage.MediaUrlResolver;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class BrandPartnerResponseDto {

    private Long id;
    private String brandName;
    private String slug;
    /** Contact person behind the brand — null for brands onboarded before V12. */
    private String firstName;
    private String lastName;
    private String description;
    private String logoUrl;
    /** Net-new, key-only from birth — no legacy free-text hero URL ever existed. */
    private String heroImageUrl;
    private String websiteUrl;
    private String instagramHandle;
    private String tiktokHandle;
    private String country;
    private String contactEmail;
    private String vatId;
    private String taxNumber;
    /** Derived from addressCountry (DE ⇒ true). Drives the Inland/Ausland badge + reverse charge. */
    private boolean domestic;
    private String legalName;
    private String addressStreet;
    private String addressPostalCode;
    private String addressCity;
    private String addressCountry;
    /** Nominated returns destination — null when the brand falls back to its §22f address. */
    private String returnRecipient;
    private String returnStreet;
    private String returnPostalCode;
    private String returnCity;
    private String returnCountry;
    private String returnInstructions;
    /**
     * The address returns are actually routed to, with the fallback already applied — what the
     * brand dashboard should display, so a brand can see the consequence of leaving it unset.
     */
    private String effectiveReturnAddress;
    private BrandStatus status;
    private boolean approved;
    private Long userId;
    private String userEmail;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static BrandPartnerResponseDto from(BrandPartner brand, MediaUrlResolver mediaUrlResolver) {
        return BrandPartnerResponseDto.builder()
                .id(brand.getId())
                .brandName(brand.getBrandName())
                .slug(brand.getSlug())
                .firstName(brand.getFirstName())
                .lastName(brand.getLastName())
                .description(brand.getDescription())
                .logoUrl(mediaUrlResolver.resolve(brand.getLogoStorageKey()))
                .heroImageUrl(mediaUrlResolver.resolve(brand.getHeroStorageKey()))
                .websiteUrl(brand.getWebsiteUrl())
                .instagramHandle(brand.getInstagramHandle())
                .tiktokHandle(brand.getTiktokHandle())
                .country(brand.getCountry())
                .contactEmail(brand.getContactEmail())
                .vatId(brand.getVatId())
                .taxNumber(brand.getTaxNumber())
                .domestic(brand.isDomestic())
                .legalName(brand.getLegalName())
                .addressStreet(brand.getAddressStreet())
                .addressPostalCode(brand.getAddressPostalCode())
                .addressCity(brand.getAddressCity())
                .addressCountry(brand.getAddressCountry())
                .returnRecipient(brand.getReturnRecipient())
                .returnStreet(brand.getReturnStreet())
                .returnPostalCode(brand.getReturnPostalCode())
                .returnCity(brand.getReturnCity())
                .returnCountry(brand.getReturnCountry())
                .returnInstructions(brand.getReturnInstructions())
                .effectiveReturnAddress(BrandReturnAddress.of(brand).formatted())
                .status(brand.getStatus())
                .approved(brand.isApproved())
                .userId(brand.getUser() != null ? brand.getUser().getId() : null)
                .userEmail(brand.getUser() != null ? brand.getUser().getEmail() : null)
                .createdAt(brand.getCreatedAt())
                .updatedAt(brand.getUpdatedAt())
                .build();
    }
}
```

- [ ] **Step 4: Verify compilation (call sites are fixed in Task 12)**

This task alone will not compile in isolation (`BrandPartnerService`/`AdminService` still call the old 1-arg `from(brand)` — fixed next task). No test run here; proceed directly to Task 12, which fixes every call site and is where the suite becomes green again.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/brandpartner/dto/RegisterBrandPartnerDto.java \
        backend/src/main/java/com/enunas/backend/brandpartner/dto/UpdateBrandPartnerDto.java \
        backend/src/main/java/com/enunas/backend/brandpartner/dto/BrandPartnerResponseDto.java
git commit -m "feat(brandpartner): storageKey-based logo/hero DTOs, drop apply-time logoUrl"
```

---

### Task 12: `BrandPartnerService`/`Controller` rewiring + `AdminService`

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/brandpartner/BrandPartnerService.java`
- Modify: `backend/src/main/java/com/enunas/backend/brandpartner/BrandPartnerController.java`
- Modify: `backend/src/main/java/com/enunas/backend/admin/AdminService.java`
- Modify: `backend/src/test/java/com/enunas/backend/brandpartner/BrandPartnerDtoValidationTest.java`
- Test: `backend/src/test/java/com/enunas/backend/brandpartner/BrandPartnerMediaServiceTest.java`

**Interfaces:**
- Consumes: `MediaStorageService`, `MediaUrlResolver` (Tasks 5/7), `MediaPurpose` (Task 3), `PresignUploadRequestDto`/`ResponseDto` (Task 9), `BrandPartnerResponseDto.from(BrandPartner, MediaUrlResolver)` (Task 11).
- Produces: `BrandPartnerService.presignMediaUpload(PresignUploadRequestDto, User): PresignUploadResponseDto`.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.brandpartner;

import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.brandpartner.dto.PresignUploadRequestDto;
import com.enunas.backend.brandpartner.dto.UpdateBrandPartnerDto;
import com.enunas.backend.media.storage.MediaPurpose;
import com.enunas.backend.media.storage.MediaStorageService;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrandPartnerMediaServiceTest {

    @Mock private BrandPartnerRepository brandPartnerRepository;
    @Mock private BrandEconomicsRepository brandEconomicsRepository;
    @Mock private UserRepository userRepository;
    @Mock private BCryptPasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private MediaStorageService mediaStorageService;
    @Mock private MediaUrlResolver mediaUrlResolver;

    @InjectMocks private BrandPartnerService service;

    private BrandPartner brand(long id) {
        User user = User.builder().id(1L).email("brand@it.local").role(Role.BRAND_PARTNER).build();
        return BrandPartner.builder().id(id).user(user).brandName("Acme").slug("acme").build();
    }

    @Test
    void updateMyProfile_logoStorageKey_confirmsBeforeSetting() {
        BrandPartner brand = brand(7L);
        User user = brand.getUser();
        when(brandPartnerRepository.findByUser(user)).thenReturn(Optional.of(brand));
        when(brandPartnerRepository.save(any(BrandPartner.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateBrandPartnerDto dto = new UpdateBrandPartnerDto();
        dto.setLogoStorageKey("brands/7/logo/abc.png");

        service.updateMyProfile(dto, user);

        verify(mediaStorageService).verifyUploaded("brands/7/logo/abc.png", MediaPurpose.BRAND_LOGO, 7L);
        assertThat(brand.getLogoStorageKey()).isEqualTo("brands/7/logo/abc.png");
    }

    @Test
    void updateMyProfile_heroStorageKey_confirmsBeforeSetting() {
        BrandPartner brand = brand(7L);
        User user = brand.getUser();
        when(brandPartnerRepository.findByUser(user)).thenReturn(Optional.of(brand));
        when(brandPartnerRepository.save(any(BrandPartner.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateBrandPartnerDto dto = new UpdateBrandPartnerDto();
        dto.setHeroStorageKey("brands/7/hero/def.jpg");

        service.updateMyProfile(dto, user);

        verify(mediaStorageService).verifyUploaded("brands/7/hero/def.jpg", MediaPurpose.BRAND_HERO, 7L);
        assertThat(brand.getHeroStorageKey()).isEqualTo("brands/7/hero/def.jpg");
    }

    @Test
    void presignMediaUpload_wrongScopePurpose_throwsIllegalArgument() {
        BrandPartner brand = brand(7L);
        User user = brand.getUser();
        when(brandPartnerRepository.findByUser(user)).thenReturn(Optional.of(brand));

        PresignUploadRequestDto dto = new PresignUploadRequestDto();
        dto.setPurpose(MediaPurpose.PRODUCT_IMAGE);
        dto.setContentType("image/jpeg");
        dto.setContentLength(1024);

        assertThatThrownBy(() -> service.presignMediaUpload(dto, user))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mediaStorageService);
    }

    @Test
    void presignMediaUpload_brandLogo_delegatesWithBrandIdAsResourceId() {
        BrandPartner brand = brand(7L);
        User user = brand.getUser();
        when(brandPartnerRepository.findByUser(user)).thenReturn(Optional.of(brand));
        when(mediaStorageService.presignUpload(MediaPurpose.BRAND_LOGO, 7L, "image/png", 2048L))
                .thenReturn(new MediaStorageService.PresignedUpload(
                        "brands/7/logo/x.png", "https://signed.example/x", java.time.Instant.now()));

        PresignUploadRequestDto dto = new PresignUploadRequestDto();
        dto.setPurpose(MediaPurpose.BRAND_LOGO);
        dto.setContentType("image/png");
        dto.setContentLength(2048);

        assertThat(service.presignMediaUpload(dto, user).getKey()).isEqualTo("brands/7/logo/x.png");
    }
}
```

Note: this test imports `com.enunas.backend.brandpartner.dto.PresignUploadRequestDto`, but Task 9 created `PresignUploadRequestDto` under `com.enunas.backend.media.dto`. **Use `com.enunas.backend.media.dto.PresignUploadRequestDto` here** — it is the one shared DTO used by both the product-media and brand-media presign flows (there is no brand-specific copy). Fix the import in the test file above to `import com.enunas.backend.media.dto.PresignUploadRequestDto;` before running it.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=BrandPartnerMediaServiceTest -q`
Expected: FAIL — compile error (`BrandPartnerService` doesn't yet take these collaborators/methods).

- [ ] **Step 3: Update `BrandPartnerService`**

Add imports:

```java
import com.enunas.backend.media.dto.PresignUploadRequestDto;
import com.enunas.backend.media.dto.PresignUploadResponseDto;
import com.enunas.backend.media.storage.MediaPurpose;
import com.enunas.backend.media.storage.MediaStorageService;
import com.enunas.backend.media.storage.MediaUrlResolver;
```

Add fields (alongside the existing `private final` fields):

```java
    private final MediaStorageService mediaStorageService;
    private final MediaUrlResolver mediaUrlResolver;
```

In `applyForBrand`, remove the `.logoUrl(dto.getLogoUrl())` line from the `BrandPartner.builder()` chain (logo can no longer be set at apply time — see Design Decision 3).

Replace every `BrandPartnerResponseDto.from(saved)` / `BrandPartnerResponseDto.from(findByUser(user))` / `BrandPartnerResponseDto.from(brandPartnerRepository.save(brand))` call in this file with the same expression plus `, mediaUrlResolver`, e.g. `BrandPartnerResponseDto.from(saved, mediaUrlResolver)`. There are 4 call sites in this file (lines ~137, 196, 229, 244 in the pre-change file).

In `updateMyProfile`, replace:

```java
        if (dto.getLogoUrl() != null) brand.setLogoUrl(dto.getLogoUrl());
```

with:

```java
        if (dto.getLogoStorageKey() != null) {
            mediaStorageService.verifyUploaded(dto.getLogoStorageKey(), MediaPurpose.BRAND_LOGO, brand.getId());
            brand.setLogoStorageKey(dto.getLogoStorageKey());
        }
        if (dto.getHeroStorageKey() != null) {
            mediaStorageService.verifyUploaded(dto.getHeroStorageKey(), MediaPurpose.BRAND_HERO, brand.getId());
            brand.setHeroStorageKey(dto.getHeroStorageKey());
        }
```

Add a new method (near `getMyProfile`/`updateMyProfile`):

```java
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public PresignUploadResponseDto presignMediaUpload(PresignUploadRequestDto dto, User user) {
        BrandPartner brand = findByUser(user);
        if (dto.getPurpose().scope() != MediaPurpose.Scope.BRAND) {
            throw new IllegalArgumentException(
                    "purpose " + dto.getPurpose() + " is not valid for brand media upload");
        }
        MediaStorageService.PresignedUpload upload = mediaStorageService.presignUpload(
                dto.getPurpose(), brand.getId(), dto.getContentType(), dto.getContentLength());
        return PresignUploadResponseDto.from(upload);
    }
```

- [ ] **Step 4: Add the upload-url endpoint to `BrandPartnerController`**

Add imports:

```java
import com.enunas.backend.media.dto.PresignUploadRequestDto;
import com.enunas.backend.media.dto.PresignUploadResponseDto;
```

Add the endpoint (alongside `updateMyProfile`):

```java
    /** Presigned upload URL for the brand's own logo/hero — POST /brandpartner/media/upload-url. */
    @PostMapping("/media/upload-url")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<PresignUploadResponseDto> presignMediaUpload(
            @RequestBody @Valid PresignUploadRequestDto dto,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(brandPartnerService.presignMediaUpload(dto, user));
    }
```

- [ ] **Step 5: Fix `AdminService`**

Add field:

```java
    private final com.enunas.backend.media.storage.MediaUrlResolver mediaUrlResolver;
```

Replace every `BrandPartnerResponseDto.from(saved)` / `BrandPartnerResponseDto.from(brand)` call in this file with `..., mediaUrlResolver)` appended — 5 call sites (lines ~91, 102, 112, 200, 253 in the pre-change file).

- [ ] **Step 6: Fix `BrandPartnerDtoValidationTest`**

Remove the `logoUrlAndWebsiteUrl_boundaryLength_enforcesNewCap` test's `logoUrl` assertions — `RegisterBrandPartnerDto` no longer has that field. Replace the test with a `websiteUrl`-only version:

```java
    @Test
    void websiteUrl_boundaryLength_enforcesNewCap() {
        RegisterBrandPartnerDto dto = validRegisterDto();

        String atMax = validUrlOfLength(255);
        dto.setWebsiteUrl(atMax);
        assertThat(validator.validate(dto)).isEmpty();

        String overMax = validUrlOfLength(256);
        dto.setWebsiteUrl(overMax);
        Set<ConstraintViolation<RegisterBrandPartnerDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "websiteUrl".equals(v.getPropertyPath().toString()));
    }
```

(`BrandPartnerEmailNormalizationTest` and `BrandPartnerVatIdToggleTest` need no changes — neither reaches `updateMyProfile` or a code path touching `logoUrl`/`heroUrl`.)

- [ ] **Step 7: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=BrandPartnerMediaServiceTest,BrandPartnerDtoValidationTest,BrandPartnerEmailNormalizationTest,BrandPartnerVatIdToggleTest -q`
Expected: PASS.

- [ ] **Step 8: Run the full build to confirm the whole module compiles again**

Run: `cd backend && ./mvnw -q compile test-compile`
Expected: BUILD SUCCESS — every call site touched by the Task 6/9/11 renames is now fixed.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/brandpartner/BrandPartnerService.java \
        backend/src/main/java/com/enunas/backend/brandpartner/BrandPartnerController.java \
        backend/src/main/java/com/enunas/backend/admin/AdminService.java \
        backend/src/test/java/com/enunas/backend/brandpartner/BrandPartnerDtoValidationTest.java \
        backend/src/test/java/com/enunas/backend/brandpartner/BrandPartnerMediaServiceTest.java
git commit -m "feat(brandpartner): storageKey confirm wiring for logo/hero + upload-url endpoint"
```

---

### Task 13: Full HTTP end-to-end test (S3Mock-backed)

**Files:**
- Test: `backend/src/test/java/com/enunas/backend/media/MediaEndToEndIntegrationTest.java`

**Interfaces:**
- Consumes: the full app (all tasks above), real S3Mock container.

- [ ] **Step 1: Write the test**

```java
package com.enunas.backend.media;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.brandpartner.BrandStatus;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"test", "mock-payments"})
@Testcontainers
class MediaEndToEndIntegrationTest {

    private static final String BUCKET = "enunas-media-e2e";

    @Container
    static final S3MockContainer S3_MOCK = new S3MockContainer("latest").withInitialBuckets(BUCKET);

    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("enunas.media.bucket", () -> BUCKET);
        registry.add("enunas.media.endpoint", S3_MOCK::getHttpEndpoint);
        registry.add("enunas.media.cdn-base-url", () -> "https://cdn.it.local");
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private BrandPartnerRepository brandPartnerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private BCryptPasswordEncoder passwordEncoder;

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @SuppressWarnings("unchecked")
    private String login(String email, String password) {
        Map<String, Object> resp = rest.postForObject("/auth/login",
                Map.of("email", email, "password", password), Map.class);
        return (String) resp.get("token");
    }

    private void putBytes(String uploadUrl, String contentType, byte[] body) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .header("Content-Type", contentType)
                        .header("x-amz-tagging", "media-status=pending")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isBetween(200, 299);
    }

    @Test
    void productImage_presignUploadConfirmAndGet_resolvesToCdnUrl() throws Exception {
        User brandUser = userRepository.save(User.builder()
                .email("e2e-brand@it.local").password(passwordEncoder.encode("Brand123!"))
                .role(Role.BRAND_PARTNER).enabled(true).adminApproved(true).build());
        BrandPartner brand = BrandPartner.builder()
                .user(brandUser).brandName("E2E Brand").slug("e2e-brand").build();
        brand.setStatus(BrandStatus.ACTIVE);
        brandPartnerRepository.save(brand);
        Product product = productRepository.save(Product.builder()
                .name("E2E Tee").slug("e2e-tee").brand(brand).creator(brandUser).build());

        String token = login("e2e-brand@it.local", "Brand123!");
        byte[] imageBytes = "hello-jpeg-bytes".getBytes(StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> presignResp = rest.exchange(
                "/products/" + product.getId() + "/media/upload-url", HttpMethod.POST,
                new HttpEntity<>(Map.of("purpose", "PRODUCT_IMAGE", "contentType", "image/jpeg",
                        "contentLength", imageBytes.length), auth(token)), Map.class);
        assertThat(presignResp.getStatusCode().is2xxSuccessful()).as("presign: %s", presignResp.getBody()).isTrue();
        String key = (String) presignResp.getBody().get("key");
        String uploadUrl = (String) presignResp.getBody().get("uploadUrl");

        putBytes(uploadUrl, "image/jpeg", imageBytes);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> createResp = rest.exchange(
                "/products/" + product.getId() + "/media/images", HttpMethod.POST,
                new HttpEntity<>(Map.of("storageKey", key, "primary", true, "displayOrder", 0),
                        auth(token)), Map.class);
        assertThat(createResp.getStatusCode().is2xxSuccessful()).as("create: %s", createResp.getBody()).isTrue();
        assertThat(createResp.getBody().get("imageUrl")).isEqualTo("https://cdn.it.local/" + key);

        ResponseEntity<List> listResp = rest.exchange(
                "/products/" + product.getId() + "/media/images", HttpMethod.GET, null, List.class);
        assertThat(listResp.getBody()).hasSize(1);
    }

    @Test
    void brandLogo_presignUploadConfirmAndGetMe_resolvesToCdnUrl() throws Exception {
        User brandUser = userRepository.save(User.builder()
                .email("e2e-logo@it.local").password(passwordEncoder.encode("Brand123!"))
                .role(Role.BRAND_PARTNER).enabled(true).adminApproved(true).build());
        BrandPartner brand = BrandPartner.builder()
                .user(brandUser).brandName("E2E Logo Brand").slug("e2e-logo-brand").build();
        brand.setStatus(BrandStatus.ACTIVE);
        brandPartnerRepository.save(brand);

        String token = login("e2e-logo@it.local", "Brand123!");
        byte[] logoBytes = "hi-a-fake-png".getBytes(StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> presignResp = rest.exchange(
                "/brandpartner/media/upload-url", HttpMethod.POST,
                new HttpEntity<>(Map.of("purpose", "BRAND_LOGO", "contentType", "image/png",
                        "contentLength", logoBytes.length), auth(token)), Map.class);
        assertThat(presignResp.getStatusCode().is2xxSuccessful()).as("presign: %s", presignResp.getBody()).isTrue();
        String key = (String) presignResp.getBody().get("key");
        String uploadUrl = (String) presignResp.getBody().get("uploadUrl");

        putBytes(uploadUrl, "image/png", logoBytes);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> updateResp = rest.exchange(
                "/brandpartner/me", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("logoStorageKey", key), auth(token)), Map.class);
        assertThat(updateResp.getStatusCode().is2xxSuccessful()).as("update: %s", updateResp.getBody()).isTrue();
        assertThat(updateResp.getBody().get("logoUrl")).isEqualTo("https://cdn.it.local/" + key);
    }
}
```

- [ ] **Step 2: Run it**

Run: `cd backend && ./mvnw test -Dtest=MediaEndToEndIntegrationTest -q`
Expected: PASS (2 tests).

- [ ] **Step 3: Run the full suite**

Run: `cd backend && ./mvnw test -q`
Expected: BUILD SUCCESS — every test in the project passes, including all pre-existing suites untouched by this feature.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/enunas/backend/media/MediaEndToEndIntegrationTest.java
git commit -m "test(media): full HTTP end-to-end — product image + brand logo through real controllers"
```

---

### Task 14: Runbook + env var docs

**Files:**
- Create: `docs/aws-media-setup.md`
- Modify: `backend/CLAUDE.md`

**Interfaces:** None (documentation only).

- [ ] **Step 1: Write the runbook**

```markdown
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

## 4. Bucket lifecycle rule (tag-based, not age-on-prefix)

Keys are organized by resource id, not lifecycle stage, so an age-only rule can't tell "orphaned"
from "legitimately old and confirmed" apart. Add a lifecycle rule instead:

- **Filter:** tag `media-status = pending`
- **Action:** expire (delete) after **24 hours**

This is generous past the 10-minute presign TTL (tolerates backend hiccups) but tight enough that
abandoned uploads don't linger. The backend clears this tag via `PutObjectTagging` the moment a
`storageKey` is confirmed (see `MediaStorageService.verifyUploaded`) — confirmed objects are never
tagged `pending` and are never touched by this rule.

## 5. IAM — least privilege, backend role

Scope to exactly these five prefixes on this one bucket, nothing broader:

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

## 6. Environment variables

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

## 7. Local dev without real AWS

Point `S3_ENDPOINT` at a local S3-compatible server (e.g. run `com.adobe.testing:s3mock` as a
Docker container, or LocalStack if you have a compliant license — see the plan's Design Decision 1
for why this project's automated tests use S3Mock, not LocalStack). `MEDIA_CDN_BASE_URL` can stay
empty in that case — the fail-fast check only requires *one* of the two.

## 8. Manual acceptance gates — not simulable, hard blockers

Run once by the owner after steps 1–5 above are applied. **No automated test substitutes for
these** — they are the only proof of real SigV4 signature enforcement, which neither S3Mock nor
LocalStack fully validates:

1. **One real presigned PUT + confirm against the actual `eu-central-1` bucket.** Proves SigV4
   canonicalization, region correctness, and bucket ownership/permissions.
2. **One real GET through CloudFront, checked in both directions:** the CDN serves the image
   *and* a direct S3 URL returns `AccessDenied`. The OAC deny is the actual security property
   being verified, not merely "loads via CDN."

The feature is not live until both gates pass, independent of how green the automated suite is.
```

- [ ] **Step 2: Update `backend/CLAUDE.md`'s env var table**

Add these rows to the existing environment variables table in `backend/CLAUDE.md`, after the `GOOGLE_OAUTH_CLIENT_ID` row:

```markdown
| `S3_BUCKET` | `enunas-media` | S3 bucket for product/brand media |
| `AWS_REGION` | `eu-central-1` | S3 bucket region |
| `MEDIA_CDN_BASE_URL` | — | CloudFront hostname; required unless `S3_ENDPOINT` is set — see `docs/aws-media-setup.md` |
| `S3_ENDPOINT` | — | LocalStack/S3Mock endpoint for local dev; leave empty for real AWS |
| `MEDIA_PRESIGN_TTL` | `PT10M` | Presigned upload URL TTL (ISO-8601 duration) |
```

- [ ] **Step 3: Commit**

```bash
git add docs/aws-media-setup.md backend/CLAUDE.md
git commit -m "docs(media): AWS setup runbook + env var table"
```

---

## Manual acceptance gates (not part of this plan's automated work)

Tracked here per the spec's own requirement that these never be reported as satisfied by the test
suite. Run once, by the infrastructure owner, after `docs/aws-media-setup.md` steps 1–5:

- [ ] **Gate 1:** One real presigned PUT + confirm against the actual `eu-central-1` bucket.
- [ ] **Gate 2:** One real GET through CloudFront (succeeds) + one direct S3 URL GET (returns `AccessDenied`).

The feature does not go live until both pass, independent of automated test results.

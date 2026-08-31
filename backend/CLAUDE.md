# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./mvnw clean package

# Run
./mvnw spring-boot:run

# Run tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=YourTestClassName

# Run a single test method
./mvnw test -Dtest=YourTestClassName#methodName

# Skip tests during build
./mvnw clean package -DskipTests
```

## Tech Stack

- **Java 21**, Spring Boot 4.0.5
- **Spring Data JPA** — database access via repositories
- **Spring Security** — authentication and authorization
- **Spring Mail** — email sending
- **Spring Validation** — bean validation (`@Valid`, `@NotBlank`, etc.)
- **PostgreSQL** — primary database (`Enunas` database)
- **Lombok** — reduces boilerplate (`@Data`, `@Builder`, `@RequiredArgsConstructor`, etc.)

## Environment Variables

All secrets are externalized. Copy `.env` and fill in values before running locally. In IntelliJ, load it via **Run Configuration → Environment variables** (use the EnvFile plugin or paste values manually).

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/Enunas` | Full JDBC URL |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | — | Database password (required) |
| `MAIL_HOST` | — (required) | SMTP host. Provider is Resend: `smtp.resend.com`. No code-level default — a missing value fails app startup loudly instead of silently falling back to an abandoned mail path. |
| `MAIL_PORT` | — (required) | SMTP port, `587` for Resend. No code-level default, same reasoning as `MAIL_HOST`. |
| `MAIL_USERNAME` | — | Resend SMTP **auth identity** — literally the string `resend`, not an email address. Never used as the sender address (see `MAIL_FROM_ADDRESS`). |
| `MAIL_PASSWORD` | — | Resend API key. |
| `MAIL_FROM_ADDRESS` | — (required) | The `From:` address on every outbound email. **Must be an address at a domain verified in the Resend dashboard** (DNS-verified — currently `enunas.com`) — an address at an unverified domain is silently rejected by Resend, not caught by this app. Deliberately a separate variable from `MAIL_USERNAME`: they were the same string under Gmail by coincidence, not by design — don't re-conflate them if the provider changes again. |
| `JWT_SECRET` | — | Base64-encoded HMAC-SHA256 key, must decode to ≥32 bytes (required) |
| `JWT_EXPIRATION` | `86400000` | Token TTL in milliseconds (default 24 h) |
| `MOLLIE_API_KEY` | — | Mollie API key (test: `test_xxx`, live: `live_xxx`) |
| `MOLLIE_WEBHOOK_URL` | — | Full public URL Mollie posts to, e.g. `https://api.enunas.com/webhooks/mollie` |
| `FRONTEND_BASE_URL` | `http://localhost:3000` | Frontend origin for post-payment redirect |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,https://enunas.com,https://www.enunas.com` | Comma-separated, exact origins (scheme+host, no path) allowed by Spring Security CORS — apex and `www` are different origins to the browser, list both if both are live |
| `ADMIN_EMAIL` | `admin@enunas.com` | Email for the seeded admin account |
| `ADMIN_PASSWORD` | — | Password for the seeded admin account (required) |
| `GOOGLE_OAUTH_CLIENT_ID` | — | Google OAuth 2.0 Client ID (`*.apps.googleusercontent.com`) that `POST /auth/google` validates ID tokens' `aud` claim against (required) |
| `S3_BUCKET` | `enunas-media` | S3 bucket for product/brand media |
| `AWS_REGION` | `eu-central-1` | S3 bucket region |
| `MEDIA_CDN_BASE_URL` | — | CloudFront hostname; required unless `S3_ENDPOINT` is set — see `docs/aws-media-setup.md` |
| `S3_ENDPOINT` | — | LocalStack/S3Mock endpoint for local dev; leave empty for real AWS |
| `MEDIA_PRESIGN_TTL` | `PT10M` | Presigned upload URL TTL (ISO-8601 duration) |

## Architecture

The project follows a standard Spring Boot layered architecture. As the codebase grows, organize code under `com.enunas.backend` in these packages:

```
controller/   — REST controllers (@RestController)
service/      — Business logic (@Service)
repository/   — Spring Data JPA interfaces (@Repository)
entity/       — JPA entities (@Entity)
dto/          — Request/response objects (no JPA annotations)
security/     — Security config, filters, JWT handling
exception/    — Global exception handler (@RestControllerAdvice)
```

### Schema management

Flyway owns the schema. Migrations live in `src/main/resources/db/migration` as `V<n>__<description>.sql` and run on startup (`spring.flyway.enabled: true`, `baseline-on-migrate: true`).

`ddl-auto` is `validate`: Hibernate checks that the entity model matches the migrated schema and **never modifies the database**. A mapping that disagrees with the schema fails startup rather than silently altering a table.

Two consequences worth internalising:

- **Every schema change needs a new `V<n>` script.** Editing an already-applied migration changes its checksum, and Flyway then refuses to start the application against any database that ran the old version.
- **Schema-generation-only annotations do nothing here.** `@Index`, `@ForeignKey`, and column `length` are read by DDL generation, which is off. They are documentation of what the migration created — useful, but keep them truthful, because nothing enforces that they match.

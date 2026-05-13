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
| `MAIL_HOST` | `smtp.gmail.com` | SMTP host |
| `MAIL_PORT` | `587` | SMTP port |
| `MAIL_USERNAME` | — | Email sender address |
| `MAIL_PASSWORD` | — | Email app password |
| `JWT_SECRET` | — | Base64-encoded HMAC-SHA256 key, must decode to ≥32 bytes (required) |
| `JWT_EXPIRATION` | `86400000` | Token TTL in milliseconds (default 24 h) |
| `MOLLIE_API_KEY` | — | Mollie API key (test: `test_xxx`, live: `live_xxx`) |
| `MOLLIE_WEBHOOK_URL` | — | Full public URL Mollie posts to, e.g. `https://api.enunas.com/webhooks/mollie` |
| `FRONTEND_BASE_URL` | `http://localhost:3000` | Frontend origin for post-payment redirect |
| `ADMIN_EMAIL` | `admin@enunas.com` | Email for the seeded admin account |
| `ADMIN_PASSWORD` | — | Password for the seeded admin account (required) |

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

`ddl-auto` is set to `update` — Hibernate manages schema changes automatically in development. Switch to `validate` or use Flyway/Liquibase before going to production.

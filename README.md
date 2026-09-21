# Enunas Backend

Spring Boot backend for the Enunas marketplace: brands, products, orders,
Mollie-backed checkout, and the VAT/commission reporting that sits behind them.

- **Java 21**, Spring Boot 4.0.5, Spring Data JPA, Spring Security
- **PostgreSQL** primary datastore, Flyway-migrated
- **S3** for product and brand media, **Resend** for transactional mail
- **Mollie** for payments

## Getting started

```bash
docker compose up -d postgres   # or point DB_URL at your own instance
cd backend && ./mvnw spring-boot:run
```

Copy `.env.example` to `.env` and fill it in first — every secret is
externalized and several have no default, so the app fails loudly on startup
rather than falling back silently. The full variable table lives in
[`backend/CLAUDE.md`](backend/CLAUDE.md).

```bash
cd backend && ./mvnw test                      # all tests
cd backend && ./mvnw test -Dtest=ClassName     # one class
```

## Documentation

| Where | What |
|---|---|
| [`backend/CLAUDE.md`](backend/CLAUDE.md) | Build commands, tech stack, env vars, layered architecture |
| [`system-documentation/`](system-documentation/) | Architecture docs, PlantUML flows, decision log, known gaps |
| [`docs/`](docs/) | AWS media setup, frontend integration notes, backend request specs |
| [`CLAUDE.md`](CLAUDE.md) | Repo-wide agent workflow |

## Agent skills

This repo ships a reproducible AI-agent setup. Anyone who clones it gets the
same skills, with no install step.

**`.agents/skills/` is committed.** It holds the vendored skill definitions and
is the source of truth. `.claude/skills/` is *not* committed — it contains
machine-local symlinks into `.agents/skills/`, recreated per-machine by the
installer. `skills-lock.json` pins every skill by source path and content hash.

Third-party skills are unmodified upstream copies under MIT (mattpocock,
cursor) and Apache-2.0 (neondatabase). See
[`.agents/skills/ATTRIBUTION.md`](.agents/skills/ATTRIBUTION.md).

### The workflow

Build → review → deepen. The full rules, and why the review order matters, are
in [`CLAUDE.md`](CLAUDE.md).

| Skill | Role |
|---|---|
| `tdd` | Red → green loop. Use while building, not after. |
| `/thermo-nuclear-code-quality-review` | Strict read-only maintainability review. Run **first**, scoped to the feature diff. |
| `/improve-codebase-architecture` | Scans for deepening opportunities, renders an HTML report, then grills through the one you pick. Run **second** — it mutates code. |
| `/to-spec` | Turns a conversation into a spec and files it as a GitHub issue. |
| `codebase-design`, `grilling`, `domain-modeling` | Called by the two review skills above. |

Run the reviews **sequentially, not in parallel**: the second one rewrites code
and `CONTEXT.md` as decisions land, so running the first alongside it would
review a moving target.

The two review skills are `disable-model-invocation`, so an agent will prompt
you to run them — it cannot start them itself.

### Setup on a fresh clone

Skills are already present. Two things are not:

```bash
winget install GitHub.cli && gh auth login   # required by /to-spec
npx skills add                                # only to re-link .claude/skills/
```

Agent conventions for this repo — issue tracker, domain docs — live in
[`docs/agents/`](docs/agents/).

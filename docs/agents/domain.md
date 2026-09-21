# Domain Docs

How the engineering skills should consume this repo's domain documentation when
exploring the codebase.

**Layout: single-context.** One `CONTEXT.md` plus `docs/adr/` at the repo root.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root: the domain glossary.
- **`docs/adr/`**: read ADRs that touch the area you're about to work in.
- **`system-documentation/`**: this repo already carries substantial
  architecture documentation that predates the ADR convention — in particular
  `system-documentation/decisions/decision-log.md` and
  `system-documentation/decisions/architecture-gaps.md`, plus PlantUML flows
  under `system-documentation/flows/` and `system-documentation/architecture/`.
  Treat the decision log as ADR-equivalent: read it before proposing changes in
  an area it covers, and don't re-litigate decisions recorded there.

If any of these files don't exist, **proceed silently**. Don't flag their
absence; don't suggest creating them upfront. The `domain-modeling` skill
(reached via `grilling` and `improve-codebase-architecture`) creates them
lazily when terms or decisions actually get resolved.

## File structure

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-....md
│   └── 0002-....md
├── system-documentation/        ← pre-existing architecture docs + decision log
└── backend/src/
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor
proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`.
Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either
you're inventing language the project doesn't use (reconsider) or there's a
real gap (note it for `domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR — or a decision recorded in
`system-documentation/decisions/decision-log.md` — surface it explicitly rather
than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders), but worth reopening because…_

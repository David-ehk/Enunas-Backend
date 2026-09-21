# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with
code in this repository.

The Java / Spring Boot backend has its own guide at `backend/CLAUDE.md` — read
that for build commands, tech stack, and module-level architecture. This file
covers repo-wide agent workflow only.

## Agent skills

### Issue tracker

Issues live as GitHub issues in `David-ehk/Enunas-Backend`, managed with the
`gh` CLI. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context: one `CONTEXT.md` plus `docs/adr/` at the repo root.
See `docs/agents/domain.md`.

### Payment debugging

Not enabled by default — see `docs/agents/mollie-mcp.md`. The Mollie MCP
(live account-state lookups) has per-session overhead the backend's own SDK
path doesn't need for normal feature work; enable it for the session when you
actually need it, then remove it again.

## Feature workflow

Build → review → deepen, with a gate right after intent-gathering so
throwaway work doesn't pay the full price of production work.

### 1. Elicit intent, then classify

`superpowers:brainstorming` — elicit intent/spec (skip if the conversation
already settled it).

Then classify in two words: **production** or **spike**.

- **production** → the full chain below, TDD enforced, all four review layers.
- **spike** → step 2's `writing-plans` only (skip it too if trivial). Skip
  TDD, skip all review layers. If a spike becomes real, it re-enters at step
  3 with tests written for what's being kept. (`test-driven-development`
  already carves out this exception in its own text — "throwaway
  prototypes, generated code, configuration files" — this gate just makes
  it a decision instead of fine print.)

### 2. Stress-test and plan (production only)

- `grilling` (mattpocock) — only for genuinely contested decisions. Don't
  run it reflexively alongside brainstorming on routine work; it does a
  different job (decision-tree stress-test vs. intent-gathering).
- `superpowers:writing-plans` — bite-sized, TDD-baked-in implementation plan.

### 3. Build test-first

`superpowers:subagent-driven-development` executes the plan: a fresh
implementer subagent per task, following `superpowers:test-driven-development`
(red → green) for each, task review via `requesting-code-review` after each
task, broad review at the end.

TDD is *how* this step executes, not a separate later stage.
`superpowers:test-driven-development` is the default entry point — not
mattpocock's `tdd` (same trigger, `disable-model-invocation` added to avoid
the collision; still available via explicit `/tdd` for its seam/anti-pattern
reference and its ties into `codebase-design` vocabulary).

Run `superpowers:verification-before-completion` before claiming any task done.

### 4. Review — four layers, in order

Each layer asks a different question; run them in this order, heaviest last:

1. **`requesting-code-review`** (superpowers, subagent) — did we build what
   the plan said?
2. **`/code-review`** (built-in, diff-scoped) — correctness bugs,
   reuse/simplification/efficiency cleanups.
3. **`/thermo-nuclear-code-quality-review`** (user-invoked,
   `disable-model-invocation: true` — Claude cannot start it autonomously) —
   strict maintainability: abstraction quality, giant files, spaghetti
   condition growth. Read-only.
4. **`/improve-codebase-architecture`** (user-invoked, same restriction,
   **run after (3), never alongside it**) — scans for deepening
   opportunities, writes an HTML report to the OS temp directory, then runs
   an interactive grilling loop that mutates code, `CONTEXT.md`, and ADRs. It
   weights recently-changed files from `git log`, so running it after the
   feature lands gives it the right hot spots. Calls `codebase-design`,
   `grilling`, and `domain-modeling` (all installed).

**Why this order, and why not three same-tier passes:** layers 1–2 check
against a written artifact (the plan, the diff) — bounded, mechanical.
Layers 3–4 are cross-file judgment calls where a miss is expensive to
recover later — see the model table below for why they're priced
differently, not just ordered differently.

If layer 4 produced a large refactor, re-run layer 3 scoped to that refactor
before shipping.

### 5. Finish

`superpowers:finishing-a-development-branch` — decide how to integrate.

`/to-spec` — file follow-up work as a GitHub issue with `ready-for-agent`.
Needs the `gh` CLI (see Prerequisite below).

## Bug workflow

`superpowers:systematic-debugging` replaces steps 1–2 above as the entry
point, then rejoins at step 3 for the fix.

## Model / cost policy

Model switching is **manual** — no skill or this file can enforce it. Only
skills that dispatch an `Agent` call (`subagent-driven-development`,
`dispatching-parallel-agents`, `requesting-code-review`) take a `model:`
param; everything else runs inline in whatever model the session is
currently on. Enforcement is `/model opus` before phases 1–2, `/model sonnet`
before phase 3 onward, and explicit `model:` overrides on `Agent` dispatches.
No `claude-budget`/`better-model`-style automation exists in this
environment — don't assume one does.

| Phase | Model | Why |
|---|---|---|
| `brainstorming` | Opus | Design decisions, low volume |
| `grilling` | Opus | Only when contested; skipped otherwise |
| `writing-plans` | Opus | Plan quality sets execution quality for everything downstream |
| `subagent-driven-development` — implementer | Sonnet | Mechanical, against an already-good plan |
| `subagent-driven-development` — per-task reviewer | Sonnet | Spec compliance, bounded scope |
| `subagent-driven-development` — boilerplate tasks | Haiku | Explicit `model:` override, only for tasks the plan flags mechanical |
| `requesting-code-review` | Sonnet | Checked against a written artifact |
| `/code-review` (built-in) | Sonnet, effort medium/high | Diff-scoped correctness + cleanup |
| `/thermo-nuclear-code-quality-review` | **Opus** | Cross-file abstraction/design judgment — the layer where a miss is hardest to recover; earns the expensive model |
| `/improve-codebase-architecture` | **Opus** (Sonnet if economizing) | Judgment-heavy but runs rarely, only on branches that touched architecture — cost is bounded by frequency, not by downgrading the model |
| `verification-before-completion` | Sonnet/Haiku | Run commands, check output |
| `finishing-a-development-branch` | Haiku/Sonnet | Mechanical decision tree |
| `/to-spec` | Sonnet | Writes to `gh` |

Two Opus picks in the review stack, not zero and not four: three same-tier
passes miss the same things three ways. The heterogeneous mix (two bounded
layers, two judgment-heavy ones) catches more per token than flattening
everything to one model.

## Context discipline

The biggest token lever isn't model tier or review count — it's not loading
things you're not using:

- Don't glob or survey the whole repo at session start. Start from the task.
- Any MCP server with per-session schema overhead and rare use gets
  **documented, not left enabled** — see `docs/agents/mollie-mcp.md` for the
  current example. Preserve the option through the doc, not the config.
- Review scope stays diff / `git log` hot-spots only, never a full-repo
  sweep — true for both mattpocock review skills and the built-in
  `/code-review`.
- Don't run `brainstorming` and `grilling` by default on the same task —
  escalate to `grilling` only when something's genuinely contested.

## Skill dependencies

`/improve-codebase-architecture` calls `codebase-design` (architecture
vocabulary: module, interface, depth, seam, adapter, leverage, locality),
`grilling` (the step-4 decision loop), and `domain-modeling` (keeps
`CONTEXT.md` and ADRs current). All three are installed.

Skills live in `.agents/skills/`, symlinked into `.claude/skills/`. See
`.agents/skills/ATTRIBUTION.md` for upstream licensing and the one recorded
modification (disabling `tdd`'s auto-invocation).

## Prerequisite: gh CLI

The issue-tracker workflow above assumes the `gh` CLI. It is **not currently
installed** on this machine. Install with `winget install GitHub.cli`, then
`gh auth login`, before using `/to-spec` or any issue operation.

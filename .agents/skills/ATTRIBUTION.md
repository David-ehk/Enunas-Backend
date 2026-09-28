# Vendored agent skills — attribution

The skills in this directory are third-party work, vendored into this repo so
the team gets a reproducible agent setup without each person re-running
`npx skills add`. They are unmodified copies of their upstream sources.

Upstream LICENSE files are not copied by the `skills` installer, so the
notices below serve as the required attribution. Refer to each upstream repo
for the full licence text.

| Skill | Upstream | Licence |
|---|---|---|
| `codebase-design` | [mattpocock/skills](https://github.com/mattpocock/skills) | MIT |
| `domain-modeling` | [mattpocock/skills](https://github.com/mattpocock/skills) | MIT |
| `grilling` | [mattpocock/skills](https://github.com/mattpocock/skills) | MIT |
| `improve-codebase-architecture` | [mattpocock/skills](https://github.com/mattpocock/skills) | MIT |
| `setup-matt-pocock-skills` | [mattpocock/skills](https://github.com/mattpocock/skills) | MIT |
| `tdd` | [mattpocock/skills](https://github.com/mattpocock/skills) | MIT |
| `to-spec` | [mattpocock/skills](https://github.com/mattpocock/skills) | MIT |
| `thermo-nuclear-code-quality-review` | [cursor/plugins](https://github.com/cursor/plugins) | MIT |
| `neon` | [neondatabase/agent-skills](https://github.com/neondatabase/agent-skills) | Apache-2.0 |
| `neon-postgres` | [neondatabase/agent-skills](https://github.com/neondatabase/agent-skills) | Apache-2.0 |
| `ponytail` | [dietrichgebert/ponytail](https://github.com/dietrichgebert/ponytail) | MIT |

## Modifications

- **`tdd/SKILL.md`**: added `disable-model-invocation: true` to the
  frontmatter. This repo's default TDD entry point is superpowers'
  `test-driven-development` (a separate, non-vendored plugin skill); `tdd`'s
  own near-identical auto-invoke trigger competed with it. `tdd` still runs
  via explicit `/tdd` invocation, and its `tests.md`/`mocking.md` content is
  unchanged — kept as manually-invoked reference (seams, anti-patterns, the
  `codebase-design` vocabulary tie-in). No other content in this file was
  touched.

All other files here are as installed. Apache-2.0 §4(b) requires stating
changes; the above is the only one to state. If you edit another vendored
skill, record it here too.

## Re-installing

`skills-lock.json` at the repo root pins every skill by source path and content
hash. To restore or update this directory:

```bash
npx skills add https://github.com/<source> --skill <name>
```

# TODOs Inbox + ROADMAP Workflow

Status: Draft (design approved 2026-05-22)
Owner: lazyhat

## Problem

Ideas, follow-ups, and "wishes" for the project currently live in several
disconnected places:

- `docs/TODOs.md` — a flat free-form notes file mixing feature ideas, language
  reviews, and IDE follow-ups.
- `docs/superpowers/todos/` — dated research notes and audits, but no rollup.
- `docs/superpowers/specs/` and `docs/superpowers/plans/` — formal artifacts
  for items already being designed/implemented.
- The author's head.

As a result:

1. **Capture** is fragile: when an idea appears, there is no obvious "drop it
   here so it's not lost" place that is also actually reviewed later.
2. **Prioritization / grouping** is missing: it is hard to answer "what is in
   flight, what is next, what is deferred, what is dropped".
3. **Progress visibility** is poor: completed items (e.g. the Workbench
   separation) are noted inline inside `TODOs.md`, mixed with raw ideas.

## Goals

- Single obvious place to dump new ideas without friction.
- Single curated place that gives a status slice across all themes.
- Clear, manual promotion step from inbox to roadmap (no magic, no
  auto-generation).
- Minimal overhead — no per-idea files, no required spec/plan, no deadlines.

## Non-goals

- Automatic generation of `ROADMAP.md` from `TODOs.md`.
- Per-idea markdown files under `docs/superpowers/todos/` (that directory keeps
  its current role: dated research notes / audits).
- Time-based planning (quarters, releases, deadlines). The roadmap is sliced by
  status, not by time.
- Replacing the existing `superpowers/specs|plans` pipeline. Roadmap entries
  may or may not gain a spec/plan; the link is optional.

## Artifacts

Two files at the top of `docs/`:

- `docs/TODOs.md` — **inbox**. Free-form, chronological dump of ideas. Loose
  structure. Source of truth for the original wording and discussion of an
  idea.
- `docs/ROADMAP.md` — **curated table**. A single markdown table giving a
  status/priority slice over ideas that have been promoted from the inbox.

The existing `docs/superpowers/todos/` directory is unchanged in role: it
keeps holding dated research notes and audits, which are a separate artifact
type from "wishes".

## `docs/TODOs.md` (inbox) format

Top of the file:

```markdown
# TODOs (inbox)

Free-form dump of ideas and wishes. Structure is loose on purpose.

Conventions:
- Each idea is a level-2 section: `## YYYY-MM-DD — short name`.
- When an idea is promoted to the roadmap, add a line right under its heading:
  `→ ROADMAP: R-NNN`.
- Implemented or rejected ideas stay in the file; mark them inline with
  **Реализовано.** / **Отклонено.** and a one-line reason.
```

Per-idea section:

```markdown
## 2026-05-22 — Capability check by imports

Свободный текст с обоснованием идеи, ссылками, набросками…

→ ROADMAP: R-002   <!-- added on promote -->
```

Rules:

- Heading format `## YYYY-MM-DD — <short name>` acts as the inbox "id" and is
  used as the anchor target from `ROADMAP.md`.
- Order is chronological (append new sections at the end). Do not reorder
  existing sections.
- The `→ ROADMAP: R-NNN` line is the only required edit when promoting; the
  inbox section is otherwise left as the historical record of the idea.
- Already-realized ideas (e.g. Workbench) keep their existing **Реализовано.**
  block; we additionally add a `→ ROADMAP: R-NNN` line and mirror them in the
  table with status `Done`.

## `docs/ROADMAP.md` (curated table) format

Top of the file (legend):

```markdown
# ROADMAP

Curated slice over ideas that have been promoted from `docs/TODOs.md`.

Statuses:
- `Backlog` — accepted into the roadmap, not scheduled.
- `Next`    — likely to be picked up soon.
- `Now`     — actively being worked on.
- `Done`    — shipped / merged.
- `Dropped` — explicitly decided against; reason in the inbox section.

Priorities: `High` / `Med` / `Low`. Use `—` for `Done` / `Dropped`.

Themes (extend deliberately, avoid synonyms):
`Language`, `VM`, `IDE`, `Networking`, `Create`, `Tooling`, `Docs`.
```

Main table:

```markdown
| ID    | Idea                                    | Theme    | Status   | Prio | Source (inbox)                                          | Spec / Plan |
|-------|-----------------------------------------|----------|----------|------|---------------------------------------------------------|-------------|
| R-001 | Workbench as separate authoring entity  | IDE      | Done     | —    | [TODOs.md](../TODOs.md#2026-04-15--workbench)           | [spec](2026-04-16-workbench-separate-entity-design.md) |
| R-002 | Capability check by imports             | Language | Backlog  | High | [TODOs.md](../TODOs.md#2026-05-22--capability-imports)  | — |
```

Column rules:

- **ID:** `R-NNN`, monotonically increasing, never reused. Allocate the next
  free number when promoting.
- **Idea:** short English/Russian title, ideally matching the inbox section
  name.
- **Theme:** one of the allowed tags listed in the legend. Extend the legend
  list before introducing a new theme.
- **Status:** one of the values listed in the legend.
- **Prio:** `High` / `Med` / `Low` for live items; `—` for `Done` / `Dropped`.
- **Source (inbox):** markdown link to the corresponding section anchor in
  `docs/TODOs.md`. Required unless the idea was born directly in the roadmap.
- **Spec / Plan:** link to a file under `docs/superpowers/specs/` or
  `docs/superpowers/plans/`, when one exists. Otherwise `—`.

Optional groupings (e.g. an "In flight" sub-header above the table) may be
added later if the single table grows uncomfortable, but the v1 of this
workflow uses one flat table.

## Workflow

1. **Capture.** A new idea is appended to `docs/TODOs.md` as a new
   `## YYYY-MM-DD — name` section. No further action is required.
2. **Promote.** When an idea is taken into the roadmap:
   - allocate the next `R-NNN`;
   - add a row to the table in `docs/ROADMAP.md` with an initial status of
     `Backlog` or `Next` and an appropriate priority;
   - add `→ ROADMAP: R-NNN` to the inbox section.
3. **Status updates.** Move the row's `Status` column as work progresses
   (`Backlog → Next → Now → Done` / `Dropped`). Discussion stays in the
   inbox section; the table stays compact.
4. **Spec / plan link.** When a spec or plan is written for the item, fill in
   the `Spec / Plan` column. This is the bridge to the existing
   `superpowers/specs|plans` pipeline and is fully optional per item.

## Migration of the existing `docs/TODOs.md`

One-shot cleanup, done as part of introducing this workflow:

- Split the current free-form content into `## YYYY-MM-DD — name` sections,
  preserving wording. Use the file's existing dates where known, otherwise
  the migration date.
- Sections that already say "Реализовано" (e.g. Workbench) keep that marker
  and additionally get `→ ROADMAP: R-NNN` linking to a `Done` row in the
  table.
- Sections describing things "отложено после переписывания UI на DSL"
  (Clipboard API, Shift+Arrow / selection) get promoted to `Backlog` rows
  with priority `Low` so the deferred work is visible.
- The large CKL libraries review stays as a single inbox section; individual
  follow-ups (visibility, re-exports, named/default args, error model,
  generics) may be promoted as separate roadmap rows over time, not as a
  big-bang migration.

## Open questions

None blocking. Future possible refinements (out of scope for v1):

- Whether to add a `Created` / `Updated` date column to the table.
- Whether to introduce a "Now / Next / Later / Done" grouped view alongside
  the flat table once it has many rows.
- Whether to allow promoting an idea straight into a spec without going
  through `Backlog` (currently allowed implicitly — the status simply starts
  at `Now`).

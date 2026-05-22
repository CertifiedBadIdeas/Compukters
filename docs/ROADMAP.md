# ROADMAP

Curated slice over ideas that have been promoted from [TODOs.md](TODOs.md).
The inbox is the source of truth for wording and discussion; this file is a
status view.

## Legend

**Statuses**

- `Backlog` — accepted into the roadmap, not scheduled.
- `Next`    — likely to be picked up soon.
- `Now`     — actively being worked on.
- `Done`    — shipped / merged.
- `Dropped` — explicitly decided against; reason stays in the inbox section.

**Priorities:** `High` / `Med` / `Low`. Use `—` for `Done` / `Dropped`.

**Themes** (extend deliberately, avoid synonyms):
`Language`, `VM`, `IDE`, `Networking`, `Create`, `Tooling`, `Docs`.

## Workflow

1. **Capture.** New ideas land in [TODOs.md](TODOs.md) as a new
   `## YYYY-MM-DD — name` section. No further action is required.
2. **Promote.** When an idea is taken into the roadmap:
   - allocate the next free `R-NNN`;
   - add a row to the table below with status `Backlog` or `Next` and a
     priority;
   - add `→ ROADMAP: R-NNN` under the corresponding section in
     [TODOs.md](TODOs.md).
3. **Status updates.** Move the row's `Status` column as work progresses
   (`Backlog → Next → Now → Done` / `Dropped`). Detailed discussion stays in
   the inbox.
4. **Spec / Plan link.** When a spec or plan is written for the item, fill in
   the `Spec / Plan` column. This is optional per item.

## Items

| ID | Idea | Theme | Status | Prio | Source (inbox) | Spec / Plan |
|----|------|-------|--------|------|----------------|-------------|
| R-001 | Workbench as separate Authoring Station | IDE | Done | — | [TODOs.md](TODOs.md#2026-04-16--workbench-как-отдельный-authoring-station) | [spec](superpowers/specs/2026-04-16-workbench-separate-entity-design.md) |
| R-002 | Workbench IDE: clipboard & selection (deferred until UI DSL) | IDE | Backlog | Low | [TODOs.md](TODOs.md#2026-05-22--workbench-ide-clipboard-и-выделение-отложено-после-переписывания-ui-на-dsl) | — |

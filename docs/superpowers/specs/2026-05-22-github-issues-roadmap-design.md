# GitHub-Issues-Based Roadmap

Status: Draft (design 2026-05-22)
Owner: lazyhat
Supersedes: [docs/superpowers/specs/2026-05-22-todos-roadmap-workflow-design.md](2026-05-22-todos-roadmap-workflow-design.md)

## Problem

The previous workflow stored ideas in `docs/TODOs.md` (inbox) and a curated
table in `docs/ROADMAP.md`. After one round of usage four pain points
surfaced:

- Manual allocation of `R-NNN` IDs.
- Anchors generated from Cyrillic headings are long, fragile, and break on
  rename.
- The same idea title lives both in the inbox section heading and in the
  roadmap table row.
- Columns of the table (theme/priority/status) are rigid and force decisions
  on capture; statuses must be updated by hand in two places.

The author wants a workflow that is **easy to maintain alone**, with zero
manual ID juggling and no two-place editing.

## Goals

- Single source of truth per idea, with automatically allocated, stable IDs.
- Status / priority / theme can be changed in one place without touching
  markdown anchors.
- A "what is in flight right now" view exists and is updated by the same
  action that changes status (no duplicated state).
- A capture path that takes seconds and requires no manual classification.
- The author can drive the whole thing solo, from VSCodium chat or terminal.

## Non-goals

- Synchronizing GitHub Issues back into markdown files in the repo. Issues
  are the source of truth; the repo no longer carries a roadmap document.
- Milestones, iterations, custom Project fields beyond the built-in Status.
- Time-based planning (deadlines, quarters).
- A replacement for the `docs/superpowers/specs|plans/` pipeline. Issues link
  to specs and plans by markdown link; design documents continue to live in
  the repo.

## Decision

Use **GitHub Issues + a GitHub Projects v2 board**, driven from VSCodium chat
via the official `github-mcp-server` (MCP) with `gh` CLI as a fallback.
Delete `docs/TODOs.md` and `docs/ROADMAP.md`.

The repository on GitHub is `LazyHat/Compukter-Kraft`; both Issues and
Projects are available there.

## Tooling

### MCP server

Add the official server `github/github-mcp-server` (remote / HTTPS variant)
to the VSCodium MCP configuration. Authentication uses a token from
`gh auth token` or `GITHUB_PERSONAL_ACCESS_TOKEN`. After enabling, the chat
agent gains tools for `list_issues`, `create_issue`, `update_issue`,
`add_labels`, project board operations, etc.

### Fallback CLI

`gh` (already installed, version 2.92.0) is the manual fallback for bulk
operations and for moments when MCP is unavailable. Both paths target the
same API; the choice is purely about ergonomics.

## Labels

Created once at setup time, reused thereafter.

**Theme** — describes what part of the project is touched:

- `theme:language`
- `theme:vm`
- `theme:ide`
- `theme:networking`
- `theme:create`
- `theme:tooling`
- `theme:docs`

**Priority** — only applied when explicitly decided (no default):

- `priority:high`
- `priority:medium`
- `priority:low`

**Kind** — distinguishes the few meta-issues:

- `kind:idea` — a large discussion or umbrella issue with sub-bullets, not a
  small actionable task.

**Status (special)**:

- `status:dropped` — combined with a closed state to distinguish "decided
  against" from "done".

New labels are added when a genuinely new dimension shows up; the bar to add
a label is "I've wanted this label at least twice".

## Status: a Project board, not labels

A single GitHub **Project v2 board** named `Roadmap` (project-level) is the
status view. Columns (`Status` field values):

- `Inbox`   — captured, not yet triaged.
- `Backlog` — accepted, no near-term plans.
- `Next`    — likely to be picked up soon.
- `Now`     — actively being worked on.
- `Done`    — shipped / merged. Equivalent to a closed issue without
  `status:dropped`.
- `Dropped` — explicitly decided against. Equivalent to a closed issue with
  `status:dropped` label.

Rules:

- A freshly created issue is auto-added to the board with status `Inbox`.
- Status changes happen on the board (or via MCP / `gh project item-edit`).
- Closing an issue moves it to `Done` (or to `Dropped` if `status:dropped`
  is set first).
- The board is the only "view"; there is no separate table to keep in sync.

## Per-issue conventions

- **Title:** English, short, imperative or noun phrase. Stable for the life
  of the issue and used in `Closes #N` commit footers.
- **Body:** any language (Russian is fine). Free-form. May contain a markdown
  task list for sub-items.
- **Labels:** theme is required; priority is optional; `kind:idea` only for
  umbrella issues.
- **Spec / Plan links:** a body line `Spec: docs/superpowers/specs/...md`
  and/or `Plan: docs/superpowers/plans/...md` when those exist. Plain
  markdown links; no automation required.
- **Cross-issue references:** standard GitHub `#NNN`; commits can carry
  `Closes #NNN` / `Refs #NNN`.

## Workflow

1. **Capture.** Create a new issue via MCP, chat, or `gh issue create`. At
   minimum: title + a theme label. The board auto-places it in `Inbox`.
2. **Triage.** Move the card on the board to `Backlog` / `Next` and add a
   priority label if you have an opinion. Skipping triage is fine.
3. **Pickup.** Move to `Now` when you start working. Optionally write a spec
   and link it from the issue body.
4. **Close.** Close the issue when done; the board moves it to `Done`. If
   the decision is "won't do", add `status:dropped` before closing and the
   board moves it to `Dropped`.

The whole loop happens via labels, the board, and the open/closed state. No
markdown is edited.

## Migration of the existing `docs/TODOs.md` content

Ten existing sections become ten issues:

| # | Title (English)                                            | Labels                        | Board column | State  |
|---|------------------------------------------------------------|-------------------------------|--------------|--------|
| 1 | Deep Create mod integration                                | `theme:create`                | Inbox        | open   |
| 2 | Capability checks from imports                             | `theme:language`              | Inbox        | open   |
| 3 | Turtle computer with Fuel/Inventory builtins               | `theme:create`                | Inbox        | open   |
| 4 | Extend import system (peripherals, scripts, deps)          | `theme:language`              | Inbox        | open   |
| 5 | Computer networking (broadcast channels)                   | `theme:networking`            | Inbox        | open   |
| 6 | git CLI client                                             | `theme:tooling`               | Inbox        | open   |
| 7 | Modems & relay towers between computers                    | `theme:networking`            | Inbox        | open   |
| 8 | Workbench as separate Authoring Station                    | `theme:ide`                   | Done         | closed |
| 9 | Workbench IDE: clipboard & text selection                  | `theme:ide`, `priority:low`   | Backlog      | open   |
| 10| CKL libraries & utilities — review and follow-ups          | `theme:language`, `kind:idea` | Backlog      | open   |

Bodies are copied verbatim from `docs/TODOs.md` into each issue's body. The
large CKL review (#10) keeps its full review text; concrete follow-ups
(visibility/exports, re-exports, top-level constants, named/default args,
error model, generics) are listed as a markdown task list at the bottom of
the issue body and may later be split into separate issues.

The Workbench `Done` issue (#8) links to its specs:

- `docs/superpowers/specs/2026-04-16-workbench-separate-entity-design.md`
- `docs/superpowers/specs/2026-04-30-device-authoring-domain-model-design.md`

## Repo-side cleanup

- Delete `docs/TODOs.md`.
- Delete `docs/ROADMAP.md`.
- Audit other docs (`docs/ARCHITECTURE.md`, `docs/superpowers/**`) for links
  to the two deleted files and fix or drop them.
- Mark the previous spec and plan as superseded by prepending a banner that
  points to this design and the new plan:
  - `docs/superpowers/specs/2026-05-22-todos-roadmap-workflow-design.md`
  - `docs/superpowers/plans/2026-05-22-todos-roadmap-workflow.md`

## Risks and mitigations

- **MCP server install fails or is unstable.** Mitigation: `gh` CLI is the
  fallback and covers the same operations.
- **Cloud-only ideas are unreachable offline.** Mitigation: drafting an issue
  body locally and creating it later via `gh issue create --body-file` is a
  one-liner.
- **Project board configuration drift over time.** Mitigation: the workflow
  uses only built-in `Status` field values; no custom fields, no
  automations.
- **Repo becomes public-private mismatch.** Out of scope for this design;
  the current repo visibility is unchanged.

## Open questions

None blocking. Possible future refinements:

- Automating "issue created without theme label" reminders via a small
  GitHub Action.
- Adding a `priority:critical` tier if low/medium/high becomes too coarse.
- Splitting the CKL umbrella issue into per-feature issues when work
  starts.

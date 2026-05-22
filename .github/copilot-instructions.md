# Copilot / agent instructions for Compukter-Kraft

All non-trivial work in this repo (anything that produces a commit on `dev` or `main`) MUST be bound to a GitHub issue that lives on the **Roadmap** project board. Use the `superpowers:using-github-roadmap` skill to create/select an issue, embed `Issue: #N` in every spec and plan header, move the issue through `Inbox → Backlog → Next → Now → Done`, and close it when the work is integrated.

If you are an agent reading this file: invoke `superpowers:using-github-roadmap` before invoking `brainstorming`, `writing-plans`, `executing-plans`, or `finishing-a-development-branch`.

## Roadmap config

```yaml roadmap
owner: lazyhat
repo: lazyhat/Compukter-Kraft
project_number: 6
project_id: PVT_kwHOBkSydc4BYgqn
status_field_id: PVTSSF_lAHOBkSydc4BYgqnzhTmClk
statuses:
  Inbox: "75588027"
  Backlog: "7fdcf485"
  Next: "2fd89f9a"
  Now: "0ea1b704"
  Done: "06288b34"
  Dropped: "bfde95f0"
```

## Labels

- `theme:language`, `theme:vm`, `theme:ide`, `theme:networking`, `theme:create`, `theme:tooling`, `theme:docs` — exactly one per issue.
- `priority:high`, `priority:medium`, `priority:low` — optional, for prioritisation inside a Status column.
- `kind:idea` — umbrella / discussion issue, not a concrete task.
- `status:dropped` — applied alongside `state_reason=not_planned` when closing rejected ideas.

## Spec & plan conventions

- English only. Specs live under `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`; plans under `docs/superpowers/plans/YYYY-MM-DD-<topic>.md`.
- First content line under the `# Title` MUST be `> Issue: [#N](https://github.com/lazyhat/Compukter-Kraft/issues/N)`.
- When a spec/plan is superseded, prepend a `> **Superseded by** [...](...)` banner; do not delete the file.

## Authentication

`gh project *` and `gh api` GraphQL mutations against user-owned Projects v2 require a **classic** PAT with the `project` scope (fine-grained PATs do not currently support user-owned Projects v2). Plain `gh issue create` / label / state edits work with either token type via REST.

## Shell pitfall

zsh hangs on `cat > f << EOF ... EOF && next_cmd` chains (heredoc inside `&&`). Always run heredocs as standalone commands, or write the file via your editor's file-write tool and call `gh ... --body-file f`.

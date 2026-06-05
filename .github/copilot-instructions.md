# Copilot / agent instructions for Compukter-Kraft

All non-trivial work in this repo (anything that produces a commit on `dev` or `main`) MUST be bound to a GitHub issue that lives on the **Roadmap** project board. Use the `superpowers:using-github-roadmap` skill to create/select an issue, embed `Issue: #N` in every spec and plan header, move the issue through `Inbox → Backlog → Next → Now → Done`, and close it when the work is integrated.

If you are an agent reading this file: invoke `superpowers:using-github-roadmap` before invoking `brainstorming`, `writing-plans`, `executing-plans`, or `finishing-a-development-branch`.

## GitHub tooling

Do not use the GitHub MCP server or GitHub app connector tools for this repository. Use the `gh` CLI for all GitHub
issue, pull request, repository, and Projects v2 operations. When a first-class `gh` command is insufficient, use
`gh api` for REST or GraphQL calls.

## Roadmap config

```yaml roadmap
owner: CertifiedBadIdeas
repo: CertifiedBadIdeas/Compukter-Kraft
project_number: 1
project_id: PVT_kwDODHkEV84BYh0b
status_field_id: PVTSSF_lADODHkEV84BYh0bzhTnEZo
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

## Agent working artifacts

- English only. Agent-generated specs and plans are working artifacts, not repository history.
- Put agent specs under `.agents/tmp/specs/YYYY-MM-DD-issue-N-<topic>-design.md`.
- Put agent plans under `.agents/tmp/plans/YYYY-MM-DD-issue-N-<topic>.md`.
- `.agents/tmp/` is intentionally ignored and must not be committed by default.
- First content line under the `# Title` MUST be `> Issue: [#N](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/N)`.
- Keep durable decisions, scope, acceptance criteria, verification, and commit links in the GitHub issue.
- Only create files under `docs/` when they are real project documentation, such as ABI references, architecture docs, profiling docs, or user-facing docs.

## Authentication

`gh project *` and `gh api` GraphQL mutations against org-owned Projects v2 require a **classic** PAT with the `project` scope (fine-grained PATs do not currently support Projects v2). Plain `gh issue create` / label / state edits work with either token type via REST.

## Shell pitfall

zsh hangs on `cat > f << EOF ... EOF && next_cmd` chains (heredoc inside `&&`). Always run heredocs as standalone commands, or write the file via your editor's file-write tool and call `gh ... --body-file f`.

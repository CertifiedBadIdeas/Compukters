# GitHub-Issues-Based Roadmap — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the markdown-based `docs/TODOs.md` + `docs/ROADMAP.md` workflow with GitHub Issues + a Projects v2 board, driven from VSCodium via the official `github-mcp-server` (with `gh` CLI as fallback), and migrate the existing 10 ideas.

**Architecture:** No production code changes. The plan touches:
- `.vscode/mcp.json` (new) — MCP server registration.
- `gh` CLI invocations against `LazyHat/Compukter-Kraft` to create labels and issues.
- A one-time manual GitHub.com web step to create the Projects v2 board and rename the `Status` field options.
- Deletion of `docs/TODOs.md` and `docs/ROADMAP.md`.
- Superseded banners on the previous spec and plan.

**Tech Stack:** `gh` CLI 2.92, Docker (for github-mcp-server), GitHub Issues / Projects v2.

**Spec:** [docs/superpowers/specs/2026-05-22/2026-05-22-github-issues-roadmap-design.md](../../specs/2026-05-22/2026-05-22-github-issues-roadmap-design.md)

---

## File Structure

- Create: `.vscode/mcp.json` — registers the local `github-mcp-server` (Docker variant) with token from an input prompt.
- Delete: `docs/TODOs.md`, `docs/ROADMAP.md`.
- Modify: `docs/superpowers/specs/2026-05-22/2026-05-22-todos-roadmap-workflow-design.md` — prepend `## Superseded` banner.
- Modify: `docs/superpowers/plans/2026-05-22/2026-05-22-todos-roadmap-workflow.md` — prepend `## Superseded` banner.
- No other repo files are touched. (Issues live on github.com.)

---

### Task 1: Register `github-mcp-server` in `.vscode/mcp.json`

**Files:**
- Create: `.vscode/mcp.json`

- [ ] **Step 1: Write the MCP server config**

Create `.vscode/mcp.json` with the following exact content:

```json
{
  "inputs": [
    {
      "id": "github_token",
      "type": "promptString",
      "description": "GitHub Personal Access Token (use `gh auth token` value)",
      "password": true
    }
  ],
  "servers": {
    "github": {
      "command": "docker",
      "args": [
        "run",
        "-i",
        "--rm",
        "-e",
        "GITHUB_PERSONAL_ACCESS_TOKEN",
        "ghcr.io/github/github-mcp-server"
      ],
      "env": {
        "GITHUB_PERSONAL_ACCESS_TOKEN": "${input:github_token}"
      }
    }
  }
}
```

Rationale for the Docker variant: it requires no separate install, the image is published by GitHub, and the same setup works on any machine with Docker.

- [ ] **Step 2: Pull the image so the first chat invocation isn't slow**

Run: `docker pull ghcr.io/github/github-mcp-server`
Expected: image downloaded; final line says `Status: Downloaded newer image for ghcr.io/github/github-mcp-server:latest`.

- [ ] **Step 3: Commit**

```bash
git add .vscode/mcp.json
git commit -m "chore: register github-mcp-server in .vscode/mcp.json"
```

Note: VSCodium needs a window reload to pick up the new MCP server. Reloading is NOT part of this task — subsequent tasks deliberately use `gh` CLI so the plan can be executed in one session without a reload.

---

### Task 2: Refresh `gh` PAT scopes for Projects

**Files:** none (auth state only).

The current PAT is fine-grained and may not include `project` scope. Projects v2 operations need it.

- [ ] **Step 1: Check current scopes**

Run: `gh auth status`
Expected: shows the active account `LazyHat` and a token. The scopes line may show `repo`, possibly without `project`.

- [ ] **Step 2: If `project` and `read:project` are missing, refresh scopes**

Run: `gh auth refresh -h github.com -s project,read:project`
Expected: opens a browser flow to approve the additional scopes; on success prints `✓ Authentication complete.`

If the PAT is fine-grained (token starts with `github_pat_`), the above may not work and a classic token is needed instead. In that case run `gh auth login` and select "Login with a web browser" → "HTTPS" → "Authorize via browser", granting `repo` + `project` scopes.

- [ ] **Step 3: Verify scopes**

Run: `gh auth status`
Expected: scope list includes `project` and `read:project` (or the fine-grained equivalents).

No commit — this task changes credentials, not files.

---

### Task 3: Create the labels in `LazyHat/Compukter-Kraft`

**Files:** none (GitHub state only).

- [ ] **Step 1: Create theme labels**

Run, each line separately:

```bash
gh label create "theme:language"   --color "5319e7" --description "Affects CKL language / frontend" --force
gh label create "theme:vm"         --color "0e8a16" --description "Affects RUX VM / runtime"        --force
gh label create "theme:ide"        --color "1d76db" --description "Workbench / IDE / editor"        --force
gh label create "theme:networking" --color "fbca04" --description "Computer networking"             --force
gh label create "theme:create"     --color "d93f0b" --description "Create mod integration"          --force
gh label create "theme:tooling"    --color "bfd4f2" --description "Build / dev / CLI tooling"       --force
gh label create "theme:docs"       --color "c5def5" --description "Documentation"                   --force
```

Expected: each command prints `✓ Label "theme:..." created in LazyHat/Compukter-Kraft` (or no error if `--force` updates an existing label).

- [ ] **Step 2: Create priority labels**

```bash
gh label create "priority:high"   --color "b60205" --description "High priority"   --force
gh label create "priority:medium" --color "fbca04" --description "Medium priority" --force
gh label create "priority:low"    --color "c2e0c6" --description "Low priority"    --force
```

- [ ] **Step 3: Create kind / status labels**

```bash
gh label create "kind:idea"      --color "6f42c1" --description "Umbrella / discussion issue, not a concrete task" --force
gh label create "status:dropped" --color "ededed" --description "Closed because the idea was rejected"             --force
```

- [ ] **Step 4: Verify the label set**

Run: `gh label list --limit 50 | grep -E '^(theme:|priority:|kind:|status:)' | sort`
Expected: 12 lines covering all 7 theme + 3 priority + 1 kind + 1 status labels listed above.

No commit — labels are GitHub state.

---

### Task 4: Create the 10 migration issues

**Files:** uses temp body files under `/tmp/ck-issue-*.md` that are deleted at the end.

For each issue: write the body verbatim from `docs/TODOs.md` into a temp file, then `gh issue create` with the proper labels. Each step shows the exact command and the exact body content; issues 1–7 have short bodies (a single paragraph copied from the inbox), issue 8 is the implemented Workbench (closed at the end), issue 9 lists the deferred clipboard/selection items, issue 10 carries the full CKL libraries review.

- [ ] **Step 1: Issue #1 — Deep Create mod integration**

```bash
cat > /tmp/ck-issue-01.md <<'EOF'
Глубокая интеграция с модом Create.
EOF
gh issue create \
  --title "Deep Create mod integration" \
  --label "theme:create" \
  --body-file /tmp/ck-issue-01.md
```

Expected: prints a URL of the created issue (`https://github.com/LazyHat/Compukter-Kraft/issues/1` if this is the first issue, otherwise the next number).

- [ ] **Step 2: Issue #2 — Capability checks from imports**

```bash
cat > /tmp/ck-issue-02.md <<'EOF'
На основе import можно определять, можно ли вообще запустить программу на этом
компьютере, возможно сделать какие-то запросы capability, peripheral, по
imports по сути можно однозначно понять от чего программа зависит. Зависеть
она может от инвентаря например, который есть только у черепашки.
EOF
gh issue create \
  --title "Capability checks from imports" \
  --label "theme:language" \
  --body-file /tmp/ck-issue-02.md
```

- [ ] **Step 3: Issue #3 — Turtle computer with Fuel/Inventory builtins**

```bash
cat > /tmp/ck-issue-03.md <<'EOF'
Сделать черепашку со своим набором builtins — У черепашки появляется Fuel и
Inventory. Можно сделать флаг конечно, черепашка это или нет, но даже не знаю
надо ли.
EOF
gh issue create \
  --title "Turtle computer with Fuel/Inventory builtins" \
  --label "theme:create" \
  --body-file /tmp/ck-issue-03.md
```

- [ ] **Step 4: Issue #4 — Extend import system**

```bash
cat > /tmp/ck-issue-04.md <<'EOF'
Сделать расширить систему import, добавив peripheral, возможно какой-то
обозреватель import чтобы можно было узнать вообще какие peripheral вообще
доступны. Так же сделать систему import файлов скриптов, чтобы можно было на
них ссылаться, и тогда по сути проверка файла на возможность запуска будет ещё
дополнена рекурсивной проверкой всех dependency файлов.
EOF
gh issue create \
  --title "Extend import system (peripherals, scripts, deps)" \
  --label "theme:language" \
  --body-file /tmp/ck-issue-04.md
```

- [ ] **Step 5: Issue #5 — Computer networking (broadcast channels)**

```bash
cat > /tmp/ck-issue-05.md <<'EOF'
Внешняя и внутренняя сеть между компьютерами, внутренняя сеть по сути своей
должна представлять систему broadcast channels, с id канала (не компьютера,
компьютеры не должны иметь возможность сообщатся просто напрямую).
EOF
gh issue create \
  --title "Computer networking (broadcast channels)" \
  --label "theme:networking" \
  --body-file /tmp/ck-issue-05.md
```

- [ ] **Step 6: Issue #6 — git CLI client**

```bash
cat > /tmp/ck-issue-06.md <<'EOF'
git cli клиент.
EOF
gh issue create \
  --title "git CLI client" \
  --label "theme:tooling" \
  --body-file /tmp/ck-issue-06.md
```

- [ ] **Step 7: Issue #7 — Modems & relay towers between computers**

```bash
cat > /tmp/ck-issue-07.md <<'EOF'
Сделать связь между компьютерами.

1. Сделать модемы — радиомодули или лазерные (если в космос например хахахаха).
2. Для модемов должна быть вышка, которая обслуживает эти модемы и обеспечивает
   связь между компьютерами.
EOF
gh issue create \
  --title "Modems & relay towers between computers" \
  --label "theme:networking" \
  --body-file /tmp/ck-issue-07.md
```

- [ ] **Step 8: Issue #8 — Workbench as separate Authoring Station (Done)**

```bash
cat > /tmp/ck-issue-08.md <<'EOF'
**Реализовано.** Workbench выделен в отдельный Authoring Station, описан в:

- `docs/superpowers/specs/2026-04-16/2026-04-16-workbench-separate-entity-design.md` —
  изначальный дизайн отдельной сущности.
- `docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md` —
  двухкатегорийная модель (Runtime Devices vs Authoring Stations).
- `docs/ARCHITECTURE.md` (раздел Domain Model) — формальное закрепление в
  архитектуре.
EOF
WORKBENCH_URL=$(gh issue create \
  --title "Workbench as separate Authoring Station" \
  --label "theme:ide" \
  --body-file /tmp/ck-issue-08.md)
echo "Created: $WORKBENCH_URL"
gh issue close "$WORKBENCH_URL" --reason completed --comment "Shipped — see linked specs."
```

Expected: issue is created, then closed with reason `completed`. The URL printed for later reference.

- [ ] **Step 9: Issue #9 — Workbench IDE: clipboard & text selection**

```bash
cat > /tmp/ck-issue-09.md <<'EOF'
Отложено после переписывания UI на DSL.

- **Clipboard API для CodeEditor.** Сейчас Ctrl+X/C/V в редакторе кода не работают — модель `EditorViewModel` поддерживает `selection`, но клавиатурные сочетания не подключены. Для полноценной IDE надо вынести clipboard-операции в отдельный интерфейс (доступ к системному буферу через Minecraft API) и обработать их в `CodeEditor` поверх текущей логики.
- **Shift+Arrow и выделение текста.** `EditorViewModel.selection: SelectionRange?` уже зарезервировано, но никем не выставляется. Нужно: расширить `WorkbenchStore` действиями `extendSelection*`, отрисовать прямоугольник выделения в `CodeEditor` (внутри ScrollArea), сделать `Backspace`/`Delete`/`Tab`/printable consume selection.
EOF
gh issue create \
  --title "Workbench IDE: clipboard & text selection" \
  --label "theme:ide,priority:low" \
  --body-file /tmp/ck-issue-09.md
```

- [ ] **Step 10: Issue #10 — CKL libraries & utilities — review and follow-ups**

The body of this issue is the full CKL libraries review from `docs/TODOs.md` (the entire section starting at `## 2026-05-22 — CKL: библиотеки и общие утилиты` and continuing to the end of the file). Copy it verbatim into the temp file, prefixed with a short note and followed by a follow-ups task list.

Concretely:

```bash
# 1. Extract the CKL review from TODOs.md into the temp file.
awk '/^## 2026-05-22 — CKL: библиотеки и общие утилиты/{p=1} p' docs/TODOs.md > /tmp/ck-issue-10-body.md

# 2. Drop the section heading line (the first line) — GitHub uses the issue title for that.
tail -n +2 /tmp/ck-issue-10-body.md > /tmp/ck-issue-10.md

# 3. Append the follow-ups task list.
cat >> /tmp/ck-issue-10.md <<'EOF'

---

## Follow-up tasks (to be split into separate issues later)

- [ ] Explicit visibility / exports (`pub fun`, `export fun`, file-level private by default)
- [ ] Re-export / facade files (e.g. `lib/math.ck` re-exporting `vector.ck`, `clamp.ck`)
- [ ] Top-level constants (`const PI: Int = ...`)
- [ ] Named / default arguments for ordinary functions
- [ ] Basic error model before generics (`Result`-like convention or builtin)
- [ ] Generics / traits (later, after the above)
EOF

gh issue create \
  --title "CKL libraries & utilities — review and follow-ups" \
  --label "theme:language,kind:idea" \
  --body-file /tmp/ck-issue-10.md
```

Expected: the issue is created with the full review text plus the task list.

- [ ] **Step 11: Verify all 10 issues exist with the right labels**

Run: `gh issue list --state all --limit 20 --json number,title,labels,state | jq -r '.[] | "\(.number)\t\(.state)\t\([.labels[].name] | join(","))\t\(.title)"'`
Expected: 10 issues. The Workbench one is `CLOSED` with label `theme:ide`. The clipboard one has `theme:ide,priority:low`. The CKL review has `theme:language,kind:idea`. The rest are `OPEN` with a single `theme:*` label.

- [ ] **Step 12: Clean up temp files**

```bash
rm -f /tmp/ck-issue-*.md
```

No commit — issues are GitHub state.

---

### Task 5: Delete `docs/TODOs.md` and `docs/ROADMAP.md`; audit cross-references

**Files:**
- Delete: `docs/TODOs.md`
- Delete: `docs/ROADMAP.md`
- Possibly modify: any file that links to either.

- [ ] **Step 1: Find references to the two files across the repo**

Run: `git grep -nE 'TODOs\.md|ROADMAP\.md' -- 'docs/**' '*.md'`
Expected: a list of references. Typical hits: the previous spec/plan (handled in Task 6), possibly `README.md` or `docs/ARCHITECTURE.md`.

- [ ] **Step 2: Fix or drop references in files that are NOT the superseded spec/plan**

For each non-spec/plan reference found in Step 1, edit the file to remove or rewrite the link. If the reference is informational ("see TODOs.md"), replace with "see GitHub Issues (`gh issue list`)". If it's a structural pointer in the architecture doc, drop the bullet.

If Step 1 found no hits outside the superseded spec/plan, skip this step.

- [ ] **Step 3: Delete the two files**

```bash
git rm docs/TODOs.md docs/ROADMAP.md
```

Expected: both files are staged for deletion.

- [ ] **Step 4: Commit**

```bash
git commit -m "docs: remove TODOs.md/ROADMAP.md (migrated to GitHub Issues)"
```

---

### Task 6: Mark previous spec and plan as superseded

**Files:**
- Modify: `docs/superpowers/specs/2026-05-22/2026-05-22-todos-roadmap-workflow-design.md`
- Modify: `docs/superpowers/plans/2026-05-22/2026-05-22-todos-roadmap-workflow.md`

- [ ] **Step 1: Prepend a Superseded banner to the previous spec**

At the very top of `docs/superpowers/specs/2026-05-22/2026-05-22-todos-roadmap-workflow-design.md` (before the existing `# TODOs Inbox + ROADMAP Workflow` heading) insert these three lines and a blank line:

```markdown
> **Superseded by** [2026-05-22-github-issues-roadmap-design.md](2026-05-22-github-issues-roadmap-design.md).
> The TODOs.md + ROADMAP.md workflow has been retired in favor of GitHub
> Issues + a Projects v2 board. This document is kept for history.
```

- [ ] **Step 2: Prepend a Superseded banner to the previous plan**

At the very top of `docs/superpowers/plans/2026-05-22/2026-05-22-todos-roadmap-workflow.md` (before the existing `# TODOs Inbox + ROADMAP Workflow — Implementation Plan` heading) insert these three lines and a blank line:

```markdown
> **Superseded by** [2026-05-22-github-issues-roadmap.md](2026-05-22-github-issues-roadmap.md).
> The TODOs.md + ROADMAP.md migration plan has been retired in favor of
> GitHub Issues + a Projects v2 board. This plan is kept for history.
```

- [ ] **Step 3: Verify both banners are in place**

Run: `head -3 docs/superpowers/specs/2026-05-22/2026-05-22-todos-roadmap-workflow-design.md docs/superpowers/plans/2026-05-22/2026-05-22-todos-roadmap-workflow.md`
Expected: each file's first non-blank line starts with `> **Superseded by**`.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-05-22/2026-05-22-todos-roadmap-workflow-design.md \
        docs/superpowers/plans/2026-05-22/2026-05-22-todos-roadmap-workflow.md
git commit -m "docs: mark TODOs/ROADMAP workflow spec & plan as superseded"
```

---

### Task 7: Set up the `Roadmap` Project board (manual web step + verification)

**Files:** none (GitHub state only).

Projects v2 Status field options can be edited via the GraphQL API, but the friction-to-value ratio is bad for a one-time setup. This task is manual via github.com — explicitly called out in the plan so the executor doesn't get stuck.

- [ ] **Step 1: Create the project (CLI)**

Run: `gh project create --owner LazyHat --title Roadmap`
Expected: prints the URL of the new project, e.g. `https://github.com/users/LazyHat/projects/N`. Keep `N`.

- [ ] **Step 2: Open the project in the browser**

Run: `gh project view N --owner LazyHat --web`
Expected: browser opens to the project board.

- [ ] **Step 3: Edit the built-in `Status` field options (manual, in the browser)**

Default options are `Todo`, `In Progress`, `Done`. Change them to:

- `Inbox`
- `Backlog`
- `Next`
- `Now`
- `Done`
- `Dropped`

Rename existing options where possible; add the missing ones via the field options menu.

- [ ] **Step 4: Add all 10 issues to the project**

Get issue URLs:

```bash
gh issue list --state all --limit 20 --json number,url | jq -r '.[].url'
```

For each URL, add it to the project:

```bash
gh project item-add N --owner LazyHat --url <ISSUE_URL>
```

Expected: each command prints the new item ID.

- [ ] **Step 5: Set the column for each item (manual, in the browser)**

Move items to columns according to the spec:

- `Inbox`: issues #1, #2, #3, #4, #5, #6, #7.
- `Backlog`: issues #9 (clipboard) and #10 (CKL review).
- `Done`: issue #8 (Workbench). It is already closed; the column should auto-set, but verify.

If preferred, the same can be done via `gh project item-edit` calls, but for six items the web UI is faster.

- [ ] **Step 6: Sanity check**

Run: `gh project item-list N --owner LazyHat --limit 20`
Expected: 10 items listed.

No commit — Project board is GitHub state.

---

## Self-Review Notes

- **Spec coverage:**
  - Tooling (MCP server + gh fallback) → Tasks 1, 2.
  - Labels (theme, priority, kind, status) → Task 3.
  - Migration of 10 issues with correct labels and close-state → Task 4.
  - Repo-side cleanup (delete files, audit refs) → Task 5.
  - Superseded banners on previous spec/plan → Task 6.
  - Status board (Inbox/Backlog/Next/Now/Done/Dropped) → Task 7.
- **Placeholders:** none. Every command is concrete. Step 2 in Task 5 is conditional on what `git grep` finds, which is the right level of specificity for a one-shot audit.
- **Type / name consistency:** label names match exactly between Task 3 and Task 4. Status option names match between the spec and Task 7. Issue ordering (1–10) matches the spec table.
- **Execution consistency:**
  - Task 1 deliberately does NOT require a VSCodium reload mid-plan; everything else uses `gh`.
  - Task 2 may require user interaction (browser auth flow) — explicitly noted.
  - Task 7 has explicit manual steps; the executor knows not to try to script the Status field edit.
  - The plan does not assume the MCP server is functional during execution; that is a Phase 2 benefit after a window reload.

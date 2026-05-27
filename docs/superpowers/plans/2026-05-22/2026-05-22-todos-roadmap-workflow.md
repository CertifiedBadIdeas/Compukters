# TODOs Inbox + ROADMAP Workflow — Implementation Plan

> **Superseded by** [2026-05-22-github-issues-roadmap.md](2026-05-22-github-issues-roadmap.md).
> The TODOs.md + ROADMAP.md workflow has been retired in favor of GitHub
> Issues + a Projects v2 board. This plan is kept for history.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a two-file roadmap workflow — `docs/TODOs.md` as an idea inbox and `docs/ROADMAP.md` as a curated status table — and migrate the existing inbox content into the new format.

**Architecture:** Pure documentation change. No code, no tests, no build impact. Two markdown files at `docs/`; the existing `docs/TODOs.md` is rewritten in place into anchored sections, and `docs/ROADMAP.md` is created from scratch with a legend and an initial population derived from the existing inbox content.

**Tech Stack:** Markdown only. No tooling.

**Spec:** [docs/superpowers/specs/2026-05-22/2026-05-22-todos-roadmap-workflow-design.md](../../specs/2026-05-22/2026-05-22-todos-roadmap-workflow-design.md)

---

## File Structure

- Modify: `docs/TODOs.md` — restructure free-form content into `## YYYY-MM-DD — name` sections; add `→ ROADMAP: R-NNN` lines under sections that are promoted in Task 3.
- Create: `docs/ROADMAP.md` — new file with legend block and the curated table.

No other files are touched. No code, no scripts.

---

### Task 1: Create `docs/ROADMAP.md` with legend and empty table

**Files:**
- Create: `docs/ROADMAP.md`

- [ ] **Step 1: Create the file with the full legend and an empty table skeleton**

Write the following content to `docs/ROADMAP.md`:

````markdown
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
````

- [ ] **Step 2: Verify the file was created and renders as valid markdown**

Run: `head -20 docs/ROADMAP.md`
Expected: prints the title, legend header, and start of the statuses list with no errors.

- [ ] **Step 3: Commit**

```bash
git add docs/ROADMAP.md
git commit -m "docs: add ROADMAP.md skeleton with legend and empty table"
```

---

### Task 2: Restructure `docs/TODOs.md` into anchored sections

**Files:**
- Modify: `docs/TODOs.md`

The current file is 329 lines of mixed free-form content. The goal of this task is purely structural: split it into `## YYYY-MM-DD — name` sections without rewording any content. No ROADMAP cross-references are added yet — that happens in Task 3.

The migration date `2026-05-22` is used for all sections whose original date is unknown. Where the existing text already references a specific date (e.g. workbench specs dated 2026-04-16 / 2026-04-30), use that earlier date.

- [ ] **Step 1: Rewrite `docs/TODOs.md` with the new top-level header and sectioned numbered ideas**

Replace the entire current content of `docs/TODOs.md` with the structure below. The intro paragraph and section headings are new; the section *bodies* are copied verbatim from the existing file. Long sections (CKL libraries review, Workbench IDE deferrals, comparison tables) keep their existing inner structure (`###` subheadings, bullet lists, tables) — only the top-level `## YYYY-MM-DD — name` wrapper is added.

Concretely, the file becomes:

````markdown
# TODOs (inbox)

Free-form dump of ideas and wishes for the project. Structure is loose on
purpose — the goal is to lose nothing.

Conventions:

- Each idea is a level-2 section: `## YYYY-MM-DD — short name`.
- When an idea is promoted into [ROADMAP.md](ROADMAP.md), add a line right
  under its heading: `→ ROADMAP: R-NNN`.
- Implemented or rejected ideas stay here; mark them inline with
  **Реализовано.** / **Отклонено.** and a one-line reason.
- Append new sections at the end. Do not reorder existing sections.

## 2026-05-22 — Глубокая интеграция с модом Create

Глубокая интеграция с модом Create.

## 2026-05-22 — Capability-проверка программы по imports

На основе import можно определять, можно ли вообще запустить программу на этом
компьютере, возможно сделать какие-то запросы capability, peripheral, по
imports по сути можно однозначно понять от чего программа зависит. Зависеть
она может от инвентаря например, который есть только у черепашки.

## 2026-05-22 — Черепашка со своим набором builtins

Сделать черепашку со своим набором builtins — У черепашки появляется Fuel и
Inventory. Можно сделать флаг конечно, черепашка это или нет, но даже не знаю
надо ли.

## 2026-05-22 — Расширение системы import (peripheral, скрипты, dependency-проверка)

Сделать расширить систему import, добавив peripheral, возможно какой-то
обозреватель import чтобы можно было узнать вообще какие peripheral вообще
доступны. Так же сделать систему import файлов скриптов, чтобы можно было на
них ссылаться, и тогда по сути проверка файла на возможность запуска будет ещё
дополнена рекурсивной проверкой всех dependency файлов.

## 2026-05-22 — Внешняя и внутренняя сеть между компьютерами

Внешняя и внутренняя сеть между компьютерами, внутренняя сеть по сути своей
должна представлять систему broadcast channels, с id канала (не компьютера,
компьютеры не должны иметь возможность сообщатся просто напрямую).

## 2026-05-22 — git cli клиент

git cli клиент.

## 2026-05-22 — Связь между компьютерами: модемы и вышка

Сделать связь между компьютерами.

1. Сделать модемы — радиомодули или лазерные (если в космос например хахахаха).
2. Для модемов должна быть вышка, которая обслуживает эти модемы и обеспечивает
   связь между компьютерами.

## 2026-04-16 — Workbench как отдельный Authoring Station

~~Сделать workbench (компьютерный стол) где можно будет программировать
компьютеры.~~ **Реализовано.** Workbench выделен в отдельный Authoring Station,
описан в:

- `docs/superpowers/specs/2026-04-16/2026-04-16-workbench-separate-entity-design.md` —
  изначальный дизайн отдельной сущности.
- `docs/superpowers/specs/2026-04-30/2026-04-30-device-authoring-domain-model-design.md` —
  двухкатегорийная модель (Runtime Devices vs Authoring Stations).
- `docs/ARCHITECTURE.md` (раздел Domain Model) — формальное закрепление в
  архитектуре.

## 2026-05-22 — CKL: библиотеки и общие утилиты

<!-- BODY: copy verbatim from the existing "## CKL: библиотеки и общие
утилиты" section in the old TODOs.md, INCLUDING all its `###` subheadings
("Что уже хорошо для библиотек", "Главные проблемы", "Сравнение с C++ /
Kotlin / Rust", and any others present), down to (but not including) the
existing "## Workbench IDE (отложено после переписывания UI на DSL)"
heading. Do not rewrite or summarize — preserve content character-for-
character. -->

## 2026-05-22 — Workbench IDE: clipboard и выделение (отложено после переписывания UI на DSL)

<!-- BODY: copy verbatim from the existing "## Workbench IDE (отложено после
переписывания UI на DSL)" section in the old TODOs.md, INCLUDING the
"Clipboard API для CodeEditor" and "Shift+Arrow и выделение текста" bullets,
plus the immediately following paragraph "Я посмотрел документацию, модель
языка, фронтенд и IDE-тесты…". Do not rewrite — preserve content character-
for-character. -->
````

Implementation note for the executor: read the current `docs/TODOs.md` once before editing, then write a single replacement preserving the long verbatim bodies as instructed in the HTML comments. The HTML comments themselves are NOT kept in the final file — they are guidance for this task only. Replace each `<!-- BODY: … -->` block with the verbatim text from the corresponding source section.

- [ ] **Step 2: Verify the new structure**

Run: `grep -nE '^## ' docs/TODOs.md`
Expected: prints exactly 10 level-2 headings, in this order:

1. `## 2026-05-22 — Глубокая интеграция с модом Create`
2. `## 2026-05-22 — Capability-проверка программы по imports`
3. `## 2026-05-22 — Черепашка со своим набором builtins`
4. `## 2026-05-22 — Расширение системы import (peripheral, скрипты, dependency-проверка)`
5. `## 2026-05-22 — Внешняя и внутренняя сеть между компьютерами`
6. `## 2026-05-22 — git cli клиент`
7. `## 2026-05-22 — Связь между компьютерами: модемы и вышка`
8. `## 2026-04-16 — Workbench как отдельный Authoring Station`
9. `## 2026-05-22 — CKL: библиотеки и общие утилиты`
10. `## 2026-05-22 — Workbench IDE: clipboard и выделение (отложено после переписывания UI на DSL)`

- [ ] **Step 3: Spot-check that long bodies are preserved verbatim**

Run: `grep -c 'Сравнение с C++ / Kotlin / Rust' docs/TODOs.md`
Expected: `1` (the comparison subsection from the CKL review survived).

Run: `grep -c 'Shift+Arrow и выделение текста' docs/TODOs.md`
Expected: `1`.

Run: `grep -c '<!-- BODY' docs/TODOs.md`
Expected: `0` (guidance comments must not be left in the final file).

- [ ] **Step 4: Commit**

```bash
git add docs/TODOs.md
git commit -m "docs: restructure TODOs.md into anchored idea sections"
```

---

### Task 3: Populate `docs/ROADMAP.md` with the initial promoted rows

**Files:**
- Modify: `docs/ROADMAP.md`
- Modify: `docs/TODOs.md` (only the `→ ROADMAP:` lines)

Promote exactly the two items where the current inbox already carries an explicit status signal:

- Workbench separation — already marked **Реализовано.** → `Done`.
- Workbench IDE clipboard/selection — already marked "отложено после переписывания UI на DSL" → `Backlog`, `Low`.

The other numbered ideas (Create integration, capability check, turtle builtins, import system, networking, git cli, modems) stay in the inbox unpromoted; the user will promote them deliberately later. The large CKL libraries review also stays unpromoted as a single section.

- [ ] **Step 1: Append the two initial rows to the table in `docs/ROADMAP.md`**

Edit `docs/ROADMAP.md`, locating the line that ends the table header:

```markdown
|----|------|-------|--------|------|----------------|-------------|
```

Append immediately after it the following two rows (and only these two rows):

```markdown
| R-001 | Workbench as separate Authoring Station | IDE | Done | — | [TODOs.md](TODOs.md#2026-04-16--workbench-как-отдельный-authoring-station) | [spec](../../specs/2026-04-16/2026-04-16-workbench-separate-entity-design.md) |
| R-002 | Workbench IDE: clipboard & selection (deferred until UI DSL) | IDE | Backlog | Low | [TODOs.md](TODOs.md#2026-05-22--workbench-ide-clipboard-и-выделение-отложено-после-переписывания-ui-на-dsl) | — |
```

Implementation note: GitHub-flavored markdown anchor generation lowercases the heading, replaces spaces with `-`, drops most punctuation, and keeps Cyrillic characters as-is. The anchors above were constructed by that rule. If a renderer disagrees with a specific anchor at review time, fix the anchor in place — the source heading is the authoritative target.

- [ ] **Step 2: Add `→ ROADMAP:` back-links in `docs/TODOs.md`**

In `docs/TODOs.md`, immediately under the heading
`## 2026-04-16 — Workbench как отдельный Authoring Station`,
insert a blank line and then the line:

```markdown
→ ROADMAP: R-001
```

Immediately under the heading
`## 2026-05-22 — Workbench IDE: clipboard и выделение (отложено после переписывания UI на DSL)`,
insert a blank line and then the line:

```markdown
→ ROADMAP: R-002
```

- [ ] **Step 3: Verify both files are consistent**

Run: `grep -nE 'R-00[12]' docs/ROADMAP.md docs/TODOs.md`
Expected: four matches total — two in `docs/ROADMAP.md` (the two new table rows) and two in `docs/TODOs.md` (the two `→ ROADMAP:` lines).

Run: `grep -cE '^\| R-[0-9]{3} ' docs/ROADMAP.md`
Expected: `2`.

- [ ] **Step 4: Commit**

```bash
git add docs/ROADMAP.md docs/TODOs.md
git commit -m "docs: seed ROADMAP with workbench Done and clipboard Backlog rows"
```

---

## Self-Review Notes

- **Spec coverage:**
  - Artifacts (TODOs.md inbox, ROADMAP.md table) → Tasks 1 and 2.
  - Inbox format (anchored sections, `→ ROADMAP:` line, status markers preserved) → Task 2 + Task 3 Step 2.
  - Roadmap legend (statuses, priorities, themes) → Task 1 Step 1.
  - Roadmap table schema (ID, Idea, Theme, Status, Prio, Source, Spec/Plan) → Task 1 Step 1 (header row) + Task 3 Step 1 (data rows).
  - Workflow (Capture / Promote / Status / Spec link) → Task 1 Step 1 (Workflow section in the file).
  - Migration of existing content (Workbench Done, Clipboard/Selection Backlog Low, CKL review left as single section, other numbered ideas unpromoted) → Tasks 2 and 3.
- **Placeholders:** none — every step has the exact content to write or the exact command to run, except for the two HTML-comment guidance markers in Task 2 Step 1 which are explicitly required to be expanded with verbatim text from the existing file before saving.
- **Type consistency:** IDs `R-001` and `R-002` are referenced consistently across Tasks 1, 2, and 3.
- **Execution consistency:** all commands use only `git`, `grep`, and `head`, which are standard on the target machine. No build, test, or compile step is required.

# Kraft16 Documentation Language Alignment Implementation Plan

> Issue: [#147](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/147)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the strategic language docs with the accepted Kraft16/K16 rename while keeping the Rux language named Rux.

**Architecture:** This is a docs-only slice. It updates strategy/audit wording that currently says machine names remain undecided, but does not change active ABI specs or binary magic values.

**Tech Stack:** Markdown documentation, `rg`, `git diff --check`.

---

### Task 1: Update Strategy And Audit Docs

**Files:**
- Modify: `docs/toolchains/rux-language-retirement-audit.md`
- Modify: `docs/toolchains/k16-language-strategy.md`

- [x] **Step 1: Replace stale machine-name decision text**

In `docs/toolchains/rux-language-retirement-audit.md`, replace the paragraph that says machine/artifact names remain current until a later decision with text that points to #147 and explains that the language remains Rux.

In `docs/toolchains/k16-language-strategy.md`, replace the equivalent paragraph under "What Is Being Retired" with the same boundary.

- [x] **Step 2: Update keep/current wording**

In `docs/toolchains/rux-language-retirement-audit.md`, update "Keep For Now" so it says Kraft16/K16 is the accepted future name for the machine/tooling path, while current ABI documents may still describe existing Rux-named formats until each ABI migration lands.

- [x] **Step 3: Verify text**

Run:

```bash
rg -n "machine/artifact names by itself|separate compatibility decision|remain the current CPU" docs/toolchains/rux-language-retirement-audit.md docs/toolchains/k16-language-strategy.md
```

Expected: no output.

- [x] **Step 4: Verify whitespace**

Run:

```bash
git diff --check
```

Expected: no output.

- [x] **Step 5: Commit**

Run:

```bash
git add docs/toolchains/rux-language-retirement-audit.md docs/toolchains/k16-language-strategy.md docs/superpowers/plans/2026-05-31/2026-05-31-issue-147-kraft16-doc-language-alignment.md
git commit -m "docs(toolchains): align Kraft16 naming strategy"
```

---
layout: default
title: Contributor guides
description: Build, understand, test and profile the Compukters implementation.
section: contributors
permalink: /CONTRIBUTORS/
---

# Contributor guides

Start by building the checkout and selecting the Minecraft target you want to edit. Read the architecture before
changing a compiler, native runtime or game boundary, then choose verification that exercises the affected behavior.

{% include audience-guide.html audience='contributors' %}

## Maintain the documentation

Player guides explain how to use the mod; developer topics own exact Guest language/library contracts and their
evidence; architecture pages own implementation boundaries. Dated performance reports describe their recorded
revisions and are not current support matrices. Update the owning page with a behavior change instead of copying
its contract into several guides.

Wiki pages use `layout: default`, a descriptive `title`, and the appropriate `section` (`players`, `developers` or
`contributors`). Preserve existing `permalink` values so published links remain stable. Add discoverable guides to
`docs/_data/navigation.yml` and detailed reports to the performance index. Use relative Markdown links for source
pages and Liquid `relative_url` links for site routes; avoid hardcoding the deployed site address for internal links.
Keep code examples complete and distinguish development features from published releases through the changelog.

The [Pages workflow](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/.github/workflows/pages.yml) builds
release and nightly documentation from separate revisions, including generated Guest API and IDE palette assets.
Run `python3 -m unittest discover -s docs/_tools -p 'test_versioned_docs.py'` after changing version composition.
Check local link targets and navigation against the source pages; these source checks and versioning tests do not
replace a Jekyll rendering check or establish that external sites are available.

## Repository workflow

Follow the repository's [working guidelines](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/AGENTS.md).
Use [GitHub issues](https://github.com/CertifiedBadIdeas/Compukters/issues) for reproducible bugs and architectural work.
Include the relevant verification commands and results with a change; visible UI changes benefit from screenshots.

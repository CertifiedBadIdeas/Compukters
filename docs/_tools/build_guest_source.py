#!/usr/bin/env python3
"""Build browsable Jekyll pages from the canonical Guest Kotlin platform sources."""

from __future__ import annotations

import argparse
import html
from pathlib import Path
import shutil
import tomllib


REPOSITORY = Path(__file__).resolve().parents[2]
PLATFORM = REPOSITORY / "modules/common/guest-platform/src/platform"
OUTPUT = REPOSITORY / "docs/guest-source"
SOURCE_URL = "https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/guest-platform/src/platform/"


def page_url(path: Path) -> str:
    return f"/guest-source/{path.as_posix()}/"


def site_link(url: str, label: str) -> str:
    return f'<a href="{{{{ \'{url}\' | relative_url }}}}">{html.escape(label)}</a>'


def source_text(value: str) -> str:
    # Liquid processes source pages before Markdown/HTML. Encode braces so Guest code
    # containing template-like text cannot be interpreted as Jekyll directives.
    return html.escape(value, quote=False).replace("{", "&#123;").replace("}", "&#125;")


def module_sources() -> list[tuple[str, list[Path]]]:
    manifest = tomllib.loads((PLATFORM / "modules.toml").read_text(encoding="utf-8"))
    modules: list[tuple[str, list[Path]]] = []
    seen: set[Path] = set()
    for module in manifest["module"]:
        paths = sorted({path.relative_to(PLATFORM) for pattern in module["sources"] for path in PLATFORM.glob(pattern)})
        if not paths:
            raise ValueError(f"Guest module {module['id']} has no sources")
        if any(path in seen for path in paths):
            raise ValueError(f"Guest module {module['id']} repeats a source from another module")
        seen.update(paths)
        modules.append((module["id"], paths))
    all_sources = {path.relative_to(PLATFORM) for path in PLATFORM.rglob("*.kt")}
    if seen != all_sources:
        raise ValueError(f"Guest source catalog differs from modules.toml: {sorted(all_sources ^ seen)}")
    return modules


def render_index(modules: list[tuple[str, list[Path]]]) -> str:
    count = sum(len(paths) for _, paths in modules)
    parts = [
        "---\nlayout: default\ntitle: Guest Kotlin source\n"
        "description: Browse the Kotlin declarations bundled with Compukters.\n"
        "permalink: /guest-source/\n---\n",
        "<h1>Guest Kotlin source</h1>",
        f"<p class=\"hero-copy\">Browse all {count} Kotlin source files bundled in the Compukters Guest platform. "
        "These are the declarations and library implementations used by Guest programs.</p>",
        "<p>The <a href=\"{{ '/KOTLIN-SUPPORT/' | relative_url }}\">language matrix</a> and "
        "<a href=\"{{ '/STDLIB-SUPPORT/' | relative_url }}\">stdlib matrix</a> explain which declarations "
        "are executable. The source catalog includes structural declarations used by the compiler.</p>",
        "<nav class=\"source-module-nav\" aria-label=\"Guest modules\">",
    ]
    for index, (module_id, _) in enumerate(modules):
        parts.append(f'<a href="#module-{index}">{html.escape(module_id)}</a>')
    parts.append("</nav>")
    for index, (module_id, paths) in enumerate(modules):
        parts.append(f'<section class="source-module" id="module-{index}"><h2>{html.escape(module_id)}</h2><ul>')
        for path in paths:
            parts.append(f'<li>{site_link(page_url(path), path.as_posix())}</li>')
        parts.append("</ul></section>")
    return "\n".join(parts) + "\n"


def render_source(path: Path, module_id: str) -> str:
    lines = (PLATFORM / path).read_text(encoding="utf-8").splitlines()
    source_lines = [
        f'<span class="source-line" id="L{number}"><a class="source-line-number" '
        f'href="#L{number}" aria-label="Line {number}">{number}</a>'
        f'<span class="source-line-text">{source_text(line)}</span></span>'
        for number, line in enumerate(lines, start=1)
    ]
    return "\n".join(
        [
            "---",
            "layout: default",
            f"title: {path.name} · Guest Kotlin source",
            f"description: Guest Kotlin source for {path.as_posix()}.",
            f"permalink: {page_url(path)}",
            "---",
            f'<nav class="source-breadcrumb">{site_link("/guest-source/", "Guest Kotlin source")} / '
            f"{html.escape(module_id)}</nav>",
            f"<h1>{html.escape(path.name)}</h1>",
            f'<p class="source-path"><code>{html.escape(path.as_posix())}</code></p>',
            f'<p><a href="{SOURCE_URL}{path.as_posix()}">View this file on GitHub</a></p>',
            '<pre class="source-code" aria-label="Kotlin source"><code>',
            *source_lines,
            "</code></pre>",
            "",
        ]
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="compare existing pages with canonical sources")
    args = parser.parse_args()

    modules = module_sources()
    expected = {Path("index.md"): render_index(modules)}
    for module_id, paths in modules:
        for path in paths:
            expected[path.with_suffix(path.suffix + ".html")] = render_source(path, module_id)

    actual = {path.relative_to(OUTPUT) for path in OUTPUT.rglob("*") if path.is_file()} if OUTPUT.exists() else set()
    if args.check:
        mismatched = [path for path, content in expected.items() if not (OUTPUT / path).exists() or (OUTPUT / path).read_text(encoding="utf-8") != content]
        if actual != expected.keys() or mismatched:
            raise SystemExit(f"Guest source pages are stale: missing/changed={mismatched}, extra={sorted(actual - expected.keys())}")
        print(f"Verified {len(expected) - 1} Guest Kotlin source pages")
        return

    if OUTPUT.exists():
        shutil.rmtree(OUTPUT)
    for path, content in expected.items():
        target = OUTPUT / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
    print(f"Generated {len(expected) - 1} Guest Kotlin source pages in {OUTPUT.relative_to(REPOSITORY)}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Build a declaration-oriented Guest Kotlin API reference from the platform metadata export."""

from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import html
import json
from pathlib import Path
import re
import shutil


REPOSITORY = Path(__file__).resolve().parents[2]
INDEX = REPOSITORY / "modules/common/platform-k2/build/generated/guest-api/declarations.json"
OUTPUT = REPOSITORY / "docs/guest-api"
SOURCE_URL = "https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/guest-platform/src/platform/"


def link(url: str, label: str) -> str:
    return f'<a href="{{{{ \'{url}\' | relative_url }}}}">{html.escape(label)}</a>'


def slug(value: str) -> str:
    words = re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", value)
    return re.sub(r"[^a-z0-9]+", "-", words.lower()).strip("-") or "declaration"


def group_key(entry: dict) -> tuple[str, str, str, str]:
    kind = entry["kind"]
    category = "type" if kind in {"class", "value-class", "interface", "object", "enum", "annotation", "typealias", "companion"} else kind
    if kind == "constructor":
        category = "constructor"
    return entry["package"], entry["owner"], entry["name"], category


def group_url(key: tuple[str, str, str, str]) -> str:
    package, owner, name, category = key
    identifier = "|".join(key)
    digest = hashlib.sha1(identifier.encode("utf-8")).hexdigest()[:8]
    readable = slug("-".join(part for part in (owner, name) if part))
    return f"/guest-api/{package}/{readable}-{category}-{digest}/"


def package_url(package: str) -> str:
    return f"/guest-api/{package}/"


def page(title: str, permalink: str, body: list[str]) -> str:
    return "\n".join(
        [
            "---",
            "layout: default",
            f"title: {title}",
            f"permalink: {permalink}",
            "---",
            *body,
            "",
        ]
    )


def description(text: str) -> str:
    if not text:
        return ""
    paragraphs = re.split(r"\n\s*\n", text)
    return "".join(f'<p>{html.escape(paragraph).replace(chr(10), " ")}</p>' for paragraph in paragraphs)


def source_link(entry: dict) -> str:
    path = entry["source"]
    line = entry["line"]
    local = f"/guest-source/{path}/#L{line}"
    remote = f"{SOURCE_URL}{path}#L{line}"
    return f'{link(local, "Source line " + str(line))} · <a href="{remote}">GitHub</a>'


def render_group(key: tuple[str, str, str, str], entries: list[dict], groups: dict) -> str:
    package, owner, name, category = key
    symbol = ".".join(part for part in (package, owner, name) if part)
    body = [
        f'<nav class="source-breadcrumb">{link("/guest-api/", "Guest API")} / '
        f'{link(package_url(package), package)}</nav>',
        f"<h1>{html.escape(name if name != '<init>' else 'Constructor')}</h1>",
        f'<p class="api-qualified-name"><code>{html.escape(symbol)}</code></p>',
    ]
    for entry in entries:
        body.extend(
            [
                f'<section class="api-declaration"><pre><code>{html.escape(entry["signature"])}</code></pre>',
                description(entry["description"]),
                f'<p class="api-source-link">{source_link(entry)}</p></section>',
            ]
        )
    if category == "type":
        members = sorted((candidate for candidate in groups if candidate[0] == package and candidate[1] == ".".join(part for part in (owner, name) if part)), key=lambda item: (item[3], item[2]))
        if members:
            body.append("<h2>Members</h2><ul class=\"api-member-list\">")
            for member in members:
                body.append(f'<li>{link(group_url(member), member[2] if member[2] != "<init>" else "Constructor")}</li>')
            body.append("</ul>")
    return page(f"{name} · Guest API", group_url(key), body)


def render_package(package: str, keys: list[tuple[str, str, str, str]], groups: dict) -> str:
    body = [
        f'<nav class="source-breadcrumb">{link("/guest-api/", "Guest API")}</nav>',
        f"<h1>{html.escape(package)}</h1>",
        "<p>Public declarations in this Guest package. Check the support matrices for executable behavior.</p>",
    ]
    top_level = [key for key in keys if not key[1]]
    for label, categories in (("Types", {"type"}), ("Functions", {"fun"}), ("Properties", {"val", "var"})):
        selected = sorted((key for key in top_level if key[3] in categories), key=lambda key: key[2].lower())
        if not selected:
            continue
        body.append(f"<h2>{label}</h2><div class=\"api-card-list\">")
        for key in selected:
            entry = groups[key][0]
            body.extend(
                [
                    '<section class="api-card">',
                    f'<h3>{link(group_url(key), key[2])}</h3>',
                    f'<code>{html.escape(entry["signature"])}</code>',
                    description(entry["description"]),
                    '</section>',
                ]
            )
        body.append("</div>")
    return page(f"{package} · Guest API", package_url(package), body)


def render_index(packages: list[str], groups: dict) -> str:
    body = [
        "<h1>Guest Kotlin API</h1>",
        '<p class="hero-copy">Browse public Guest declarations by package, type, or function. '
        "Signatures come from the canonical Guest platform sources.</p>",
        '<p>This reference includes declarations that are structural or available only to the compiler. '
        "Check the <a href=\"{{ '/STDLIB-SUPPORT/' | relative_url }}\">stdlib support matrix</a> and "
        "<a href=\"{{ '/KOTLIN-SUPPORT/' | relative_url }}\">language support matrix</a> before using an API. "
        "The <a href=\"{{ '/guest-source/' | relative_url }}\">source browser</a> includes internal files too.</p>",
        '<label for="api-search">Search Guest API</label>',
        '<input id="api-search" class="api-search" type="search" placeholder="Type, function, or package" '
        'data-api-root="{{ "/guest-api/" | relative_url }}" autocomplete="off">',
        '<div id="api-search-results" class="api-search-results" hidden></div>',
        "<h2>Packages</h2><div class=\"api-package-grid\">",
    ]
    for package in packages:
        count = sum(1 for key in groups if key[0] == package and not key[1])
        body.append(f'<div class="api-card"><h3>{link(package_url(package), package)}</h3><p>{count} top-level declarations</p></div>')
    body.extend(
        [
            "</div>",
            '<script src="{{ "/assets/js/guest-api-search.js" | relative_url }}" defer></script>',
        ]
    )
    return page("Guest Kotlin API", "/guest-api/", body)


def expected_pages(entries: list[dict]) -> dict[Path, str]:
    groups: dict[tuple[str, str, str, str], list[dict]] = defaultdict(list)
    for entry in entries:
        groups[group_key(entry)].append(entry)
    packages = sorted({entry["package"] for entry in entries})
    expected = {Path("index.md"): render_index(packages, groups)}
    for package in packages:
        keys = [key for key in groups if key[0] == package]
        expected[Path(package) / "index.md"] = render_package(package, keys, groups)
    for key, declarations in groups.items():
        expected[Path(key[0]) / group_url(key).rstrip("/").split("/")[-1] / "index.md"] = render_group(key, declarations, groups)
    search = [
        {"name": key[2] if key[2] != "<init>" else "Constructor", "symbol": ".".join(part for part in key[:3] if part), "url": group_url(key), "signature": groups[key][0]["signature"]}
        for key in sorted(groups)
    ]
    expected[Path("search.json")] = json.dumps(search, ensure_ascii=False, separators=(",", ":")) + "\n"
    return expected


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="compare generated pages with the metadata export")
    args = parser.parse_args()
    entries = json.loads(INDEX.read_text(encoding="utf-8"))
    if not entries:
        raise SystemExit("Guest API export contains no declarations")
    expected = expected_pages(entries)
    actual = {path.relative_to(OUTPUT) for path in OUTPUT.rglob("*") if path.is_file()} if OUTPUT.exists() else set()
    if args.check:
        changed = [path for path, content in expected.items() if not (OUTPUT / path).exists() or (OUTPUT / path).read_text(encoding="utf-8") != content]
        if changed or actual != expected.keys():
            raise SystemExit(f"Guest API pages are stale: changed={changed}, extra={sorted(actual - expected.keys())}")
        print(f"Verified {len(expected) - 2} Guest API package and declaration pages")
        return
    if OUTPUT.exists():
        shutil.rmtree(OUTPUT)
    for path, content in expected.items():
        target = OUTPUT / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
    print(f"Generated {len(expected) - 2} Guest API package and declaration pages")


if __name__ == "__main__":
    main()

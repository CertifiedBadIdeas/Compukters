#!/usr/bin/env python3
"""Stage Dokka's Guest Kotlin and Create addon API reference for Jekyll."""

from __future__ import annotations

import argparse
from pathlib import Path
import shutil


REPOSITORY = Path(__file__).resolve().parents[2]
SOURCE = REPOSITORY / "docs/api-build/build/dokka/html"
OUTPUT = REPOSITORY / "docs/guest-api"


def files_under(root: Path) -> dict[Path, Path]:
    return {path.relative_to(root): path for path in root.rglob("*") if path.is_file()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="compare staged files with Dokka output")
    args = parser.parse_args()

    source_files = files_under(SOURCE)
    required_pages = (
        "index.html",
        "guest/index.html",
        "guest/kotlin/index.html",
        "create/index.html",
        "create/create.boiler/index.html",
        "create/create.kinetics/index.html",
        "create/create.logistics/index.html",
    )
    missing_pages = [page for page in required_pages if Path(page) not in source_files]
    if missing_pages:
        raise SystemExit(
            f"Dokka output is missing API pages {missing_pages}; "
            "run ./gradlew -p docs/api-build dokkaGeneratePublicationHtml"
        )

    if args.check:
        staged_files = files_under(OUTPUT)
        changed = [
            path for path, source in source_files.items()
            if path not in staged_files or source.read_bytes() != staged_files[path].read_bytes()
        ]
        extra = staged_files.keys() - source_files.keys()
        if changed or extra:
            raise SystemExit(f"Guest API files are stale: changed={changed[:10]}, extra={sorted(extra)[:10]}")
        print(f"Verified {len(source_files)} Dokka files")
        return

    if OUTPUT.exists():
        shutil.rmtree(OUTPUT)
    shutil.copytree(SOURCE, OUTPUT)
    print(f"Staged {len(source_files)} Dokka files")


if __name__ == "__main__":
    main()

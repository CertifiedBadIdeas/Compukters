#!/usr/bin/env python3
"""Stage Dokka's Guest Kotlin API reference for the Jekyll site."""

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
    if not (SOURCE / "index.html").is_file() or not any(
        path.parts[-2:] == ("kotlin", "index.html") for path in source_files
    ):
        raise SystemExit("Dokka output is missing the Guest Kotlin API; run ./gradlew -p docs/api-build dokkaGeneratePublicationHtml")

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

#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Project the in-game IDE's color roles into Jekyll's syntax palette."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re


REPOSITORY = Path(__file__).resolve().parents[2]
SOURCE = REPOSITORY / "modules/minecraft/shared/common/src/main/kotlin/ru/lazyhat/compukters/impl/ide/IdeColors.kt"
OUTPUT = REPOSITORY / "docs/_data/ide_palette.json"
ROLES = {
    "background": "EDITOR",
    "text": "EDITOR_TEXT",
    "keyword": "KEYWORD",
    "string": "STRING",
    "escape": "STRING_ESCAPE",
    "number": "NUMBER",
    "comment": "COMMENT",
    "type": "TYPE",
    "peripheral_provider": "PERIPHERAL_PROVIDER",
    "type_parameter": "TYPE_PARAMETER",
    "annotation": "ANNOTATION",
    "function": "FUNCTION",
    "property": "PROPERTY",
    "error": "ERROR",
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify the committed palette against the IDE source")
    args = parser.parse_args()
    declarations = dict(re.findall(r"const val (\w+) = 0x([0-9A-Fa-f]{8})\.toInt\(\)", SOURCE.read_text()))
    palette = {"_source": str(SOURCE.relative_to(REPOSITORY))}
    for role, name in ROLES.items():
        value = declarations.get(name)
        if value is None or not value.upper().startswith("FF"):
            raise SystemExit(f"IDE color {name} must be an explicit opaque ARGB constant")
        palette[role] = "#" + value[2:].lower()
    expected = json.dumps(palette, indent=2) + "\n"
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_text() != expected:
            raise SystemExit("IDE syntax palette is stale; run python3 docs/_tools/build_ide_palette.py")
        print(f"Verified {len(ROLES)} IDE syntax color roles")
        return
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(expected)
    print(f"Staged {len(ROLES)} IDE syntax color roles")


if __name__ == "__main__":
    main()

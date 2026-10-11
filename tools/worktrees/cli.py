#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Task checkout creation, diagnostics and safe completion."""

import argparse
from pathlib import Path
import sys

import create
import lifecycle


def main():
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument('--workspace', type=Path, default=Path(__file__).resolve().parents[2])
    arguments, remaining = parser.parse_known_args()
    if remaining and remaining[0] in ('doctor', 'finish'):
        return lifecycle.main(arguments.workspace, remaining)
    return create.main()


if __name__ == '__main__':
    sys.exit(main())

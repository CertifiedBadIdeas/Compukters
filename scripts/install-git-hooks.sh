#!/usr/bin/env sh
set -eu

ROOT="$(git rev-parse --show-toplevel)"
git -C "$ROOT" config core.hooksPath scripts/git-hooks
echo "Configured git hooks path: scripts/git-hooks"

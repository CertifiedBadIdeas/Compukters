#!/bin/sh
# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
set -eu

python3 "$COMPUKTERS_BUILD_ROOT/tools/gradle/cache.py" --root "$COMPUKTERS_BUILD_ROOT" prepare
if flock --shared "$GRADLE_USER_HOME/.compukters-cache.lock" \
    "$COMPUKTERS_BUILD_GRADLE" --no-daemon -Dorg.gradle.vfs.watch=false "$@"; then
    python3 "$COMPUKTERS_BUILD_ROOT/tools/gradle/cache.py" --root "$COMPUKTERS_BUILD_ROOT" finish || \
        printf 'Gradle succeeded; its cache template could not be refreshed.\n' >&2
else
    exit "$?"
fi

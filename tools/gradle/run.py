#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Serialize managed builds across worktrees and bound their internal parallelism."""

import argparse
import fcntl
import os
from pathlib import Path
import subprocess
import sys


def positive_setting(name):
    value = os.environ.get(name, "8")
    if not value.isdecimal() or int(value) < 1:
        raise ValueError(f"{name} must be a positive integer")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not args.command:
        parser.error("a build command is required")
    try:
        workers = positive_setting("COMPUKTERS_GRADLE_WORKERS")
        jobs = positive_setting("COMPUKTERS_RUST_JOBS")
        common = subprocess.check_output(
            ["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
            cwd=args.root, text=True,
        ).strip()
        lock = Path(common).parent / ".toolchain" / "gradle-build.lock"
        lock.parent.mkdir(parents=True, exist_ok=True)
        handle = lock.open("a+b")
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Waiting for the workspace Gradle build slot...", file=sys.stderr, flush=True)
            fcntl.flock(handle, fcntl.LOCK_EX)
        # The build inherits the lock; process exit releases it, including on failure.
        os.set_inheritable(handle.fileno(), True)
        # Explicit command-line limits take precedence over wrapper defaults.
        command = [args.command[0], f"--max-workers={workers}",
                   f"-PcompukterVmBuildJobs={jobs}", *args.command[1:]]
        os.execvp(command[0], command)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Cannot start managed Gradle build: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Create an isolated task checkout, initialize pinned submodules and prepare its cache."""

import argparse
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys


def git(repository, *arguments, capture=True):
    return subprocess.run(
        ["git", "-C", str(repository), *map(str, arguments)], check=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None, text=True,
    ).stdout


def common_directory(repository):
    return Path(git(repository, "rev-parse", "--path-format=absolute", "--git-common-dir").strip())


def main_checkout(repository):
    common = common_directory(repository)
    configured = subprocess.run(["git", "config", "--file", str(common / "config"), "--get", "core.worktree"],
                                capture_output=True, text=True)
    if configured.returncode == 0:
        # Submodules can report their Git metadata directory as the first worktree entry.
        return (common / configured.stdout.strip()).resolve()
    # The first porcelain worktree entry is the main checkout, even when invoked from a linked tree.
    for line in git(repository, "worktree", "list", "--porcelain").splitlines():
        if line.startswith("worktree "):
            return Path(line.removeprefix("worktree "))
    raise RuntimeError(f"No main checkout for {repository}")


def modules(repository):
    if not (repository / ".gitmodules").exists():
        return {}
    result = git(repository, "config", "--file", ".gitmodules", "--get-regexp", r"^submodule\..*\.path$")
    entries = {}
    for line in result.splitlines():
        key, path = line.split(" ", 1)
        url = git(repository, "config", "--file", ".gitmodules", "--get", key.removesuffix("path") + "url").strip()
        entries[path] = url
    return entries


def github_transport_options():
    if not shutil.which("gh"):
        return []
    agent = subprocess.run(["ssh-add", "-l"], capture_output=True) if shutil.which("ssh-add") else None
    if agent is not None and agent.returncode == 0:
        return []
    return ["-c", "url.https://github.com/.insteadOf=git@github.com:",
            "-c", "credential.helper=!gh auth git-credential"]


def initialize_module(checkout, source, path, url):
    options = github_transport_options()
    local = source / path
    revision = git(checkout, "rev-parse", f"HEAD:{path}").strip()
    if local.is_dir():
        try:
            top = Path(git(local, "rev-parse", "--show-toplevel").strip()).resolve()
            if top == local.resolve():
                git(local, "cat-file", "-e", revision + "^{commit}")
                # A command-scoped rewrite reuses local objects, but preserves the recorded URL.
                options += ["-c", "protocol.file.allow=always", "-c",
                            f"url.{common_directory(local).as_uri()}.insteadOf={url}"]
        except subprocess.CalledProcessError:
            pass
    git(checkout, *options, "submodule", "update", "--init", "--recursive", "--", path, capture=False)
    if git(checkout / path, "rev-parse", "HEAD").strip() != revision:
        raise RuntimeError(f"Submodule {path} does not match its pinned revision {revision}")


def create(arguments):
    workspace = Path(git(arguments.workspace, "rev-parse", "--show-toplevel").strip())
    main_workspace = main_checkout(workspace)
    requested = arguments.repo or workspace
    if not requested.is_absolute():
        requested = main_workspace / requested
    repository = Path(git(requested, "rev-parse", "--show-toplevel").strip())
    if repository.resolve() != requested.resolve():
        raise RuntimeError(f"Not an initialized repository root: {requested}; initialize that submodule first")
    source = main_checkout(repository)
    parent_task = common_directory(repository) == common_directory(workspace)
    base = arguments.base or ("dev" if parent_task else "main")
    # Resolve only a local branch, never a remote-tracking ref or an implicit fetch.
    git(repository, "rev-parse", "--verify", f"refs/heads/{base}^{{commit}}")
    git(repository, "check-ref-format", "--branch", arguments.branch)
    try:
        repository_name = str(source.relative_to(main_workspace))
    except ValueError:
        repository_name = source.name
    prefix = "" if parent_task else re.sub(r"[^a-zA-Z0-9_.-]+", "-", repository_name) + "--"
    slug = re.sub(r"[^a-zA-Z0-9_.-]+", "-", arguments.branch)
    checkout = (arguments.path or main_workspace / ".worktrees" / (prefix + slug)).resolve()
    if checkout == repository or checkout == main_workspace:
        raise RuntimeError("Destination must be a separate checkout")
    if arguments.resume:
        registered = git(repository, "worktree", "list", "--porcelain")
        entries = registered.split("\n\n")
        expected = f"worktree {checkout}\n"
        if not any(entry.startswith(expected) and f"branch refs/heads/{arguments.branch}" in entry.splitlines()
                   for entry in entries):
            raise RuntimeError("Resume requires this branch's existing registered worktree at the requested path")
        if git(checkout, "status", "--porcelain").strip():
            raise RuntimeError("Cannot resume setup in a dirty checkout; preserve its changes and finish setup manually")
    else:
        if checkout.exists():
            raise RuntimeError(f"Destination already exists: {checkout}; use --resume for an interrupted setup")
        if subprocess.run(["git", "-C", str(repository), "show-ref", "--verify", "--quiet",
                           f"refs/heads/{arguments.branch}"]).returncode == 0:
            raise RuntimeError(f"Branch already exists: {arguments.branch}; choose a new task branch")
        checkout.parent.mkdir(parents=True, exist_ok=True)
        git(repository, "worktree", "add", "-b", arguments.branch, checkout, f"refs/heads/{base}", capture=False)
    try:
        available = modules(checkout)
        selected = list(available) if arguments.all_submodules else list(arguments.submodule)
        if not arguments.no_submodules and parent_task and "host/compukter-vm" in available:
            selected.insert(0, "host/compukter-vm")
        if arguments.no_submodules:
            selected = []
        for path in dict.fromkeys(selected):
            if path not in available:
                raise RuntimeError(f"Unknown submodule: {path}; available: {', '.join(available)}")
            initialize_module(checkout, source, path, available[path])
        cache = workspace / "tools/gradle/cache.py"
        if cache.exists() and (checkout / "gradlew-sandbox").exists():
            environment = dict(os.environ)
            environment.setdefault("COMPUKTERS_GRADLE_CACHE_TEMPLATE",
                                   str(main_workspace / ".toolchain/gradle-cache/template"))
            subprocess.run([sys.executable, str(cache), "--root", str(checkout), "prepare"],
                           env=environment, check=True)
    except (OSError, RuntimeError, subprocess.CalledProcessError):
        print(f"Setup did not finish; the checkout and branch were preserved at {checkout}.\n"
              "Correct the reported problem and retry the same command with --resume.", file=sys.stderr)
        raise
    print(f"Ready: {checkout}\nBranch: {arguments.branch}\nBase: {base}\n"
          f"cd {shlex.quote(str(checkout))}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("branch", help="new task branch, for example feat/display-api")
    parser.add_argument("--repo", type=Path, help="repository/submodule to work on, relative to the main workspace")
    parser.add_argument("--base", help="local base branch (default: dev in the parent, main in submodules)")
    parser.add_argument("--path", type=Path, help="explicit destination instead of .worktrees/<branch>")
    parser.add_argument("--resume", action="store_true", help="finish an interrupted setup in its clean checkout")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--all-submodules", action="store_true", help="initialize all pinned submodules")
    selection.add_argument("--no-submodules", action="store_true", help="skip submodule initialization")
    parser.add_argument("--submodule", action="append", default=[], help="additional pinned submodule; repeatable")
    arguments = parser.parse_args()
    if arguments.no_submodules and arguments.submodule:
        parser.error("--no-submodules cannot be combined with --submodule")
    try:
        create(arguments)
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(error.stderr.strip(), file=sys.stderr)
        print(f"Worktree setup: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

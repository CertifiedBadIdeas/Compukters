#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Publish stable cache templates and clone independent copy-on-write Gradle homes."""

import argparse
import contextlib
import fcntl
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import uuid


HOME_LOCK = ".compukters-cache.lock"
GENERATION = ".compukters-template-generation"


@contextlib.contextmanager
def lock(path, *, exclusive=True, nonblocking=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+b") as handle:
        operation = fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH
        if nonblocking:
            operation |= fcntl.LOCK_NB
        try:
            fcntl.flock(handle, operation)
        except BlockingIOError as error:
            raise RuntimeError(f"Cache is in use: {path.parent}; retry after its build finishes") from error
        yield


def template_path(root):
    override = os.environ.get("COMPUKTERS_GRADLE_CACHE_TEMPLATE")
    if override:
        return Path(os.path.abspath(os.path.expanduser(override)))
    common = subprocess.check_output(
        ["git", "rev-parse", "--path-format=absolute", "--git-common-dir"], cwd=root, text=True,
    ).strip()
    return Path(common).parent / ".toolchain" / "gradle-cache" / "template"


def bootstrap_sources(root):
    configured = os.environ.get("COMPUKTERS_GRADLE_CACHE_SOURCES")
    if configured is not None:
        return [Path(path).expanduser().resolve() for path in configured.split(os.pathsep) if path]
    result = subprocess.run(
        ["git", "worktree", "list", "--porcelain"], cwd=root, capture_output=True, text=True,
    )
    candidates = [Path(line.removeprefix("worktree ")) / ".gradle-sandbox"
                  for line in result.stdout.splitlines() if line.startswith("worktree ")]
    candidates.append(Path.home() / ".gradle")
    # Prefer homes containing expensive Minecraft caches over a wrapper-only home.
    def warmth(home):
        return sum((home / "caches" / name).is_dir() for name in (
            "modules-2", "fabric-loom", "neoformruntime", "forge_gradle", "jars-9",
        ))
    return sorted(set(candidates), key=lambda home: (-warmth(home), str(home)))


def bootstrap(root, template):
    if (template / "current").exists():
        return
    with lock(template.parent / ".bootstrap.lock"):
        if (template / "current").exists():
            return
        unsupported = []
        for source in bootstrap_sources(root):
            if not any((source / name).is_dir() for name in ("caches", "wrapper")) or source.is_symlink():
                continue
            try:
                seed(source, template)
                return
            except (OSError, RuntimeError) as error:
                print(f"Cannot bootstrap from {source}: {error}", file=sys.stderr)
                if "Reflink cache cloning failed" in str(error):
                    unsupported.append(source)
        if unsupported:
            raise RuntimeError("Available caches cannot be reflinked into the workspace; "
                               "place the template and source on a reflink-capable filesystem")


def clone(source, destination):
    result = subprocess.run(
        ["cp", "-a", "--reflink=always", str(source) + "/.", str(destination)],
        capture_output=True, text=True,
    )
    if result.returncode:
        raise RuntimeError(
            "Reflink cache cloning failed; source and destination must support copy-on-write "
            "on the same filesystem. No full-copy fallback was used.\n" + result.stderr.strip()
        )


def discard_process_state(home):
    for directory, directories, files in os.walk(home):
        for name in list(directories):
            if name in {".tmp", "journal-1"}:
                shutil.rmtree(Path(directory) / name)
                directories.remove(name)
        for name in files:
            if name.endswith((".lock", ".lck", ".tmp")) or name == "gc.properties":
                (Path(directory) / name).unlink()


@contextlib.contextmanager
def source_locks(source):
    # The wrapper gate protects the full build. Native locks additionally detect direct
    # Gradle invocations; unmanaged sources must be idle throughout publication.
    with contextlib.ExitStack() as stack:
        stack.enter_context(lock(source / HOME_LOCK, nonblocking=True))
        for directory, directories, files in os.walk(source):
            # Thousands of per-transform locks live here. The enclosing cache locks and
            # wrapper gate cover managed sources; unmanaged sources must remain idle.
            if ".internal" in directories:
                directories.remove(".internal")
            for name in sorted(files):
                if not name.endswith((".lock", ".lck")) or name == HOME_LOCK:
                    continue
                handle = stack.enter_context((Path(directory) / name).open("r+b"))
                try:
                    fcntl.lockf(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError as error:
                    raise RuntimeError(f"Gradle cache is in use: {handle.name}") from error
        yield


def seed(source, template, *, expected_generation=None):
    source = source.expanduser().resolve()
    if not any((source / name).is_dir() for name in ("caches", "wrapper")):
        raise RuntimeError(f"Not a populated Gradle home: {source} (missing caches/ and wrapper/)")
    if source == template or source in template.parents or template in source.parents:
        raise RuntimeError("Source home and template must be separate directories")
    template.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".seed-", dir=template.parent) as staging:
        staged = Path(staging) / "home"
        staged.mkdir()
        with source_locks(source):
            for name in ("caches", "wrapper"):
                directory = source / name
                if not directory.exists():
                    continue
                if directory.is_symlink() or any(path.is_symlink() for path in directory.rglob("*")):
                    raise RuntimeError(f"Cache contains symbolic links: {directory}")
                (staged / name).mkdir()
                clone(directory, staged / name)
        discard_process_state(staged)
        with lock(template.parent / ".template.lock"):
            current = template / "current"
            actual_generation = current.readlink().name if current.exists() else ""
            if expected_generation is not None and actual_generation != expected_generation:
                # Another successful build published first. Do not replace its newer snapshot
                # with a home initialized from an older generation.
                return None
            generations = template / "generations"
            generations.mkdir(parents=True, exist_ok=True)
            generation = generations / uuid.uuid4().hex
            staged.rename(generation)
            pointer = Path(staging) / "current"
            pointer.symlink_to(Path("generations") / generation.name)
            # Replacing the pointer is atomic, including across process interruption.
            os.replace(pointer, current)
            for previous in generations.iterdir():
                if previous != generation:
                    shutil.rmtree(previous)
    print(f"Published reflink cache template: {template}")
    return generation.name


def prepare(root, template):
    home = root / ".gradle-sandbox"
    with lock(root / ".toolchain" / "gradle-cache-init.lock"):
        if home.exists():
            if home.is_symlink() or not home.is_dir():
                raise RuntimeError(f"Gradle home must be an independent directory: {home}")
            return
        bootstrap(root, template)
        with tempfile.TemporaryDirectory(prefix=".gradle-cache-", dir=root / ".toolchain") as staging:
            staged = Path(staging) / "home"
            staged.mkdir()
            with lock(template.parent / ".template.lock", exclusive=False):
                if (template / "current").exists():
                    clone(template / "current", staged)
                    (staged / GENERATION).write_text((template / "current").readlink().name)
                else:
                    print(
                        "No workspace cache template yet; starting with an empty Gradle home. "
                        "Seed it once with ./gradlew-sandbox-cache seed <idle-gradle-home>.",
                        file=sys.stderr,
                    )
            staged.rename(home)
            print(f"Prepared independent Gradle home: {home}", file=sys.stderr)


def finish(root, template):
    home = root / ".gradle-sandbox"
    if not any((home / name).is_dir() for name in ("caches", "wrapper")):
        return
    marker = home / GENERATION
    origin = marker.read_text() if marker.exists() else ""
    with lock(template.parent / ".template.lock", exclusive=False):
        current = template / "current"
        actual = current.readlink().name if current.exists() else ""
    if actual != origin:
        return
    published = seed(home, template, expected_generation=origin)
    if published:
        marker.write_text(published)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("prepare", help="initialize this worktree's Gradle home if absent")
    commands.add_parser("finish", help="publish the cache after a successful sandbox build")
    publisher = commands.add_parser("seed", help="publish a template from an idle populated Gradle home")
    publisher.add_argument("source", type=Path)
    arguments = parser.parse_args()
    root = arguments.root.resolve()
    try:
        template = template_path(root)
        if arguments.command == "seed":
            seed(arguments.source, template)
        elif arguments.command == "finish":
            finish(root, template)
        else:
            prepare(root, template)
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Gradle cache: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Behavioral checks for actual reflinks, cache publication and sandbox wrappers."""

import fcntl
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/gradle/cache.py"


class CacheTest(unittest.TestCase):
    def setUp(self):
        # /tmp may be tmpfs: fixtures must live on the checkout's filesystem.
        (ROOT / "build").mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="cache-test-", dir=ROOT / "build")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.template = self.directory / "template"
        self.env = dict(os.environ, COMPUKTERS_GRADLE_CACHE_TEMPLATE=str(self.template),
                        COMPUKTERS_GRADLE_CACHE_SOURCES="")
        self.source = self.directory / "source"
        self.artifact = self.source / "caches/modules-2/files-2.1/artifact.jar"
        self.artifact.parent.mkdir(parents=True)
        self.artifact.write_bytes(b"original cache data" * 8192)
        self.checkout = self.directory / "checkout with spaces"
        self.checkout.mkdir()

    def run_cache(self, *arguments, root=None, env=None):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--root", str(root or self.checkout), *map(str, arguments)],
            env=env or self.env, capture_output=True, text=True, timeout=20,
        )

    def require_reflink(self):
        result = subprocess.run(
            ["cp", "--reflink=always", str(self.artifact), str(self.directory / "probe")],
            capture_output=True,
        )
        if result.returncode:
            self.skipTest("checkout filesystem does not support reflinks")

    def seed(self):
        self.require_reflink()
        result = self.run_cache("seed", self.source)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_concurrent_homes_share_data_but_not_mutations_or_inodes(self):
        self.seed()
        second = self.directory / "second"
        second.mkdir()
        processes = [subprocess.Popen(
            [sys.executable, str(SCRIPT), "--root", str(root), "prepare"],
            env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        ) for root in (self.checkout, self.checkout, second)]
        for process in processes:
            output = process.communicate(timeout=20)
            self.assertEqual(0, process.returncode, output)
        relative = self.artifact.relative_to(self.source)
        first_artifact = self.checkout / ".gradle-sandbox" / relative
        second_artifact = second / ".gradle-sandbox" / relative
        template_artifact = self.template / "current" / relative
        self.assertEqual(self.artifact.read_bytes(), first_artifact.read_bytes())
        self.assertEqual(4, len({path.stat().st_ino for path in (
            self.artifact, template_artifact, first_artifact, second_artifact,
        )}))
        first_artifact.write_bytes(b"local mutation")
        self.assertEqual(self.artifact.read_bytes(), second_artifact.read_bytes())
        self.assertEqual(self.artifact.read_bytes(), template_artifact.read_bytes())
        # A second invocation must preserve the already initialized writable home.
        self.assertEqual(0, self.run_cache("prepare").returncode)
        self.assertEqual(b"local mutation", first_artifact.read_bytes())

    def test_seed_excludes_locks_journal_daemons_and_transient_files(self):
        for relative in ("caches/modules-2/modules-2.lock", "caches/gc.properties",
                         "caches/data.tmp", "caches/journal-1/data.bin",
                         "caches/.tmp/download", "wrapper/dists/dist.zip.lck",
                         "daemon/registry.bin", "init.d/local.gradle.kts"):
            path = self.source / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.touch()
        self.seed()
        current = self.template / "current"
        self.assertTrue((current / self.artifact.relative_to(self.source)).is_file())
        self.assertFalse(any(path.name.endswith((".lock", ".lck", ".tmp")) for path in current.rglob("*")))
        self.assertFalse((current / "caches/journal-1").exists())
        self.assertFalse((current / "caches/.tmp").exists())
        self.assertFalse((current / "daemon").exists())
        self.assertFalse((current / "init.d").exists())

    def test_refresh_updates_new_homes_and_preserves_existing_homes(self):
        self.seed()
        self.assertEqual(0, self.run_cache("prepare").returncode)
        self.artifact.write_bytes(b"updated template")
        self.seed()
        self.assertEqual(1, len(list((self.template / "generations").iterdir())))
        second = self.directory / "second"
        second.mkdir()
        self.assertEqual(0, self.run_cache("prepare", root=second).returncode)
        relative = self.artifact.relative_to(self.source)
        self.assertEqual(b"updated template", (second / ".gradle-sandbox" / relative).read_bytes())
        self.assertNotEqual(b"updated template", (self.checkout / ".gradle-sandbox" / relative).read_bytes())

    def test_failed_reflink_preserves_template_and_does_not_publish_partial_home(self):
        generation = self.template / "generations" / "existing"
        shutil.copytree(self.source, generation)
        (self.template / "current").symlink_to(Path("generations/existing"))
        pointer = (self.template / "current").readlink()
        binaries = self.directory / "bin"
        binaries.mkdir()
        cp = binaries / "cp"
        cp.write_text("#!/bin/sh\nprintf 'reflink unsupported\\n' >&2\nexit 1\n")
        cp.chmod(0o755)
        env = dict(self.env, PATH=str(binaries) + os.pathsep + self.env["PATH"])
        for arguments in (("seed", self.source), ("prepare",)):
            result = self.run_cache(*arguments, env=env)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("No full-copy fallback", result.stderr)
        self.assertEqual(pointer, (self.template / "current").readlink())
        self.assertFalse((self.checkout / ".gradle-sandbox").exists())
        self.assertFalse(list(self.template.parent.glob(".seed-*")))
        self.assertFalse(list((self.checkout / ".toolchain").glob(".gradle-cache-*")))

    def test_existing_home_is_preserved_without_template(self):
        home = self.checkout / ".gradle-sandbox"
        home.mkdir()
        marker = home / "keep"
        marker.write_text("user cache")
        self.assertEqual(0, self.run_cache("prepare").returncode)
        self.assertEqual("user cache", marker.read_text())
        self.assertFalse(self.template.exists())

    def test_automatic_bootstrap_and_recreation_after_home_deletion(self):
        self.require_reflink()
        env = dict(self.env, COMPUKTERS_GRADLE_CACHE_SOURCES=str(self.source))
        for _ in range(2):
            result = self.run_cache("prepare", env=env)
            self.assertEqual(0, result.returncode, result.stderr)
            home = self.checkout / ".gradle-sandbox"
            self.assertEqual(self.artifact.read_bytes(), (home / self.artifact.relative_to(self.source)).read_bytes())
            shutil.rmtree(home)

    def test_successful_cold_build_publishes_template_without_manual_seeding(self):
        self.require_reflink()
        self.install_wrapper(self.checkout)
        gradle = self.checkout / "gradlew"
        gradle.write_text("""#!/usr/bin/env python3
import os, pathlib
artifact = pathlib.Path(os.environ['GRADLE_USER_HOME']) / 'caches/downloaded.jar'
artifact.parent.mkdir(parents=True, exist_ok=True)
artifact.write_bytes(b'downloaded during first build')
""")
        result = subprocess.run([str(self.checkout / "gradlew-sandbox")], env=self.env,
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(b"downloaded during first build", (self.template / "current/caches/downloaded.jar").read_bytes())
        shutil.rmtree(self.checkout / ".gradle-sandbox")
        self.assertEqual(0, self.run_cache("prepare").returncode)
        self.assertEqual(b"downloaded during first build",
                         (self.checkout / ".gradle-sandbox/caches/downloaded.jar").read_bytes())

    def test_older_home_cannot_replace_newer_template_after_parallel_build(self):
        self.seed()
        self.assertEqual(0, self.run_cache("prepare").returncode)
        self.artifact.write_bytes(b"newer published data")
        self.seed()
        pointer = (self.template / "current").readlink()
        self.assertEqual(0, self.run_cache("finish").returncode)
        self.assertEqual(pointer, (self.template / "current").readlink())
        self.assertEqual(b"newer published data", (self.template / "current" / self.artifact.relative_to(self.source)).read_bytes())

    def test_wrapper_only_home_can_seed_distributions_before_first_build(self):
        self.require_reflink()
        shutil.rmtree(self.source / "caches")
        distribution = self.source / "wrapper/dists/gradle/bin/gradle"
        distribution.parent.mkdir(parents=True)
        distribution.write_bytes(b"distribution")
        result = self.run_cache("seed", self.source)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(b"distribution", (self.template / "current" / distribution.relative_to(self.source)).read_bytes())

    def test_success_status_survives_automatic_template_refresh_failure(self):
        self.install_wrapper(self.checkout)
        home = self.checkout / ".gradle-sandbox"
        shutil.copytree(self.source, home)
        binaries = self.directory / "bin"
        binaries.mkdir()
        cp = binaries / "cp"
        cp.write_text("#!/bin/sh\nexit 1\n")
        cp.chmod(0o755)
        env = dict(self.env, PATH=str(binaries) + os.pathsep + self.env["PATH"])
        result = subprocess.run([str(self.checkout / "gradlew-sandbox")], env=env,
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("could not be refreshed", result.stderr)
        self.assertFalse((self.template / "current").exists())

    def test_missing_template_creates_empty_home(self):
        result = self.run_cache("prepare")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("No workspace cache template", result.stderr)
        self.assertEqual([], list((self.checkout / ".gradle-sandbox").iterdir()))

    def test_symlink_home_and_symlink_source_are_rejected(self):
        (self.checkout / ".gradle-sandbox").symlink_to(self.source)
        self.assertNotEqual(0, self.run_cache("prepare").returncode)
        (self.artifact.parent / "shared.jar").symlink_to(self.artifact)
        self.assertNotEqual(0, self.run_cache("seed", self.source).returncode)
        self.assertFalse((self.template / "current").exists())

    def test_active_gradle_native_lock_is_rejected(self):
        path = self.source / "caches/modules-2/modules-2.lock"
        with path.open("w+b") as handle:
            fcntl.lockf(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.run_cache("seed", self.source)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("in use", result.stderr)
        self.assertFalse((self.template / "current").exists())

    def install_wrapper(self, root):
        (root / "tools/gradle").mkdir(parents=True)
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        for name in ("cache.py", "run.py", "build.sh"):
            shutil.copy2(ROOT / "tools/gradle" / name, root / "tools/gradle" / name)
        shutil.copy2(ROOT / "gradlew-sandbox", root / "gradlew-sandbox")
        gradle = root / "gradlew"
        gradle.write_text("""#!/usr/bin/env python3
import os, pathlib, sys, time
home = pathlib.Path(os.environ['GRADLE_USER_HOME'])
(home / 'ready').write_text('running')
(home / 'arguments').write_text('\\n'.join(sys.argv[1:]))
time.sleep(float(os.environ.get('FAKE_GRADLE_SLEEP', '0')))
sys.exit(int(os.environ.get('FAKE_GRADLE_EXIT', '0')))
""")
        gradle.chmod(0o755)

    def test_wrapper_preserves_arguments_exit_code_and_blocks_seeding_active_home(self):
        self.install_wrapper(self.checkout)
        env = dict(self.env, FAKE_GRADLE_SLEEP="2", FAKE_GRADLE_EXIT="7")
        process = subprocess.Popen(
            [str(self.checkout / "gradlew-sandbox"), "task", "-Pmessage=hello world"],
            cwd=self.directory, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        try:
            home = self.checkout / ".gradle-sandbox"
            deadline = time.monotonic() + 10
            while not (home / "ready").exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue((home / "ready").exists())
            (home / "caches").mkdir()
            result = self.run_cache("seed", home)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("in use", result.stderr)
            self.assertEqual(["--no-daemon", "-Dorg.gradle.vfs.watch=false", "--max-workers=8",
                              "-PcompukterVmBuildJobs=8", "task",
                              "-Pmessage=hello world"], (home / "arguments").read_text().splitlines())
            process.communicate(timeout=10)
            self.assertEqual(7, process.returncode)
        finally:
            if process.poll() is None:
                process.kill()
            process.communicate()


if __name__ == "__main__":
    unittest.main()

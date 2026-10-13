# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Safety and readiness checks against real Git checkouts and process locks."""

import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock

import test_create
from test_create import git
import lifecycle

CLI = Path(__file__).with_name('cli.py')


class LifecycleTest(unittest.TestCase):
    check_success = test_create.WorktreeTest.check_success
    add_module = test_create.WorktreeTest.add_module

    def setUp(self):
        test_create.WorktreeTest.setUp(self)
        with (self.workspace / '.gitignore').open('a') as handle:
            handle.write('build/\n')
        git(self.workspace, 'commit', '-am', 'ignore generated build output')

    # Reuse fixture helpers without running setup tests twice.
    def command(self, branch='feat/example', *arguments, **kwargs):
        return test_create.WorktreeTest.command(self, branch, *arguments, **kwargs)

    def lifecycle(self, *arguments, workspace=None):
        return subprocess.run([sys.executable, str(CLI), '--workspace', str(workspace or self.workspace),
                               *map(str, arguments)], capture_output=True, text=True, timeout=30)

    def ready_task(self):
        self.check_success(self.command())
        checkout = self.workspace / '.worktrees/feat-example'
        (checkout / 'tracked').write_text('task change')
        git(checkout, 'commit', '-am', 'task change')
        evidence = checkout / 'build/agent-logs/check.log'
        evidence.parent.mkdir(parents=True)
        evidence.write_text('verification passed')
        git(self.workspace, 'merge', '--ff-only', 'feat/example')
        return checkout

    def test_finish_archives_evidence_ignored_user_data_and_keeps_branch(self):
        checkout = self.ready_task()
        git(checkout, 'config', 'core.excludesFile', str(self.directory / 'ignore'))
        (self.directory / 'ignore').write_text('world/\n')
        world = checkout / 'world/save.dat'
        world.parent.mkdir()
        world.write_bytes(b'game save')
        result = self.lifecycle('finish', 'feat/example')
        self.check_success(result)
        self.assertFalse(checkout.exists())
        self.assertEqual(git(self.workspace, 'rev-parse', 'dev'), git(self.workspace, 'rev-parse', 'feat/example'))
        archive = next((self.workspace / 'build/agent-logs/worktree-completion').iterdir())
        self.assertEqual('verification passed', (archive / '0-agent-logs/check.log').read_text())
        self.assertEqual(b'game save', (archive / 'preserved-files/world/save.dat').read_bytes())
        self.assertEqual('dev', json.loads((archive / 'completion.json').read_text())['base'])

    def test_finish_ignores_nested_gradle_caches_but_preserves_world_data(self):
        checkout = self.ready_task()
        git(checkout, 'config', 'core.excludesFile', str(self.directory / 'ignore'))
        (self.directory / 'ignore').write_text('.gradle-sandbox/\nrun/\n')
        cache = checkout / 'addons/example/.gradle-sandbox/caches/src/cached-data'
        cache.parent.mkdir(parents=True)
        cache.write_text('disposable cache')
        world = checkout / 'addons/example/run/world/save.dat'
        world.parent.mkdir(parents=True)
        world.write_text('keep world')
        self.check_success(self.lifecycle('finish', 'feat/example'))
        archive = next((self.workspace / 'build/agent-logs/worktree-completion').iterdir())
        self.assertFalse(any(path.name == 'cached-data' for path in archive.rglob('*')))
        self.assertEqual('keep world', (archive / 'preserved-files/addons/example/run/world/save.dat').read_text())

    def test_finish_refuses_active_nested_independent_gradle_build(self):
        checkout = self.ready_task()
        wrapper = checkout / 'addons/example/gradlew-sandbox'
        wrapper.parent.mkdir(parents=True)
        wrapper.write_text('#!/bin/sh\n')
        git(checkout, 'add', 'addons/example/gradlew-sandbox')
        git(checkout, 'commit', '-m', 'nested independent build')
        git(self.workspace, 'merge', '--ff-only', 'feat/example')
        git(checkout, 'config', 'core.excludesFile', str(self.directory / 'ignore'))
        (self.directory / 'ignore').write_text('.gradle-sandbox/\n')
        lock = wrapper.parent / '.gradle-sandbox/.compukters-cache.lock'
        lock.parent.mkdir()
        with lock.open('wb') as handle:
            fcntl.flock(handle, fcntl.LOCK_SH)
            self.assertIn('build is active', self.lifecycle('finish', 'feat/example').stderr)
        self.assertTrue(checkout.exists())
        self.check_success(self.lifecycle('finish', 'feat/example'))

    def test_finish_refuses_unmerged_dirty_or_missing_evidence(self):
        self.check_success(self.command())
        checkout = self.workspace / '.worktrees/feat-example'
        (checkout / 'tracked').write_text('task change')
        git(checkout, 'commit', '-am', 'task change')
        self.assertIn('not integrated', self.lifecycle('finish', 'feat/example').stderr)
        git(self.workspace, 'merge', '--ff-only', 'feat/example')
        self.assertIn('No verification evidence', self.lifecycle('finish', 'feat/example').stderr)
        (checkout / 'new-user-file').write_text('keep')
        self.assertIn('untracked', self.lifecycle('finish', 'feat/example').stderr)
        self.assertEqual('keep', (checkout / 'new-user-file').read_text())

    def test_finish_refuses_wrong_branch_locked_checkout_or_unrecorded_worktree(self):
        checkout = self.ready_task()
        self.assertNotEqual(0, self.lifecycle('finish', 'feat/example', '--path', self.directory).returncode)
        git(self.workspace, 'worktree', 'lock', checkout)
        self.assertIn('locked', self.lifecycle('finish', 'feat/example').stderr)
        git(self.workspace, 'worktree', 'unlock', checkout)
        git(checkout, 'checkout', '-b', 'feat/wrong')
        self.assertIn('does not match', self.lifecycle('finish', 'feat/example').stderr)
        self.assertIn('No helper task record', self.lifecycle('finish', 'dev').stderr)
        self.assertTrue(checkout.exists())

    def test_finish_refuses_running_gradle_and_keeps_evidence(self):
        checkout = self.ready_task()
        lock = checkout / '.gradle-sandbox/.compukters-cache.lock'
        lock.parent.mkdir()
        with lock.open('wb') as handle:
            fcntl.flock(handle, fcntl.LOCK_SH)
            self.assertIn('build is active', self.lifecycle('finish', 'feat/example').stderr)
        self.assertTrue(checkout.exists())
        self.check_success(self.lifecycle('finish', 'feat/example'))

    def test_finish_parent_with_initialized_submodule(self):
        self.add_module()
        # Restore main source to its pinned revision; source dirty state is independent.
        checkout = self.ready_task()
        self.check_success(self.lifecycle('finish', 'feat/example'))
        self.assertFalse(checkout.exists())
        self.assertTrue((self.workspace / 'host/compukter-vm/.git').exists())

    def test_finish_preserves_dirty_submodule_and_unpublished_module_branch(self):
        path, _, _ = self.add_module()
        checkout = self.ready_task()
        module = checkout / path
        (module / 'version').write_text('user edit')
        self.assertNotEqual(0, self.lifecycle('finish', 'feat/example').returncode)
        git(module, 'checkout', '--', 'version')
        git(module, 'config', 'user.name', 'Fixture')
        git(module, 'config', 'user.email', 'fixture@example.invalid')
        git(module, 'checkout', '-b', 'private-work')
        (module / 'version').write_text('private change')
        git(module, 'commit', '-am', 'private')
        pinned = git(checkout, 'rev-parse', f'HEAD:{path}')
        git(module, 'checkout', '--detach', pinned)
        self.assertIn('unpublished', self.lifecycle('finish', 'feat/example').stderr)
        self.assertTrue(module.exists())

    def test_finish_submodule_task_uses_main_and_main_workspace_archive(self):
        path, _, _ = self.add_module()
        source = self.workspace / path
        git(source, 'checkout', '-B', 'main', 'HEAD')
        self.check_success(self.command('fix/module', '--repo', path))
        checkout = self.workspace / '.worktrees/host-compukter-vm--fix-module'
        proof = self.directory / 'proof.log'
        proof.write_text('verified')
        self.check_success(self.lifecycle('finish', 'fix/module', '--repo', path, '--evidence', proof))
        self.assertFalse(checkout.exists())
        self.assertTrue((self.workspace / 'build/agent-logs/worktree-completion').is_dir())

    def test_finish_rejects_empty_missing_symlink_or_recursive_archive_source(self):
        checkout = self.ready_task()
        missing = self.directory / 'missing'
        self.assertNotEqual(0, self.lifecycle('finish', 'feat/example', '--evidence', missing).returncode)
        missing.mkdir()
        self.assertIn('empty', self.lifecycle('finish', 'feat/example', '--evidence', missing).stderr)
        link = self.directory / 'link'
        link.symlink_to(checkout / 'build/agent-logs/check.log')
        self.assertIn('symbolic link', self.lifecycle('finish', 'feat/example', '--evidence', link).stderr)
        self.assertIn('destination', self.lifecycle('finish', 'feat/example', '--evidence', self.workspace).stderr)
        self.assertTrue(checkout.exists())

    def test_finish_refuses_module_worktrees_and_hidden_pin_mismatch(self):
        path, _, _ = self.add_module()
        checkout = self.ready_task()
        module = checkout / path
        additional = self.directory / 'module-worktree'
        git(module, 'worktree', 'add', '--detach', additional)
        self.assertIn('additional worktrees', self.lifecycle('finish', 'feat/example').stderr)
        git(module, 'worktree', 'remove', additional)
        git(module, 'config', 'user.name', 'Fixture')
        git(module, 'config', 'user.email', 'fixture@example.invalid')
        (module / 'version').write_text('unrecorded HEAD')
        git(module, 'commit', '-am', 'unrecorded')
        git(checkout, 'config', 'submodule.host/compukter-vm.ignore', 'all')
        self.assertIn('committed pin', self.lifecycle('finish', 'feat/example').stderr)
        self.assertTrue(checkout.exists())

    def test_finish_preserves_files_in_uninitialized_module(self):
        path, _, _ = self.add_module()
        self.check_success(self.command('feat/none', '--no-submodules'))
        checkout = self.workspace / '.worktrees/feat-none'
        file = checkout / path / 'user-file'
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text('keep')
        proof = self.directory / 'proof.log'
        proof.write_text('verified')
        result = self.lifecycle('finish', 'feat/none', '--evidence', proof)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('keep', file.read_text())

    def test_archive_failure_preserves_checkout_and_task_record(self):
        checkout = self.ready_task()
        args = type('Arguments', (), dict(branch='feat/example', repo=None, path=None, evidence=[]))()
        with mock.patch('lifecycle.shutil.copytree', side_effect=OSError('disk full')):
            with self.assertRaisesRegex(OSError, 'disk full'):
                lifecycle.finish(self.workspace, args)
        self.assertTrue(checkout.exists())
        self.assertTrue(lifecycle.task_record(self.workspace, 'feat/example').exists())

    def test_doctor_reports_uninitialized_vm_wrong_pin_and_optional_addons(self):
        vm, pinned, _ = self.add_module()
        self.add_module('addons/create')
        self.check_success(self.command('feat/empty', '--no-submodules'))
        checkout = self.workspace / '.worktrees/feat-empty'
        result = self.lifecycle('doctor', '--path', checkout)
        self.assertEqual(0, result.returncode)
        self.assertIn('INFO: host/compukter-vm: not initialized', result.stdout)
        self.assertIn('INFO: addons/create: not initialized', result.stdout)
        self.check_success(self.command('feat/ready'))
        ready = self.workspace / '.worktrees/feat-ready'
        self.check_success(self.lifecycle('doctor', '--path', ready))
        git(ready, 'submodule', 'deinit', '--all')
        result = self.lifecycle('doctor', '--path', ready)
        self.assertEqual(1, result.returncode)
        self.assertIn('ERROR: host/compukter-vm: not initialized', result.stdout)
        self.check_success(self.command('feat/ready', '--resume'))
        module = ready / vm
        git(module, 'config', 'user.name', 'Fixture')
        git(module, 'config', 'user.email', 'fixture@example.invalid')
        (module / 'version').write_text('wrong pin')
        git(module, 'commit', '-am', 'advance task module')
        self.assertNotEqual(pinned, git(module, 'rev-parse', 'HEAD'))
        self.assertEqual(1, self.lifecycle('doctor', '--path', ready).returncode)

    def test_doctor_gradle_cold_home_jdk_reflink_and_symlink(self):
        (self.workspace / 'gradlew-sandbox').touch()
        # A real script substitutes only the Java version, not Git or filesystem operations.
        java = self.directory / 'java'
        java.write_text('#!/bin/sh\nprintf \'openjdk version "25"\\n\' >&2\n')
        java.chmod(0o755)
        messages = lifecycle.diagnose(self.workspace, java=str(java))
        self.assertFalse(any(level == 'ERROR' and 'reflink' not in message for level, message in messages), messages)
        self.assertTrue(any('Template-to-checkout reflink' in message for _, message in messages))
        self.assertTrue(any('absent' in message for _, message in messages))
        home = self.workspace / '.gradle-sandbox'
        home.mkdir()
        self.assertTrue(any('cold' in message for _, message in lifecycle.diagnose(self.workspace, java=str(java))))
        java.write_text('#!/bin/sh\nprintf \'openjdk version "21"\\n\' >&2\n')
        self.assertTrue(any(level == 'ERROR' and 'JDK 25' in message for level, message in lifecycle.diagnose(self.workspace, java=str(java))))
        home.rmdir()
        home.symlink_to(self.directory)
        self.assertTrue(any(level == 'ERROR' and 'independent' in message for level, message in lifecycle.diagnose(self.workspace, java=str(java))))

    def test_doctor_reports_unsupported_reflink(self):
        (self.workspace / 'gradlew-sandbox').touch()
        java = self.directory / 'java'
        java.write_text('#!/bin/sh\nprintf \'openjdk version "25"\\n\' >&2\n')
        java.chmod(0o755)
        original = subprocess.run
        def run(command, **kwargs):
            if command[:2] == ['cp', '--reflink=always']:
                return subprocess.CompletedProcess(command, 1, '', 'Operation not supported')
            return original(command, **kwargs)
        with mock.patch('lifecycle.subprocess.run', side_effect=run):
            messages = lifecycle.diagnose(self.workspace, java=str(java))
        self.assertTrue(any(level == 'ERROR' and 'Operation not supported' in message for level, message in messages))

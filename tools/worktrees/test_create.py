# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Exercise task setup against real local Git repositories, including pinned submodules."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/worktrees/create.py'


def git(root, *arguments):
    return subprocess.check_output(['git', '-C', str(root), *map(str, arguments)], text=True).strip()


class WorktreeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='worktree-fixture-')
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.workspace = self.directory / 'workspace with spaces'
        self.workspace.mkdir()
        git(self.workspace, 'init', '--initial-branch=dev')
        git(self.workspace, 'config', 'user.name', 'Fixture')
        git(self.workspace, 'config', 'user.email', 'fixture@example.invalid')
        (self.workspace / 'tracked').write_text('base')
        (self.workspace / '.gitignore').write_text('.worktrees/\n.gradle-sandbox/\n.toolchain/\n')
        git(self.workspace, 'add', '.')
        git(self.workspace, 'commit', '-m', 'fixture base')
        self.base = git(self.workspace, 'rev-parse', 'HEAD')

    def command(self, branch='feat/example', *arguments, workspace=None, env=None):
        return subprocess.run([sys.executable, str(SCRIPT), '--workspace', str(workspace or self.workspace),
                               branch, *map(str, arguments)], capture_output=True, text=True,
                              env=env, timeout=30)

    def check_success(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def add_module(self, path='host/compukter-vm'):
        remote = self.directory / path.replace('/', '-')
        remote.mkdir()
        git(remote, 'init', '--initial-branch=main')
        git(remote, 'config', 'user.name', 'Fixture')
        git(remote, 'config', 'user.email', 'fixture@example.invalid')
        (remote / 'version').write_text('pinned')
        git(remote, 'add', '.')
        git(remote, 'commit', '-m', 'pinned')
        revision = git(remote, 'rev-parse', 'HEAD')
        git(self.workspace, '-c', 'protocol.file.allow=always', 'submodule', 'add', remote.as_uri(), path)
        git(self.workspace, 'commit', '-m', 'pin module')
        (remote / 'version').write_text('new upstream version')
        git(remote, 'commit', '-am', 'advance remote main')
        # Advance the source checkout too; task setup must still check out the parent's pinned commit.
        git(self.workspace / path, '-c', 'protocol.file.allow=always', 'fetch', 'origin')
        git(self.workspace / path, 'checkout', '--detach', 'origin/main')
        unavailable = remote.with_name(remote.name + '-offline')
        remote.rename(unavailable)
        return path, revision, remote.as_uri()

    def test_create_uses_dev_and_preserves_dirty_original_checkout(self):
        (self.workspace / 'tracked').write_text('uncommitted user edit')
        result = self.command()
        self.check_success(result)
        checkout = self.workspace / '.worktrees/feat-example'
        self.assertEqual(self.base, git(checkout, 'rev-parse', 'HEAD'))
        self.assertEqual('feat/example', git(checkout, 'branch', '--show-current'))
        self.assertEqual('base', (checkout / 'tracked').read_text())
        self.assertEqual('uncommitted user edit', (self.workspace / 'tracked').read_text())
        self.assertIn(str(checkout), result.stdout)

    def test_existing_destination_and_branch_are_preserved(self):
        self.check_success(self.command())
        checkout = self.workspace / '.worktrees/feat-example'
        (checkout / 'untracked-user-file').write_text('keep')
        self.assertNotEqual(0, self.command().returncode)
        self.assertEqual('keep', (checkout / 'untracked-user-file').read_text())
        result = self.command('feat/example', '--path', self.directory / 'other')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.directory / 'other').exists())

    def test_invalid_branch_and_missing_base_do_not_create_worktrees(self):
        self.assertNotEqual(0, self.command('invalid..branch').returncode)
        self.assertNotEqual(0, self.command('feat/new', '--base', 'absent').returncode)
        self.assertEqual(1, git(self.workspace, 'worktree', 'list', '--porcelain').count('worktree '))

    def test_selected_modules_reuse_local_git_objects_offline_and_preserve_url(self):
        vm, revision, url = self.add_module()
        addon, addon_revision, addon_url = self.add_module('addons/create')
        result = self.command('feat/modules', '--submodule', addon)
        self.check_success(result)
        checkout = self.workspace / '.worktrees/feat-modules'
        for path, pinned, original in ((vm, revision, url), (addon, addon_revision, addon_url)):
            self.assertEqual(pinned, git(checkout / path, 'rev-parse', 'HEAD'))
            self.assertEqual(original, git(checkout / path, 'remote', 'get-url', 'origin'))
            # Local clones are independent, rather than borrowing an ephemeral object directory.
            common = Path(git(checkout / path, 'rev-parse', '--path-format=absolute', '--git-common-dir'))
            self.assertFalse((common / 'objects/info/alternates').exists())
        self.assertNotEqual(revision, git(self.workspace / vm, 'rev-parse', 'HEAD'))

    def test_no_submodules_and_all_submodules(self):
        vm, revision, _ = self.add_module()
        addon, addon_revision, _ = self.add_module('addons/create')
        self.check_success(self.command('feat/none', '--no-submodules'))
        self.assertFalse((self.workspace / '.worktrees/feat-none' / vm / '.git').exists())
        self.check_success(self.command('feat/all', '--all-submodules'))
        all_checkout = self.workspace / '.worktrees/feat-all'
        self.assertEqual(revision, git(all_checkout / vm, 'rev-parse', 'HEAD'))
        self.assertEqual(addon_revision, git(all_checkout / addon, 'rev-parse', 'HEAD'))

    def test_vm_is_default_and_addons_remain_optional(self):
        vm, revision, _ = self.add_module()
        addon, _, _ = self.add_module('addons/create')
        self.check_success(self.command('feat/default'))
        checkout = self.workspace / '.worktrees/feat-default'
        self.assertEqual(revision, git(checkout / vm, 'rev-parse', 'HEAD'))
        self.assertFalse((checkout / addon / '.git').exists())

    def test_submodule_task_uses_main_without_changing_parent_pin(self):
        path, _, _ = self.add_module()
        module = self.workspace / path
        git(module, 'checkout', '-B', 'main', 'HEAD')
        base = git(module, 'rev-parse', 'main')
        before = git(self.workspace, 'status', '--porcelain')
        result = self.command('fix/module', '--repo', path)
        self.check_success(result)
        checkout = self.workspace / '.worktrees/host-compukter-vm--fix-module'
        self.assertEqual(base, git(checkout, 'rev-parse', 'HEAD'))
        self.assertEqual(before, git(self.workspace, 'status', '--porcelain'))
        self.assertIn('Base: main', result.stdout)

    def test_uninitialized_submodule_is_not_mistaken_for_parent_repository(self):
        path, _, _ = self.add_module()
        git(self.workspace, 'submodule', 'deinit', '--force', '--', path)
        result = self.command('fix/module', '--repo', path)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Not an initialized repository root', result.stderr)
        self.assertEqual(1, git(self.workspace, 'worktree', 'list', '--porcelain').count('worktree '))

    def test_interrupted_setup_can_resume_but_dirty_checkout_cannot(self):
        result = self.command('feat/resume', '--submodule', 'missing')
        self.assertNotEqual(0, result.returncode)
        checkout = self.workspace / '.worktrees/feat-resume'
        self.assertTrue(checkout.exists())
        self.assertIn('--resume', result.stderr)
        self.check_success(self.command('feat/resume', '--resume'))
        (checkout / 'tracked').write_text('user edit')
        self.assertNotEqual(0, self.command('feat/resume', '--resume').returncode)
        self.assertEqual('user edit', (checkout / 'tracked').read_text())
        self.assertNotEqual(0, self.command('feat/other', '--resume', '--path', checkout).returncode)

    def test_invocation_from_linked_checkout_uses_main_worktree_directory(self):
        self.check_success(self.command('feat/first'))
        first = self.workspace / '.worktrees/feat-first'
        self.check_success(self.command('feat/second', workspace=first))
        self.assertTrue((self.workspace / '.worktrees/feat-second').exists())
        self.assertFalse((first / '.worktrees').exists())

    def test_linked_checkout_selects_initialized_module_in_main_workspace(self):
        path, revision, _ = self.add_module()
        self.check_success(self.command('feat/first', '--no-submodules'))
        first = self.workspace / '.worktrees/feat-first'
        self.check_success(self.command('fix/module', '--repo', path, workspace=first))
        checkout = self.workspace / '.worktrees/host-compukter-vm--fix-module'
        self.assertEqual(revision, git(checkout, 'rev-parse', 'HEAD'))

    def test_cache_preparation_receives_new_checkout_and_workspace_template(self):
        cache = self.workspace / 'tools/gradle/cache.py'
        cache.parent.mkdir(parents=True)
        cache.write_text('''import os, pathlib, sys
root = pathlib.Path(sys.argv[sys.argv.index('--root') + 1])
(root / '.gradle-sandbox').mkdir()
(root / '.gradle-sandbox/template').write_text(os.environ['COMPUKTERS_GRADLE_CACHE_TEMPLATE'])
''')
        (self.workspace / 'gradlew-sandbox').touch()
        git(self.workspace, 'add', 'tools/gradle/cache.py', 'gradlew-sandbox')
        git(self.workspace, 'commit', '-m', 'fixture Gradle workflow')
        environment = dict(os.environ)
        environment.pop('COMPUKTERS_GRADLE_CACHE_TEMPLATE', None)
        self.check_success(self.command(env=environment))
        home = self.workspace / '.worktrees/feat-example/.gradle-sandbox'
        self.assertEqual(str(self.workspace / '.toolchain/gradle-cache/template'), (home / 'template').read_text())


if __name__ == '__main__':
    unittest.main()

# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Diagnose task prerequisites and remove only clean, integrated task checkouts."""

import argparse
import contextlib
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

from create import git, main_checkout, modules, task_record


def repository_root(path):
    path = path.resolve()
    top = Path(git(path, 'rev-parse', '--show-toplevel').strip()).resolve()
    if top != path:
        raise RuntimeError(f'Not an initialized repository root: {path}')
    return top


def registered(repository):
    result = {}
    for entry in git(repository, 'worktree', 'list', '--porcelain', '-z').split('\0\0'):
        fields = entry.split('\0')
        if fields and fields[0].startswith('worktree '):
            result[Path(fields[0][9:]).resolve()] = fields[1:]
    return result


def diagnose(checkout, *, java=None):
    """Return severity/message pairs. Cold caches are informational, not failures."""
    messages = []
    def report(level, message):
        messages.append((level, message))
    root = repository_root(checkout)
    fields = registered(root).get(root, [])
    branch = git(root, 'branch', '--show-current').strip() or '(detached)'
    report('OK', f'Checkout: {root}; branch: {branch}; HEAD: {git(root, "rev-parse", "HEAD").strip()}')
    if any(field.startswith(('locked', 'prunable')) for field in fields):
        report('ERROR', 'Worktree is locked or prunable; inspect git worktree list --porcelain')
    dirty = git(root, 'status', '--porcelain').strip()
    report('WARN' if dirty else 'OK', 'Checkout has uncommitted changes' if dirty else 'Checkout is clean')
    required = {'host/compukter-vm'}
    record_path = task_record(root, branch)
    if record_path.exists():
        record = json.loads(record_path.read_text())
        if record.get('checkout') == str(root) and isinstance(record.get('submodules'), list):
            required = set(record['submodules'])
    for path in modules(root):
        module = root / path
        if not (module / '.git').exists():
            report('ERROR' if path in required else 'INFO',
                   f'{path}: not initialized; select it during setup if required')
            continue
        pinned = git(root, 'rev-parse', f'HEAD:{path}').strip()
        actual = git(module, 'rev-parse', 'HEAD').strip()
        report('OK' if actual == pinned else 'ERROR', f'{path}: HEAD {actual}; pinned {pinned}')
        if git(module, 'status', '--porcelain').strip():
            report('WARN', f'{path}: uncommitted changes')
    if not (root / 'gradlew-sandbox').exists():
        report('INFO', 'No Gradle wrapper in this repository; Gradle/JDK/cache checks do not apply')
        return messages
    java = java or (str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME')
                    else shutil.which('java'))
    if not java or not Path(java).is_file():
        report('ERROR', 'Java launcher missing; select JDK 25 with JAVA_HOME')
    else:
        result = subprocess.run([java, '-version'], capture_output=True, text=True, timeout=15)
        version = re.search(r'version "(\d+)', result.stderr + result.stdout)
        report('OK' if result.returncode == 0 and version and version[1] == '25' else 'ERROR',
               f'Java launcher: {java}; expected JDK 25; ' + (result.stderr + result.stdout).split('\n')[0])
    for command in ('cp', 'flock'):
        report('OK' if shutil.which(command) else 'ERROR', f'{command}: ' + (shutil.which(command) or 'missing'))
    home = root / '.gradle-sandbox'
    if home.is_symlink() or (home.exists() and not home.is_dir()):
        report('ERROR', f'Gradle home must be an independent directory: {home}')
    elif home.exists():
        populated = any((home / path).is_dir() for path in ('caches', 'wrapper'))
        report('OK' if populated else 'INFO', f'Gradle home: {home}; ' + ('populated (offline completeness not verified)' if populated else 'cold; first build may require downloads'))
        lock = home / '.compukters-cache.lock'
        if lock.exists():
            with lock.open('rb') as handle:
                try:
                    fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    report('WARN', 'Managed Gradle build is active; completion must wait')
    else:
        report('INFO', 'Gradle home is absent; sandbox wrapper will prepare it on first build')
    workspace = main_checkout(root)
    template = Path(os.environ.get('COMPUKTERS_GRADLE_CACHE_TEMPLATE',
                                 str(workspace / '.toolchain/gradle-cache/template'))).expanduser().absolute()
    report('OK' if (template / 'current').exists() else 'INFO', f'Cache template: {template}; ' + ('available' if (template / 'current').exists() else 'absent; automatic bootstrap or first successful build will populate it'))
    source_directory = template
    while not source_directory.exists():
        source_directory = source_directory.parent
    try:
        with tempfile.TemporaryDirectory(prefix='.reflink-doctor-', dir=source_directory) as source, \
                tempfile.TemporaryDirectory(prefix='.reflink-doctor-', dir=root) as destination:
            probe = Path(source) / 'probe'
            probe.write_bytes(os.urandom(4096))
            result = subprocess.run(['cp', '--reflink=always', str(probe), str(Path(destination) / 'probe')],
                                    capture_output=True, text=True)
            report('OK' if result.returncode == 0 else 'ERROR', 'Template-to-checkout reflink: ' + ('supported' if result.returncode == 0 else result.stderr.strip()))
    except OSError as error:
        report('ERROR', f'Cannot probe template-to-checkout reflink: {error}')
    return messages


def generated(path):
    # Worlds, IDE settings and temporary plans are deliberately not disposable.
    parts = Path(path).parts
    return '.gradle-sandbox' in parts or ('src' not in parts and any(part in ('build', '.gradle', '.kotlin') for part in parts)) or parts[0] in ('.toolchain', 'target')


def check_clean(root):
    if git(root, 'status', '--porcelain', '--untracked-files=all').strip():
        raise RuntimeError(f'Checkout has uncommitted or untracked changes: {root}')
    ignored = git(root, 'ls-files', '--others', '--ignored', '--exclude-standard', '-z')
    unexpected = [path for path in ignored.split('\0') if path and not generated(path)]
    return [root / path for path in unexpected]


def initialized_modules(root):
    result = []
    for path in modules(root):
        module = root / path
        if (module / '.git').exists():
            result.append(module)
            result.extend(initialized_modules(module))
    return result


@contextlib.contextmanager
def idle_builds(roots):
    build_roots = set(roots)
    for root in roots:
        wrappers = git(root, 'ls-files', '-z', '--', 'gradlew-sandbox', '*/gradlew-sandbox')
        build_roots.update((root / path).parent for path in wrappers.split('\0') if path)
    with contextlib.ExitStack() as stack:
        for root in sorted(build_roots):
            home = root / '.gradle-sandbox'
            if home.is_symlink() or (home.exists() and not home.is_dir()):
                raise RuntimeError(f'Gradle home must be an independent directory: {home}')
            lock = home / '.compukters-cache.lock'
            if home.is_dir():
                handle = stack.enter_context(lock.open('a+b'))
                try:
                    fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError as error:
                    raise RuntimeError(f'Managed Gradle build is active: {root}') from error
        yield


def finish(workspace, arguments):
    main_workspace = main_checkout(repository_root(workspace))
    repository = repository_root(main_workspace / arguments.repo if arguments.repo else workspace)
    record_path = task_record(repository, arguments.branch)
    if not record_path.exists():
        raise RuntimeError('No helper task record; finish only manages recorded task worktrees. '
                           'For an older task, explicitly register its clean checkout with setup --resume first.')
    record = json.loads(record_path.read_text())
    if not isinstance(record, dict) or not all(isinstance(record.get(key), str) for key in ('checkout', 'branch', 'base', 'starting_commit')):
        raise RuntimeError('Invalid task record; preserve it and inspect the conflict')
    checkout = Path(record['checkout']).resolve()
    if arguments.path and arguments.path.resolve() != checkout:
        raise RuntimeError('Requested path does not match task record')
    if checkout == main_checkout(repository) or checkout == main_workspace:
        raise RuntimeError('Cannot remove a main checkout')
    if Path.cwd().resolve().is_relative_to(checkout):
        raise RuntimeError('Run finish from outside the checkout being removed')
    fields = registered(repository).get(checkout, [])
    if f'branch refs/heads/{arguments.branch}' not in fields or record['branch'] != arguments.branch:
        raise RuntimeError('Registered worktree branch does not match task record')
    if any(field.startswith(('locked', 'prunable')) for field in fields):
        raise RuntimeError('Worktree is locked or prunable; preserve it and inspect registration')
    revision = git(checkout, 'rev-parse', 'HEAD').strip()
    destination = git(repository, 'rev-parse', '--verify', f'refs/heads/{record["base"]}^{{commit}}').strip()
    if subprocess.run(['git', '-C', str(repository), 'merge-base', '--is-ancestor', revision, destination]).returncode:
        raise RuntimeError(f'Task HEAD is not integrated into local {record["base"]}; merge and verify it first')
    nested = initialized_modules(checkout)
    with idle_builds([checkout, *nested]):
        preserved = []
        for root in (checkout, *nested):
            preserved.extend(check_clean(root))
        for owner in (checkout, *nested):
            for path in modules(owner):
                module = owner / path
                if not (module / '.git').exists() and module.is_dir() and any(module.iterdir()):
                    raise RuntimeError(f'Uninitialized submodule contains files; preserve them first: {module}')
                if (module / '.git').exists() and git(module, 'rev-parse', 'HEAD').strip() != git(owner, 'rev-parse', f'HEAD:{path}').strip():
                    raise RuntimeError(f'Submodule HEAD does not match the committed pin: {module}')
        for module in nested:
            if len(registered(module)) != 1:
                raise RuntimeError(f'Submodule has additional worktrees; preserve them first: {module}')
            # Refuse local branch commits not protected by the recorded pin or a remote ref.
            unpublished = git(module, 'rev-list', '--branches', '--tags', '--not', 'HEAD', '--remotes').strip()
            if unpublished:
                raise RuntimeError(f'Submodule contains unpublished branch or tag commits: {module}')
        sources = [path.absolute() for path in arguments.evidence]
        logs = checkout / 'build/agent-logs'
        if logs.is_dir():
            sources.append(logs.absolute())
        if not sources:
            raise RuntimeError('No verification evidence; provide --evidence <file-or-directory> or build/agent-logs')
        for source in sources:
            if not source.exists() or source.is_symlink():
                raise RuntimeError(f'Evidence is missing or a symbolic link: {source}')
            if source.is_dir() and (not any(p.is_file() for p in source.rglob('*')) or
                                    any(p.is_symlink() for p in source.rglob('*'))):
                raise RuntimeError(f'Evidence directory is empty or contains symbolic links: {source}')
        archive_root = main_workspace / 'build/agent-logs/worktree-completion'
        if any(source.is_dir() and archive_root.resolve().is_relative_to(source.resolve()) for source in sources):
            raise RuntimeError('Evidence source must not contain the completion archive destination')
        archive_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.archive-', dir=archive_root) as temporary:
            staged = Path(temporary)
            for index, source in enumerate(dict.fromkeys(sources)):
                target = staged / f'{index}-{source.name}'
                if source.is_dir():
                    shutil.copytree(source, target)
                else:
                    shutil.copy2(source, target)
            for source in preserved:
                target = staged / 'preserved-files' / source.relative_to(checkout)
                target.parent.mkdir(parents=True, exist_ok=True)
                if source.is_symlink():
                    target.symlink_to(os.readlink(source))
                else:
                    shutil.copy2(source, target)
            (staged / 'completion.json').write_text(json.dumps(dict(
                **record, task_commit=revision, integrated_commit=destination,
                evidence=[str(path) for path in sources],
                completed_at=datetime.now(timezone.utc).isoformat()), indent=2) + '\n')
            archive = archive_root / (re.sub(r'[^a-zA-Z0-9_.-]+', '-', arguments.branch) + '-' + staged.name[9:])
            staged.rename(archive)
        print(f'Verification evidence retained: {archive}', flush=True)
        check_clean(checkout)
        if git(checkout, 'rev-parse', 'HEAD').strip() != revision or git(repository, 'rev-parse', f'refs/heads/{record["base"]}').strip() != destination:
            raise RuntimeError('Task or integration commit changed during completion; archive retained, checkout preserved')
        # Git requires --force for worktrees with submodules even after deinit.
        # Use it only after checking all initialized modules and preserving user data.
        for module in nested:
            check_clean(module)
        removal = ['worktree', 'remove']
        if modules(checkout):
            removal.append('--force')
        git(repository, *removal, checkout, capture=False)
        record_path.unlink()
    print(f'Completed: {arguments.branch}; integrated into {record["base"]} at {destination}; removed {checkout}')
    return archive


def main(workspace, arguments):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    doctor = commands.add_parser('doctor', help='diagnose checkout readiness without fetching or building')
    doctor.add_argument('--path', type=Path, default=workspace, help='checkout to diagnose (default: entrypoint checkout)')
    completion = commands.add_parser('finish', help='archive evidence and remove a clean, already integrated task checkout')
    completion.add_argument('branch')
    completion.add_argument('--repo', type=Path, help='initialized submodule repository, relative to main workspace')
    completion.add_argument('--path', type=Path, help='assert the recorded checkout path')
    completion.add_argument('--evidence', action='append', type=Path, default=[], help='verification file/directory to retain; repeatable')
    parsed = parser.parse_args(arguments)
    try:
        if parsed.command == 'finish':
            finish(workspace, parsed)
            return 0
        messages = diagnose(parsed.path)
        for level, message in messages:
            print(f'{level}: {message}')
        return int(any(level == 'ERROR' for level, _ in messages))
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(f'Worktree {parsed.command}: {error}', file=sys.stderr)
        return 1

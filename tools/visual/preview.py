#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Run, reload and record an isolated addon visual preview before integration."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def inside(root, path):
    resolved = path.resolve()
    if not resolved.is_relative_to(root.resolve()):
        raise ValueError(f'Path escapes {root}: {path}')
    return resolved


def fingerprint(project):
    digest = hashlib.sha256()
    source = project / 'src/main/resources'
    files = sorted(source.rglob('*'))
    for file in files:
        if file.is_symlink():
            raise ValueError(f'Symlink is not a preview resource: {file}')
        if file.is_file():
            digest.update(file.relative_to(source).as_posix().encode() + b'\0')
            data = file.read_bytes()
            digest.update(len(data).to_bytes(8, 'big') + data)
    return digest.hexdigest()


def identity(root, project):
    return dict(checkout=str(root), revision=git(root, 'rev-parse', 'HEAD'),
                addonRevision=git(project, 'rev-parse', 'HEAD'), resources=fingerprint(project))


def reload_resources(project):
    source = project / 'src/main/resources'
    output = project / 'build/resources/main'
    if not source.is_dir() or not output.is_dir():
        raise ValueError('Build the addon first: resource source/output is missing')
    inside(project, output)
    copies = []
    for file in sorted(source.rglob('*')):
        if file.is_symlink():
            raise ValueError(f'Refusing symlink resource: {file}')
        if file.is_file():
            target = inside(output, output / file.relative_to(source))
            copies.append((file, target))
    for file, target in copies:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(file, target)
    return len(copies)


def evidence_path(directory, label, suffix):
    if not label or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in label):
        raise ValueError('Label must contain only letters, digits, hyphens or underscores')
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    return directory / f'{stamp}-{label}{suffix}'


def capture(root, project, directory, image, label, scenario):
    if image.is_symlink() or not image.is_file() or image.read_bytes()[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('Capture requires an existing Minecraft PNG screenshot')
    directory.mkdir(parents=True, exist_ok=True)
    target = evidence_path(directory, label, '.png')
    shutil.copy2(image, target)
    record = dict(identity(root, project), screenshot=target.name, scenario=scenario,
                  screenshotSha256=hashlib.sha256(target.read_bytes()).hexdigest(), approval=None)
    metadata = target.with_suffix('.json')
    metadata.write_text(json.dumps(record, indent=2) + '\n')
    return metadata


def approve(root, project, directory, review, note):
    review = inside(directory, review)
    record = json.loads(review.read_text())
    for key, value in identity(root, project).items():
        if record.get(key) != value:
            raise ValueError('Candidate changed since capture; capture and review it again')
    screenshot = inside(directory, review.parent / record['screenshot'])
    if hashlib.sha256(screenshot.read_bytes()).hexdigest() != record['screenshotSha256']:
        raise ValueError('Review screenshot changed')
    if not note.strip():
        raise ValueError('Record the explicit user approval text')
    approved = dict(record, approval=note, approvedAt=datetime.now(timezone.utc).isoformat())
    target = evidence_path(directory, 'approved', '.json')
    target.write_text(json.dumps(approved, indent=2) + '\n')
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--addon', default='create', help='Initialized addon directory under addons/ (default: create)')
    subs = parser.add_subparsers(dest='command', required=True)
    run = subs.add_parser('run', help='Launch isolated addon client; exit the game normally to release Gradle')
    run.add_argument('--world', help='Existing disposable world folder under this checkout run/client/saves')
    run.add_argument('--scenario', required=True, help='Initial state, actions and expected appearance')
    subs.add_parser('reload', help='Copy resources to runtime output; then press F3+T manually')
    shot = subs.add_parser('capture', help='Archive an F2 screenshot and exact candidate identity')
    shot.add_argument('--image', type=Path, help='PNG path; defaults to latest screenshot in the isolated client')
    shot.add_argument('--label', default='preview')
    shot.add_argument('--scenario', required=True)
    approval = subs.add_parser('approve', help='Record explicit user approval; rejects changed resources or revisions')
    approval.add_argument('--review', type=Path, required=True, help='Capture JSON path')
    approval.add_argument('--note', required=True, help='Exact explicit user approval; never infer it')
    subs.add_parser('status', help='Print current candidate identity and evidence location')
    args = parser.parse_args()
    try:
        if Path(args.addon).name != args.addon or args.addon in ('.', '..'):
            raise ValueError('Addon must be a directory name')
        project = inside(ROOT / 'addons', ROOT / 'addons' / args.addon)
        if not (project / 'build.gradle.kts').is_file() or not (project / '.git').exists():
            raise ValueError('Initialize the owning addon in the task worktree first')
        directory = ROOT / 'build/agent-logs/visual-preview' / args.addon
        client = project / 'run/client'
        if args.command == 'status':
            print(json.dumps(dict(identity(ROOT, project), evidence=str(directory)), indent=2))
        elif args.command == 'reload':
            print(f'Updated {reload_resources(project)} resources. Press F3+T in this preview client.')
            print('Deleted/renamed resources require stopping the client and rebuilding its outputs.')
        elif args.command == 'capture':
            images = sorted((client / 'screenshots').glob('*.png'), key=lambda p: p.stat().st_mtime_ns)
            image = args.image or (images[-1] if images else None)
            if image is None:
                raise ValueError('Press F2 in the isolated preview client first')
            print(capture(ROOT, project, directory, image, args.label, args.scenario))
        elif args.command == 'approve':
            print(approve(ROOT, project, directory, args.review, args.note))
        elif args.command == 'run':
            # Previewing in the user's normal checkout defeats the isolation contract.
            common = Path(git(ROOT, 'rev-parse', '--path-format=absolute', '--git-common-dir'))
            if ROOT == common.parent:
                raise ValueError('Run visual candidates in their task worktree, not the main checkout')
            directory.mkdir(parents=True, exist_ok=True)
            script = directory / 'client.gradle'
            arguments = []
            if args.world:
                world = inside(client / 'saves', client / 'saves' / args.world)
                if not (world / 'level.dat').is_file():
                    raise ValueError('World does not exist in this isolated client; create a disposable world first')
                arguments = ['--quickPlaySingleplayer', args.world]
            # JSON strings are valid Groovy string literals; escape $ to prevent interpolation.
            quoted = [json.dumps(a).replace('$', '\\$') for a in arguments]
            script.write_text('gradle.projectsEvaluated {\n'
                              '  if (rootProject.extensions.findByName("loom") != null) {\n'
                              '    rootProject.extensions.getByName("loom").runs.named("client") {\n' +
                              ('      programArgs ' + ', '.join(quoted) + '\n' if quoted else '') +
                              '    }\n  }\n}\n')
            (directory / 'session.json').write_text(json.dumps(dict(identity(ROOT, project),
                scenario=args.scenario, gameDirectory=str(client)), indent=2) + '\n')
            return subprocess.call([str(ROOT / 'gradlew-sandbox-dev-parallel'), '-p', str(project),
                                    '--init-script', str(script), 'runClient'], cwd=ROOT)
    except (ValueError, OSError, subprocess.CalledProcessError, KeyError) as error:
        print(f'Visual preview: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())

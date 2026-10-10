# The Compukters Developers
# Copyright 2026 Vsevolod Petrov (lazyhat)
# SPDX-License-Identifier: Apache-2.0
"""Select and verify complete same-commit CI evidence; never create tags or publish."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tomllib
from urllib.parse import urlencode
import zipfile

import release


def pages(api, endpoint, key, parameters=None):
    page = 1
    while True:
        query = dict(parameters or {}, per_page=100, page=page)
        items = api(f'{endpoint}?{urlencode(query)}')[key]
        yield from items
        if len(items) < 100:
            return
        page += 1


def find_source_run(api, repository, sha, current_run):
    runs = pages(api, f'repos/{repository}/actions/workflows/release.yml/runs', 'workflow_runs',
                 {'head_sha': sha, 'event': 'push', 'status': 'completed'})
    for run in runs:
        if (str(run['id']) == str(current_run) or run.get('head_sha') != sha
                or run.get('event') != 'push' or run.get('status') != 'completed'
                or (run.get('head_repository') or {}).get('full_name') != repository):
            continue
        jobs = list(pages(api, f"repos/{repository}/actions/runs/{run['id']}/jobs", 'jobs', {'filter': 'latest'}))
        verified = [job for job in jobs if job.get('name') == 'Verify and collect mod and addon JARs']
        if len(verified) != 1 or verified[0].get('status') != 'completed' or verified[0].get('conclusion') != 'success':
            continue
        artifacts = list(pages(api, f"repos/{repository}/actions/runs/{run['id']}/artifacts", 'artifacts'))
        matching = [a for a in artifacts if a.get('name') == f'mod-verified-{sha}'
                    and not a.get('expired', True)]
        if len(matching) != 1:
            continue
        provenance = matching[0].get('workflow_run') or {}
        if provenance.get('id') == run['id'] and provenance.get('head_sha') == sha:
            return str(run['id'])
    return ''


def identity(root):
    version = release.properties(root / 'gradle.properties')['version']
    release.require(re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', version),
                    'CI requires a canonical base version')
    return {'schema': 1, 'repository': release.REPOSITORY, 'version': version,
            'revision': release.git(root, 'rev-parse', 'HEAD'),
            'vm_revision': release.git(root / 'host/compukter-vm', 'rev-parse', 'HEAD'),
            'runtime_release': release.properties(root / 'config/runtime-release.properties')}


def validate_tag(root, tag):
    if tag:
        release.require(tag == 'v' + identity(root)['version'], 'tag does not match canonical base version')
        release.require(tag in release.git(root, 'tag', '--points-at', 'HEAD').splitlines(),
                        'checkout is not at requested tag')
        release.changelog(root, identity(root)['version'])


def effective_version(root):
    version = identity(root)['version']
    tags = release.git(root, 'tag', '--points-at', 'HEAD').splitlines()
    return version if any(tag in (version, 'v' + version) for tag in tags) else version + '-SNAPSHOT'


def expected_archives(root, effective):
    version = identity(root)['version']
    archives = [(f'compukters-{minecraft}-neoforge-{effective}.jar', minecraft)
                for minecraft, _, _ in release.TARGETS]
    line = '.'.join(version.split('.')[:2])
    for addon in release.ADDONS:
        addon_version = release.properties(root / 'addons' / addon / 'gradle.properties')['addonVersion']
        release.require(re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)', addon_version), 'invalid addon version')
        archives.append((f'compukters-{addon}-1.21.1-neoforge-{line}-{addon_version}.jar', '1.21.1'))
    return archives


def inspect_archives(root, directory, effective):
    expected = expected_archives(root, effective)
    records = []
    native = None
    tooling = None
    for index, (filename, _) in enumerate(expected):
        path = directory / filename
        hashes = release.digests(path)
        if index < len(release.TARGETS):
            component = release.inspect_bundled(path, release.TARGETS[index][2])
            selected = release.inspect_runtime(path)
            release.require(tooling is None or tooling == component, 'tooling differs across targets')
            release.require(native is None or native == selected, 'Runtime differs across targets')
            tooling, native = component, selected
            with zipfile.ZipFile(path) as archive:
                metadata = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode())
            mods = metadata.get('mods', [])
            release.require(len(mods) == 1 and mods[0].get('modId') == 'compukters'
                            and mods[0].get('version') == effective, 'CI archive product identity differs')
        else:
            addon = release.ADDONS[index - len(release.TARGETS)]
            addon_version = release.properties(root / 'addons' / addon / 'gradle.properties')['addonVersion']
            release.inspect_addon(path, addon, addon_version, identity(root)['version'])
        records.append({'filename': filename, 'bytes': path.stat().st_size, 'hashes': hashes})
    selected = identity(root)['runtime_release']
    release.require(native['version'] == selected['version'] and native['revision'] == selected['vmCommit'],
                    'CI archives differ from selected Runtime release')
    return records


def stage(root, output):
    release.require(not release.git(root, 'status', '--porcelain'), 'CI checkout is dirty')
    effective = effective_version(root)
    if effective == identity(root)['version']:
        release.prepare(root, output, 'v' + effective)
    else:
        release.require(not output.exists(), 'CI staging directory already exists')
        output.mkdir(parents=True)
        for filename, minecraft in expected_archives(root, effective):
            shutil.copyfile(root / 'dist' / minecraft / filename, output / filename)
    manifest = identity(root) | {'effective_version': effective,
                                'artifacts': inspect_archives(root, output, effective)}
    (output / 'verification.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def verify(root, directory):
    manifest = json.loads((directory / 'verification.json').read_text())
    current = identity(root)
    release.require(set(manifest) == set(current) | {'effective_version', 'artifacts'}, 'unknown CI evidence fields')
    release.require(all(manifest[key] == value for key, value in current.items()), 'CI evidence source identity differs')
    effective = manifest['effective_version']
    release.require(effective in (current['version'], current['version'] + '-SNAPSHOT'), 'unsupported CI archive version')
    release.require(manifest['artifacts'] == inspect_archives(root, directory, effective), 'CI archives changed after verification')
    if effective == current['version']:
        stable = release.load_release(directory)
        release.require(stable['revision'] == current['revision'] and stable['vm_revision'] == current['vm_revision'],
                        'release inventory source differs from CI evidence')
    return manifest


def runtime_directory(root):
    version = identity(root)['runtime_release']['version']
    release.require(re.fullmatch(r'0\.[1-9]\d*\.(0|[1-9]\d*)', version), 'invalid Runtime release version')
    home = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
    return home / 'caches/compukters/runtime' / version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['resolve', 'stage', 'verify', 'runtime-directory', 'mode'])
    parser.add_argument('--directory', type=Path, default=Path('build/verified'))
    args = parser.parse_args()
    root = Path.cwd()
    if args.command == 'resolve':
        tag = os.environ.get('RELEASE_TAG', '')
        validate_tag(root, tag)
        sha = identity(root)['revision']
        def api(endpoint):
            return json.loads(subprocess.check_output(['gh', 'api', endpoint], text=True))
        run = find_source_run(api, os.environ['GITHUB_REPOSITORY'], sha, os.environ['GITHUB_RUN_ID'])
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f'source_run={run}\nrevision={sha}\nrelease_tag={tag}\n')
        print(f'Reuse verified mod build from run {run}' if run else 'Run complete mod verification')
    elif args.command == 'stage':
        stage(root, args.directory)
    elif args.command == 'verify':
        manifest = verify(root, args.directory)
        if 'GITHUB_OUTPUT' in os.environ:
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                output.write(f"stable={str(manifest['effective_version'] == manifest['version']).lower()}\n")
        print(f"Verified {manifest['revision']} / {manifest['effective_version']}")
    elif args.command == 'runtime-directory':
        print(runtime_directory(root))
    else:
        print('release' if effective_version(root) == identity(root)['version'] else 'snapshot')


if __name__ == '__main__':
    main()

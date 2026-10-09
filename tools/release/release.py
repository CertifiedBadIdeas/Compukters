#!/usr/bin/env python3
# The Compukters Developers
# Copyright 2026 Vsevolod Petrov (lazyhat)
# SPDX-License-Identifier: Apache-2.0
"""Inventory and transfer already verified release outputs; never build or tag."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import tomllib
import urllib.error
import urllib.request
import uuid
import zipfile

PROJECT = 'xriOD3eh'
REPOSITORY = 'CertifiedBadIdeas/Compukters'
TARGETS = [('1.21.1', 'v1_21_1', 'jni'), ('26.1.2', 'v26_1', 'ffi')]
ADDONS = ['create', 'sable', 'propulsion']
TOOLING = 'tooling/workers/k2-tooling-workers'
MAX_ARTIFACT = 256 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def properties(path):
    return dict(tuple(part.strip() for part in line.split('=', 1)) for line in
                (row.strip() for row in path.read_text().splitlines())
                if line and not line.startswith('#') and '=' in line)


def digests(path):
    hashes = {name: hashlib.new(name) for name in ['sha256', 'sha512']}
    require(path.is_file() and not path.is_symlink(), f'not a regular artifact: {path}')
    require(0 < path.stat().st_size <= MAX_ARTIFACT, f'invalid artifact size: {path}')
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            for digest in hashes.values():
                digest.update(chunk)
    return {name: digest.hexdigest() for name, digest in hashes.items()}


def changelog(root, version):
    document = (root / 'docs/CHANGELOG.md').read_text()
    match = re.search(r'^## ' + re.escape(version) + r' — ([^\n]+)\n(.*?)(?=^## |\Z)',
                      document, re.M | re.S)
    require(match is not None, f'changelog section missing for {version}')
    require(match[1] != 'In development', 'release changelog is still In development')
    return match[2].strip() + '\n'


def inspect_bundled(path, transport):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'duplicate JAR entries')
        expected = [f'META-INF/natives/linux/x86_64/libcompukter_{transport}.so',
                    f'META-INF/natives/windows/x86_64/compukter_{transport}.dll',
                    TOOLING + '.bundle', TOOLING + '.zip.xz']
        require(all(name in names for name in expected), f'not an autonomous universal JAR: {path.name}')
        document = archive.read(TOOLING + '.bundle')
        require(len(document) <= 1024 * 1024, 'tooling manifest exceeds limit')
        identity = re.search(rb'^bundleSha256=([0-9a-f]{64})$', document, re.M)
        require(document.startswith(b'format=1\n') and identity, 'invalid tooling identity')
        carrier = hashlib.sha256()
        with archive.open(TOOLING + '.zip.xz') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                carrier.update(chunk)
        return {'bundle_sha256': identity[1].decode(),
                'carrier_sha256': carrier.hexdigest(),
                'manifest_sha256': hashlib.sha256(document).hexdigest(),
                'delivery': 'bundled'}


def prepare(root, output, tag):
    version = properties(root / 'gradle.properties')['version'].strip()
    require(re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', version),
            'only canonical stable release versions are supported')
    require(tag == 'v' + version and tag in git(root, 'tag', '--points-at', 'HEAD').splitlines(),
            'release requires the exact version tag at HEAD')
    require(not git(root, 'status', '--porcelain'), 'release checkout is dirty')
    statuses = git(root, 'submodule', 'status', '--recursive').splitlines()
    # git() strips the leading space of the first clean submodule line.
    require(all(not row.startswith(('-', '+', 'U')) for row in statuses), 'submodule checkout is not pinned and clean')
    notes = changelog(root, version)
    artifacts = []
    tooling = None
    for minecraft, _, transport in TARGETS:
        filename = f'compukters-{minecraft}-neoforge-{version}.jar'
        source = root / 'dist' / minecraft / filename
        component = inspect_bundled(source, transport)
        require(tooling is None or tooling == component, 'Minecraft targets have different tooling components')
        tooling = component
        artifacts.append({'filename': filename, 'source': source, 'minecraft': minecraft,
                          'loader': 'neoforge', 'distribution': 'bundled', 'bytes': source.stat().st_size,
                          'hashes': digests(source), 'version_number': f'{minecraft}-neoforge-{version}'})
    addons = []
    line = '.'.join(version.split('.')[:2])
    for addon in ADDONS:
        addon_version = properties(root / 'addons' / addon / 'gradle.properties')['addonVersion']
        require(re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)', addon_version), 'invalid addon version')
        filename = f'compukters-{addon}-1.21.1-neoforge-{line}-{addon_version}.jar'
        source = root / 'dist/1.21.1' / filename
        inspect_addon(source, addon, addon_version, version)
        addons.append({'addon': addon, 'version': addon_version, 'compukters_line': line,
                       'filename': filename, 'source': source, 'minecraft': '1.21.1', 'loader': 'neoforge',
                       'bytes': source.stat().st_size, 'hashes': digests(source)})
    require(not output.exists(), 'release staging directory already exists; use a fresh output directory')
    output.mkdir(parents=True)
    for artifact in artifacts + addons:
        shutil.copyfile(artifact.pop('source'), output / artifact['filename'])
    manifest = {'schema': 2, 'repository': REPOSITORY, 'modrinth_project': PROJECT,
                'tag': tag, 'version': version, 'revision': git(root, 'rev-parse', 'HEAD'),
                'vm_revision': git(root / 'host/compukter-vm', 'rev-parse', 'HEAD'),
                'components': {'tooling': tooling}, 'artifacts': artifacts, 'addons': addons}
    (output / 'release.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (output / 'release-notes.md').write_text(notes)
    files = sorted(path for path in output.iterdir() if path.is_file())
    (output / 'checksums.sha256').write_text(''.join(
        f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n' for path in files))
    return manifest


def inspect_addon(path, addon, addon_version, mod_version):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'duplicate addon JAR entries')
        require('META-INF/neoforge.mods.toml' in names
                and f'META-INF/compukters/addons/{addon}.cagb' in names, 'missing addon metadata or Guest bundle')
        metadata = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode())
        mod_id = 'compukters_' + addon
        mods = metadata.get('mods', [])
        require(len(mods) == 1 and mods[0].get('modId') == mod_id and mods[0].get('version') == addon_version,
                'addon identity/version mismatch')
        major, minor, _ = map(int, mod_version.split('.'))
        dependencies = metadata.get('dependencies', {}).get(mod_id, [])
        base = [entry for entry in dependencies if entry.get('modId') == 'compukters']
        require(len(base) == 1 and base[0].get('type') == 'required'
                and base[0].get('versionRange') == f'[{mod_version},{major}.{minor + 1}.0)',
                'addon Compukters dependency mismatch')


def load_release(directory):
    manifest = json.loads((directory / 'release.json').read_text())
    require(manifest['schema'] in (1, 2) and manifest['repository'] == REPOSITORY,
            'unsupported release manifest')
    require(manifest['modrinth_project'] == PROJECT, 'unexpected destination project')
    require(re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', manifest['version']),
            'invalid release version')
    require(manifest['tag'] == 'v' + manifest['version'], 'tag/version mismatch')
    require(len(manifest['artifacts']) == len(TARGETS), 'missing or extra release targets')
    for artifact, (minecraft, _, _) in zip(manifest['artifacts'], TARGETS):
        expected = f'compukters-{minecraft}-neoforge-{manifest["version"]}.jar'
        require(artifact['filename'] == expected and artifact['minecraft'] == minecraft
                and artifact['loader'] == 'neoforge' and artifact['distribution'] == 'bundled'
                and artifact['version_number'] == f'{minecraft}-neoforge-{manifest["version"]}',
                'unsupported artifact composition')
        path = directory / artifact['filename']
        require(digests(path) == artifact['hashes'] and path.stat().st_size == artifact['bytes'],
                f'artifact changed after staging: {path.name}')
    addons = manifest['addons'] if manifest['schema'] == 2 else []
    require(manifest['schema'] == 2 or 'addons' not in manifest, 'schema 1 cannot declare addons')
    require([a['addon'] for a in addons] == (ADDONS if manifest['schema'] == 2 else []),
            'missing or extra release addons')
    line = '.'.join(manifest['version'].split('.')[:2])
    for artifact in addons:
        addon, addon_version = artifact['addon'], artifact['version']
        require(re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)', addon_version), 'invalid addon version')
        require(artifact['filename'] == f'compukters-{addon}-1.21.1-neoforge-{line}-{addon_version}.jar'
                and artifact['minecraft'] == '1.21.1' and artifact['loader'] == 'neoforge'
                and artifact['compukters_line'] == line, 'unsupported addon composition')
        path = directory / artifact['filename']
        require(digests(path) == artifact['hashes'] and path.stat().st_size == artifact['bytes'],
                f'addon changed after staging: {path.name}')
        inspect_addon(path, addon, addon_version, manifest['version'])
    expected_files = sorted(['release.json', 'release-notes.md'] + [a['filename'] for a in manifest['artifacts'] + addons])
    expected_checksums = ''.join(f'{hashlib.sha256((directory / name).read_bytes()).hexdigest()}  {name}\n'
                                 for name in expected_files)
    require((directory / 'checksums.sha256').read_text() == expected_checksums, 'staged release checksum inventory differs')
    return manifest


class Modrinth:
    def __init__(self, token, base='https://api.modrinth.com/v2'):
        require(bool(token), 'MODRINTH_TOKEN is required for publication')
        self.token, self.base = token, base

    def request(self, method, path, data=None, content_type=None):
        headers = {'Authorization': self.token,
                   'User-Agent': 'Compukters-release/1 (https://github.com/' + REPOSITORY + ')'}
        if content_type:
            headers['Content-Type'] = content_type
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                body = response.read(2 * 1024 * 1024 + 1)
                require(len(body) <= 2 * 1024 * 1024, 'Modrinth response exceeds limit')
                return json.loads(body)
        except urllib.error.HTTPError as error:
            # Do not print response bodies, authorization headers, or the token.
            raise ValueError(f'Modrinth {method} {path} failed: HTTP {error.code}') from None

    def versions(self, project):
        return self.request('GET', '/project/' + project + '/version')

    def upload(self, metadata, path):
        boundary = 'compukters-' + uuid.uuid4().hex
        prefix = (f'--{boundary}\r\nContent-Disposition: form-data; name="data"\r\n'
                  'Content-Type: application/json\r\n\r\n').encode() + json.dumps(metadata).encode()
        file_header = (f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                       f'filename="{path.name}"\r\nContent-Type: application/java-archive\r\n\r\n').encode()
        return self.request('POST', '/version', prefix + file_header + path.read_bytes()
                            + f'\r\n--{boundary}--\r\n'.encode(),
                            'multipart/form-data; boundary=' + boundary)


def version_matches(version, artifact, notes):
    files = version.get('files', [])
    return (version.get('game_versions') == [artifact['minecraft']]
            and version.get('loaders') == [artifact['loader']]
            and version.get('version_type') == 'release'
            and version.get('status') == 'listed'
            and version.get('dependencies') == []
            and version.get('changelog') == notes
            and len(files) == 1 and files[0].get('primary') is True
            and files[0].get('filename') == artifact['filename']
            and files[0].get('size') == artifact['bytes']
            and files[0].get('hashes', {}).get('sha512') == artifact['hashes']['sha512'])


def publish_modrinth(directory, client):
    manifest = load_release(directory)
    notes = (directory / 'release-notes.md').read_text()
    existing = client.versions(manifest['modrinth_project'])
    # Preflight all conflicts before uploading any missing target.
    missing = []
    for artifact in manifest['artifacts']:
        matches = [version for version in existing if version['version_number'] == artifact['version_number']]
        require(len(matches) <= 1, f'duplicate Modrinth versions: {artifact["version_number"]}')
        if matches:
            require(version_matches(matches[0], artifact, notes),
                    f'conflicting Modrinth version: {artifact["version_number"]}')
            print(f'Already published: {artifact["version_number"]}')
        else:
            missing.append(artifact)
    for artifact in missing:
        metadata = {'project_id': manifest['modrinth_project'], 'version_number': artifact['version_number'],
                    'name': f'Compukters {manifest["version"]} / {artifact["minecraft"]} NeoForge',
                    'changelog': notes, 'dependencies': [], 'game_versions': [artifact['minecraft']],
                    'loaders': [artifact['loader']], 'version_type': 'release', 'featured': False,
                    'status': 'listed', 'file_parts': ['file'], 'primary_file': 'file'}
        created = client.upload(metadata, directory / artifact['filename'])
        require(created.get('version_number') == artifact['version_number']
                and created.get('project_id') == manifest['modrinth_project']
                and version_matches(created, artifact, notes), 'Modrinth upload response differs from staged release')
        print(f'Published: {artifact["version_number"]} ({created["id"]})')


class GitHub:
    def cli(self, *args):
        result = subprocess.run(['gh', *args], capture_output=True, text=True)
        require(result.returncode == 0, 'GitHub command failed: ' + ' '.join(args[:3]))
        return result.stdout

    def tag_revision(self, tag):
        reference = json.loads(self.cli('api', f'repos/{REPOSITORY}/git/ref/tags/{tag}'))['object']
        for _ in range(8):
            if reference['type'] == 'commit':
                return reference['sha']
            require(reference['type'] == 'tag', 'release tag does not point to a commit')
            reference = json.loads(self.cli('api', f'repos/{REPOSITORY}/git/tags/{reference["sha"]}'))['object']
        raise ValueError('release tag nesting exceeds limit')

    def get(self, tag):
        result = subprocess.run(['gh', 'api', f'repos/{REPOSITORY}/releases/tags/{tag}'],
                                capture_output=True, text=True)
        if result.returncode and 'HTTP 404' in result.stderr:
            return None
        require(result.returncode == 0, 'could not inspect GitHub release')
        return json.loads(result.stdout)

    def create(self, tag, title, notes):
        self.cli('release', 'create', tag, '--repo', REPOSITORY, '--verify-tag', '--draft',
                 '--title', title, '--notes-file', str(notes))

    def asset_digest(self, asset):
        # Older GitHub assets do not carry the server-calculated digest.
        digest = asset.get('digest')
        if digest and digest.startswith('sha256:'):
            return digest.removeprefix('sha256:')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'asset'
            with path.open('wb') as output:
                result = subprocess.run(['gh', 'api', '-H', 'Accept: application/octet-stream',
                                         f'repos/{REPOSITORY}/releases/assets/{asset["id"]}'],
                                        stdout=output, stderr=subprocess.PIPE)
            require(result.returncode == 0, 'could not verify existing GitHub asset')
            return digests(path)['sha256']

    def upload(self, tag, path):
        self.cli('release', 'upload', tag, str(path), '--repo', REPOSITORY)

    def finish(self, tag):
        self.cli('release', 'edit', tag, '--repo', REPOSITORY, '--draft=false', '--latest')


def publish_github(directory, client):
    manifest = load_release(directory)
    tag = manifest['tag']
    require(client.tag_revision(tag) == manifest['revision'], 'remote tag differs from verified release revision')
    notes = (directory / 'release-notes.md').read_text()
    title = f'Compukters {manifest["version"]}'
    files = {name: directory / name for name in
             ['release.json', 'release-notes.md', 'checksums.sha256']
             + [artifact['filename'] for artifact in manifest['artifacts'] + manifest.get('addons', [])]}
    existing = client.get(tag)
    if existing is None:
        client.create(tag, title, directory / 'release-notes.md')
        existing = client.get(tag)
    require(existing is not None and existing['tag_name'] == tag
            and existing['name'] == title and existing['body'] == notes,
            'GitHub release metadata differs from staged release')
    assets = existing['assets']
    require(len({asset['name'] for asset in assets}) == len(assets), 'duplicate GitHub assets')
    require(all(asset['name'] in files for asset in assets), 'unexpected existing GitHub release assets')
    # Check every existing asset before appending anything.
    for asset in assets:
        path = files[asset['name']]
        require(asset['size'] == path.stat().st_size
                and client.asset_digest(asset) == digests(path)['sha256'],
                f'conflicting GitHub asset: {path.name}')
    present = {asset['name'] for asset in assets}
    for name, path in files.items():
        if name not in present:
            client.upload(tag, path)
    # Never expose an incomplete draft after a partial upload.
    completed = client.get(tag)
    require(completed is not None and {asset['name'] for asset in completed['assets']} == set(files),
            'GitHub release asset set is incomplete')
    for asset in completed['assets']:
        path = files[asset['name']]
        require(asset['size'] == path.stat().st_size and client.asset_digest(asset) == digests(path)['sha256'],
                f'uploaded GitHub asset differs: {path.name}')
    if completed['draft']:
        client.finish(tag)
    print(f'Published GitHub release: {tag}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    prep = commands.add_parser('prepare')
    prep.add_argument('--root', type=Path, default=Path.cwd())
    prep.add_argument('--output', type=Path, required=True)
    prep.add_argument('--tag', required=True)
    for name in ['verify', 'modrinth', 'github']:
        commands.add_parser(name).add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            prepare(args.root.resolve(), args.output.resolve(), args.tag)
        elif args.command == 'verify':
            manifest = load_release(args.directory)
            print(f'Verified staged release {manifest["tag"]}: {len(manifest["artifacts"])} mod JARs, '
                  f'{len(manifest.get("addons", []))} addon JARs')
        elif args.command == 'github':
            publish_github(args.directory, GitHub())
        else:
            publish_modrinth(args.directory, Modrinth(os.environ.get('MODRINTH_TOKEN')))
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f'Release failed: {error}\n')


if __name__ == '__main__':
    main()

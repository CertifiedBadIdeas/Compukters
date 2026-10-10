#!/usr/bin/env python3
"""Stage and publish a tagged first-party addon using the downloaded Compukters SDK."""

import argparse
import json
import re
import shutil
import tomllib
import zipfile
from pathlib import Path

import release


def verify(directory):
    manifest = json.loads((directory / 'addon-release.json').read_text())
    release.require(manifest['schema'] == 1 and manifest['addon'] in release.ADDONS, 'unsupported addon release')
    release.require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', manifest['repository']), 'invalid repository')
    release.require(re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)', manifest['version'])
                    and manifest['tag'] == 'v' + manifest['version'], 'invalid addon version/tag')
    release.require(re.fullmatch(r'[0-9a-f]{40}', manifest['revision']), 'invalid addon revision')
    assets = manifest['artifacts']
    release.require(len(assets) == 3 and {a['role'] for a in assets} == {'production', 'development', 'guest'},
                    'incomplete addon artifacts')
    names = [a['filename'] for a in assets]
    release.require(len(set(names)) == len(names) and all(Path(n).name == n for n in names), 'invalid addon filename')
    for artifact in assets:
        path = directory / artifact['filename']
        release.require(path.stat().st_size == artifact['bytes'] and release.digests(path) == artifact['hashes'],
                        'addon release asset changed')
    production = directory / next(a['filename'] for a in assets if a['role'] == 'production')
    release.inspect_addon(production, manifest['addon'], manifest['version'], manifest['compukters_version'])
    guest = directory / next(a['filename'] for a in assets if a['role'] == 'guest')
    development = directory / next(a['filename'] for a in assets if a['role'] == 'development')
    inspect_dependencies(production, development, guest, manifest["addon"], manifest["version"])
    files = sorted(names + ['addon-release.json', 'release-notes.md'])
    checksums = ''.join(f'{release.digests(directory / name)["sha256"]}  {name}\n' for name in files)
    release.require((directory / 'checksums.sha256').read_text() == checksums, 'addon checksum inventory differs')
    return manifest


def inspect_dependencies(production, development, guest, addon, version):
    for jar in [production, development]:
        with zipfile.ZipFile(jar) as archive:
            release.require(len(archive.namelist()) == len(set(archive.namelist())), 'duplicate addon entries')
            release.require(archive.read(f'META-INF/compukters/addons/{addon}.cagb') == guest.read_bytes(),
                            'Guest bundle differs between release artifacts')
            metadata = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode())
            release.require(metadata['mods'][0]['version'] == version, 'addon metadata version differs')
            if jar == development:
                release.require(re.search(rb'(?mi)^Fabric-Loom-Remap: false\r?$',
                                          archive.read('META-INF/MANIFEST.MF')), 'development mod must use named mappings')


def prepare(root, output, addon, sdk, repository):
    release.require(addon in release.ADDONS, 'unknown first-party addon')
    version = release.properties(root / 'gradle.properties')['addonVersion']
    tag = 'v' + version
    release.require(tag in release.git(root, 'tag', '--points-at', 'HEAD').splitlines(), 'exact addon tag required at HEAD')
    release.require(not release.git(root, 'status', '--porcelain'), 'addon checkout is dirty')
    identity = release.properties(sdk / 'sdk.properties')
    mod_version = identity['modVersion']
    release.require(identity.get('format') == '1'
                    and re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', mod_version)
                    and re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', identity['sdkVersion']),
                    'invalid SDK metadata')
    release.require(identity['modBuildVersion'] == mod_version, 'tagged addon requires a stable Compukters SDK')
    line = '.'.join(mod_version.split('.')[:2])
    name = f'compukters-{addon}-1.21.1-neoforge-{line}-{version}.jar'
    production = root / 'build/distribution' / name
    release.inspect_addon(production, addon, version, mod_version)
    development = root / 'build/devlibs' / f'compukters-{addon}-development-mod.jar'
    guest = root / 'build/compukters-addon' / f'compukters-{addon}.cagb'
    inspect_dependencies(production, development, guest, addon, version)
    release.require(not output.exists(), 'addon staging directory already exists')
    output.mkdir(parents=True)
    artifacts = []
    for role, source in [('production', production), ('development', development), ('guest', guest)]:
        destination = output / source.name
        shutil.copyfile(source, destination)
        artifacts.append({'role': role, 'filename': source.name, 'bytes': source.stat().st_size,
                          'hashes': release.digests(source)})
    manifest = {'schema': 1, 'addon': addon, 'version': version, 'tag': tag, 'repository': repository,
                'revision': release.git(root, 'rev-parse', 'HEAD'), 'compukters_version': mod_version,
                'sdk_version': identity['sdkVersion'], 'artifacts': artifacts}
    (output / 'addon-release.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (output / 'release-notes.md').write_text(
        f'Compukters: {addon.title()} {version} for Minecraft 1.21.1 NeoForge.\n\n'
        f'Built against Compukters {mod_version}, addon SDK {identity["sdkVersion"]}.\n'
        'Includes the production mod, named development mod and Guest API bundle.\n')
    files = sorted(p for p in output.iterdir() if p.is_file())
    (output / 'checksums.sha256').write_text(''.join(f'{release.digests(p)["sha256"]}  {p.name}\n' for p in files))
    verify(output)
    return manifest


def publish(directory, client):
    manifest = verify(directory)
    files = {name: directory / name for name in ['addon-release.json', 'release-notes.md', 'checksums.sha256']
             + [artifact['filename'] for artifact in manifest['artifacts']]}
    release.publish_files(directory, client, manifest, files,
                          f'Compukters: {manifest["addon"].title()} {manifest["version"]}',
                          (directory / 'release-notes.md').read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    stage = sub.add_parser('prepare')
    stage.add_argument('--root', type=Path, required=True)
    stage.add_argument('--output', type=Path, required=True)
    stage.add_argument('--addon', choices=release.ADDONS, required=True)
    stage.add_argument('--sdk', type=Path, required=True)
    stage.add_argument('--repository', required=True)
    for command in ['verify', 'github']:
        sub.add_parser(command).add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            prepare(args.root.resolve(), args.output.resolve(), args.addon, args.sdk.resolve(), args.repository)
        elif args.command == 'verify':
            verify(args.directory)
        else:
            manifest = verify(args.directory)
            publish(args.directory, release.GitHub(manifest['repository']))
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f'Addon release failed: {error}\n')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Stage revision-specific Jekyll sources and compose release/nightly documentation."""

from __future__ import annotations

import argparse
import html
import json
from pathlib import Path
import re
import shutil
import subprocess
from urllib.parse import urlsplit, urlunsplit

VERSIONS = ('0.5.1', 'nightly')
ASSETS = Path(__file__).parent
ATTRIBUTE = re.compile(r'''(\b(?:href|src|action)\s*=\s*)(["'])(.*?)(\2)''', re.I)


def git(checkout: Path, *args: str) -> str:
    return subprocess.check_output(['git', '-C', str(checkout), *args], text=True).strip()


def prepare(checkout: Path, output: Path, version: str, baseurl: str) -> None:
    revision = git(checkout, 'rev-parse', 'HEAD')
    if version != 'nightly' and git(checkout, 'rev-parse', f'v{version}^{{commit}}') != revision:
        raise ValueError('Release documentation must use its exact version tag')
    source_rewrites = {}
    repository_url = 'https://github.com/CertifiedBadIdeas/Compukters'
    for addon in ('create', 'sable', 'propulsion'):
        pinned = git(checkout, 'ls-tree', 'HEAD', f'addons/{addon}').split()[2]
        if git(checkout / 'addons' / addon, 'rev-parse', 'HEAD') != pinned:
            raise ValueError(f'{addon} checkout does not match its pinned revision')
        remote = git(checkout, 'config', '-f', '.gitmodules', '--get', f'submodule.addons/{addon}.url')
        remote = remote.removesuffix('.git').replace('git@github.com:', 'https://github.com/')
        source_rewrites[f'{repository_url}/blob/dev/addons/{addon}/'] = f'{remote}/blob/{pinned}/'
    source_rewrites[f'{repository_url}/blob/dev/'] = f'{repository_url}/blob/{revision}/'
    if output.exists():
        raise ValueError(f'Staging destination already exists: {output}')
    shutil.copytree(checkout / 'docs', output, ignore=shutil.ignore_patterns('.jekyll-cache', '__pycache__'))
    config = output / '_config.yml'
    content = config.read_text()
    content, count = re.subn(r'^baseurl:.*$', f'baseurl: {json.dumps(baseurl + "/" + version)}', content, flags=re.M)
    if count != 1:
        raise ValueError('Expected one Jekyll baseurl')
    config.write_text(content)
    (output / '.documentation-version.json').write_text(json.dumps({
        'id': version, 'revision': revision, 'baseurl': baseurl,
        'source_rewrites': source_rewrites,
    }, indent=2) + '\n')
    print(f'Staged {version} documentation from {revision}')


def page_url(path: Path) -> str:
    url = '/' + path.as_posix()
    return url.removesuffix('index.html') if path.name == 'index.html' else url


def rewrite_url(value: str, metadata: dict) -> str:
    value = html.unescape(value)
    for prefix, replacement in metadata['source_rewrites'].items():
        if value.startswith(prefix):
            return replacement + value[len(prefix):]
    parsed = urlsplit(value)
    own_site = parsed.netloc == 'certifiedbadideas.github.io' and parsed.scheme in ('https', 'http', '')
    if parsed.netloc and not own_site:
        return value
    baseurl = metadata['baseurl']
    if parsed.path != baseurl and not parsed.path.startswith(baseurl + '/'):
        return value
    suffix = parsed.path[len(baseurl):]
    if any(suffix == '/' + version or suffix.startswith('/' + version + '/') for version in VERSIONS):
        return value
    path = baseurl + '/' + metadata['id'] + (suffix or '/')
    return urlunsplit(('', '', path, parsed.query, parsed.fragment))


def switcher(current: str, path: Path, inventories: dict, metadata: dict) -> str:
    links = []
    for version in VERSIONS:
        equivalent = path in inventories[version]
        target = metadata['baseurl'] + '/' + version + (page_url(path) if equivalent else '/')
        label = '0.5.1 · Released' if version == '0.5.1' else 'Nightly · Development'
        attr = ' aria-current="true"' if version == current else ''
        attr += ' data-equivalent-page' if equivalent else ''
        links.append(f'<li><a href="{html.escape(target, quote=True)}"{attr}>{label}</a></li>')
    label = '0.5.1' if current == '0.5.1' else 'Nightly'
    revision = metadata['revision']
    source = f'<a class="docs-revision" href="https://github.com/CertifiedBadIdeas/Compukters/commit/{revision}">{revision[:8]}</a>'
    status = 'Released' if current == '0.5.1' else 'Development'
    return (f'<nav class="docs-version-bar" aria-label="Documentation version">'
            f'<details class="docs-version-menu"><summary>Docs: {label}</summary><ul>{"".join(links)}</ul></details>'
            f'<span class="docs-version-status">{status} · {source}</span></nav>')


def decorate(content: str, current: str, path: Path, inventories: dict, metadata: dict) -> str:
    content = ATTRIBUTE.sub(lambda match: match[1] + match[2] + html.escape(rewrite_url(match[3], metadata), quote=True) + match[4], content)
    assets_url = metadata['baseurl'] + '/assets/'
    assets = (f'<link rel="stylesheet" href="{assets_url}docs-versions.css">'
              f'<script src="{assets_url}docs-versions.js" defer></script>')
    content, heads = re.subn(r'</head\s*>', assets + '</head>', content, count=1, flags=re.I)
    content, bodies = re.subn(r'(<body\b[^>]*>)', lambda match: match[0] + switcher(current, path, inventories, metadata), content, count=1, flags=re.I)
    if heads != 1 or bodies != 1:
        raise ValueError(f'Expected a complete HTML document: {current}/{path}')
    return content


def redirect(target: str) -> str:
    escaped = html.escape(target, quote=True)
    # JSON encoding is used only inside a script, never as shell escaping.
    return ('<!doctype html><html lang="en"><head><meta charset="utf-8">'
            '<title>Compukters documentation</title>'
            f'<link rel="canonical" href="{escaped}"><meta http-equiv="refresh" content="0;url={escaped}">'
            f'</head><body><a href="{escaped}">Open documentation</a>'
            f'<script>location.replace({json.dumps(target)} + location.search + location.hash);</script></body></html>')


def compose(sources: dict[str, Path], sites: dict[str, Path], output: Path) -> None:
    metadata = {version: json.loads((sources[version] / '.documentation-version.json').read_text()) for version in VERSIONS}
    if any(metadata[version]['id'] != version for version in VERSIONS):
        raise ValueError('Version metadata does not match the site')
    if len({item['baseurl'] for item in metadata.values()}) != 1:
        raise ValueError('Versions must share the same site baseurl')
    # Dokka also ships HTML navigation fragments consumed by JavaScript; these are not standalone pages.
    inventories = {version: {path.relative_to(sites[version]) for path in sites[version].rglob('*.html')
                             if re.search(r'<body\b', path.read_text(), re.I)} for version in VERSIONS}
    for version in VERSIONS:
        for required in ('index.html', 'guest-api/index.html', 'guest-api/guest/index.html', 'guest-api/create/index.html',
                         'guest-api/sable/index.html', 'guest-api/propulsion/index.html'):
            if Path(required) not in inventories[version]:
                raise ValueError(f'Missing {version} output: {required}')
    if output.exists():
        raise ValueError(f'Composition destination already exists: {output}')
    output.mkdir(parents=True)
    for version in VERSIONS:
        destination = output / version
        shutil.copytree(sites[version], destination)
        for target in destination.rglob('*.html'):
            path = target.relative_to(destination)
            content = target.read_text()
            if path in inventories[version]:
                content = decorate(content, version, path, inventories, metadata[version])
            else:
                content = ATTRIBUTE.sub(lambda match: match[1] + match[2] + html.escape(
                    rewrite_url(match[3], metadata[version]), quote=True) + match[4], content)
            target.write_text(content)
        (destination / 'version.json').write_text(json.dumps(metadata[version], indent=2) + '\n')
    baseurl = metadata['0.5.1']['baseurl']
    for path in inventories['0.5.1'] | inventories['nightly']:
        version = '0.5.1' if path in inventories['0.5.1'] else 'nightly'
        target = output / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(redirect(baseurl + '/' + version + page_url(path)))
    for name in ('docs-versions.css', 'docs-versions.js'):
        destination = output / 'assets' / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ASSETS / name, destination)
    (output / '.nojekyll').touch()
    print(f'Composed {len(inventories["0.5.1"])} release and {len(inventories["nightly"])} nightly HTML pages')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    stage = commands.add_parser('prepare')
    stage.add_argument('--checkout', type=Path, required=True)
    stage.add_argument('--output', type=Path, required=True)
    stage.add_argument('--version', choices=VERSIONS, required=True)
    stage.add_argument('--baseurl', default='/Compukters')
    build = commands.add_parser('compose')
    for version in ('release', 'nightly'):
        build.add_argument(f'--{version}-source', type=Path, required=True)
        build.add_argument(f'--{version}-site', type=Path, required=True)
    build.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'prepare':
        prepare(args.checkout.resolve(), args.output.resolve(), args.version, args.baseurl.rstrip('/'))
    else:
        compose({'0.5.1': args.release_source, 'nightly': args.nightly_source},
                {'0.5.1': args.release_site, 'nightly': args.nightly_site}, args.output)


if __name__ == '__main__':
    main()

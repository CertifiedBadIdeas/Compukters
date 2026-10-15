#!/usr/bin/env python3
# The Compukters Developers
# Copyright 2026 Vsevolod Petrov (lazyhat)
# SPDX-License-Identifier: Apache-2.0
"""Submit verified mod and addon JARs to CurseForge, with a durable upload journal."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request
import uuid
import zipfile

import release

PROJECT = 1528063
# Fixed reviewed destinations; inventories cannot redirect publication.
ADDONS = {
    'create': (1746054, 'Create', [('create', 328085)]),
    'createaddition': (1746058, 'CreateAddition', [('create', 328085), ('createaddition', 439890)]),
    'sable': (1746063, 'Sable', [('sable', 1312371)]),
    'propulsion': (1746068, 'Propulsion', [('create', 328085), ('create-propulsion-simulated', 1522736)]),
}
BASE = 'https://minecraft.curseforge.com'
TASK = 'curseforge-upload'
ENVIRONMENT = 'curseforge-upload'
ABSENT = 'Operator confirmed no CurseForge file'
ACCEPTED = 'CurseForge accepted file '


def positive_id(value):
    return type(value) is int and value > 0


def json_hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('CurseForge redirects are not permitted')


class CurseForge:
    def __init__(self, token):
        release.require(bool(token), 'CURSEFORGE_TOKEN is required for publication')
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, path, data=None, content_type=None):
        headers = {'X-Api-Token': self.token,
                   'User-Agent': 'Compukters-release/1 (https://github.com/' + release.REPOSITORY + ')'}
        if content_type:
            headers['Content-Type'] = content_type
        request = urllib.request.Request(BASE + path, data=data, headers=headers,
                                         method='POST' if data is not None else 'GET')
        try:
            with self.opener.open(request, timeout=120) as response:
                body = response.read(2 * 1024 * 1024 + 1)
                release.require(len(body) <= 2 * 1024 * 1024, 'response exceeds limit')
                return json.loads(body)
        except urllib.error.HTTPError as error:
            raise ValueError(f'CurseForge request failed: HTTP {error.code}') from None
        except (OSError, ValueError):
            # Neither response bodies nor transport exception text may expose credentials.
            raise ValueError('CurseForge request failed or returned an invalid response') from None

    def versions(self):
        result = self.request('/api/game/versions')
        release.require(isinstance(result, list) and all(isinstance(row, dict) for row in result),
                        'invalid CurseForge game versions response')
        return {row.get('name') for row in result if isinstance(row.get('name'), str)}

    def upload(self, project, metadata, path):
        release.require(project in {PROJECT} | {row[0] for row in ADDONS.values()},
                        'unreviewed CurseForge destination')
        boundary = 'compukters-' + uuid.uuid4().hex
        prefix = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n'
                  'Content-Type: application/json\r\n\r\n').encode() + json.dumps(metadata).encode()
        file_header = (f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                       f'filename="{path.name}"\r\nContent-Type: application/java-archive\r\n\r\n').encode()
        result = self.request(f'/api/projects/{project}/upload-file',
                              prefix + file_header + path.read_bytes() + f'\r\n--{boundary}--\r\n'.encode(),
                              'multipart/form-data; boundary=' + boundary)
        release.require(isinstance(result, dict) and positive_id(result.get('id')),
                        'CurseForge upload did not return a valid file ID; reconcile before retry')
        return result['id']


class Journal(release.GitHub):
    """GitHub Deployments retain intent even if a runner dies before receiving the file ID."""
    def api(self, path, body=None, paginate=False):
        args = ['gh', 'api', f'repos/{self.repository}/' + path]
        if paginate:
            args += ['--paginate', '--slurp']
        if body is not None:
            args += ['--method', 'POST', '--input', '-']
        result = subprocess.run(args, input=json.dumps(body) if body is not None else None,
                                capture_output=True, text=True)
        release.require(result.returncode == 0, 'GitHub CurseForge journal request failed')
        return json.loads(result.stdout)

    def entries(self, manifest):
        pages = self.api(f'deployments?ref={manifest["tag"]}&task={TASK}&per_page=100', paginate=True)
        return [entry for page in pages for entry in page]

    def statuses(self, entry):
        pages = self.api(f'deployments/{entry["id"]}/statuses?per_page=100', paginate=True)
        return [status for page in pages for status in page]

    def begin(self, manifest, payload):
        entry = self.api('deployments', {'ref': manifest['tag'], 'task': TASK,
                         'environment': ENVIRONMENT, 'auto_merge': False, 'required_contexts': [],
                         'production_environment': False, 'payload': payload,
                         'description': 'Submit ' + payload['filename']})
        release.require(positive_id(entry.get('id')) and entry.get('sha') == manifest['revision']
                        and entry.get('payload') == payload, 'GitHub upload intent differs from release')
        return entry

    def record(self, entry, state, description):
        self.api(f'deployments/{entry["id"]}/statuses',
                 {'state': state, 'description': description, 'auto_inactive': False})


def project_for(artifact):
    return ADDONS[artifact['addon']][0] if 'addon' in artifact else PROJECT


def metadata_for(manifest, artifact, notes):
    metadata = {'displayName': f'Compukters {manifest["version"]} / {artifact["minecraft"]} NeoForge',
                'changelog': notes, 'changelogType': 'markdown', 'releaseType': 'release',
                'gameVersionNames': [artifact['minecraft'], 'NeoForge', 'Client', 'Server']}
    if 'addon' in artifact:
        _, title, dependencies = ADDONS[artifact['addon']]
        metadata['displayName'] = (f'Compukters: {title} {artifact["version"]} / '
                                   f'{artifact["minecraft"]} NeoForge / Compukters {artifact["compukters_line"]}')
        metadata['relations'] = {'projects': [
            {'slug': slug, 'projectID': str(project), 'type': 'requiredDependency'}
            for slug, project in [('compukters', PROJECT)] + dependencies]}
    return metadata


def planned(directory, journal):
    manifest = release.load_release(directory)
    release.require(journal.tag_revision(manifest['tag']) == manifest['revision'],
                    'remote tag differs from verified release revision')
    notes = (directory / 'release-notes.md').read_text()
    entries = journal.entries(manifest)
    plans = []
    # Legacy inventories retain their original base-only publication contract.
    artifacts = manifest['artifacts'] + (manifest['addons'] if manifest['schema'] >= 5 else [])
    for artifact in artifacts:
        project = project_for(artifact)
        metadata = metadata_for(manifest, artifact, notes)
        payload = {'schema': 1, 'project': project, 'filename': artifact['filename'],
                   'sha256': artifact['hashes']['sha256'], 'bytes': artifact['bytes'],
                   'inventory_sha256': hashlib.sha256((directory / 'release.json').read_bytes()).hexdigest(),
                   'metadata_sha256': json_hash(metadata)}
        matches = [entry for entry in entries if isinstance(entry.get('payload'), dict)
                   and entry['payload'].get('project') == project
                   and entry['payload'].get('filename') == artifact['filename']]
        # All attempts must agree, including attempts explicitly reconciled as absent.
        for entry in matches:
            release.require(entry.get('sha') == manifest['revision'] and entry['payload'] == payload,
                            'conflicting CurseForge upload intent: ' + artifact['filename'])
        active = []
        for entry in matches:
            statuses = journal.statuses(entry)
            last = statuses[0] if statuses else {}
            if last.get('state') == 'failure' and last.get('description') == ABSENT:
                continue
            active.append((entry, last))
        release.require(len(active) <= 1, 'multiple unresolved CurseForge attempts: ' + artifact['filename'])
        entry, last = active[0] if active else (None, {})
        accepted = last.get('state') == 'success' and re.fullmatch(
            re.escape(ACCEPTED) + r'[1-9]\d*', last.get('description') or '')
        plans.append((artifact, metadata, payload, entry, bool(accepted)))
    return manifest, plans


def publish(directory, client, journal):
    manifest, plans = planned(directory, journal)
    # Reject every ambiguous attempt before uploading any target, even an earlier missing one.
    for artifact, _, payload, entry, accepted in plans:
        release.require(entry is None or accepted,
                        'Unresolved CurseForge upload for ' + artifact['filename']
                        + f'; inspect project {payload["project"]} and use reconcile before retry')
    missing = [plan for plan in plans if not plan[4]]
    if missing:
        names = client.versions()
        release.require(all(set(metadata['gameVersionNames']) <= names for _, metadata, _, _, _ in missing),
                        'CurseForge does not recognize a required Minecraft/loader/environment version')
    for artifact, metadata, payload, _, accepted in plans:
        if accepted:
            print('Already submitted to CurseForge: ' + artifact['filename'])
            continue
        entry = journal.begin(manifest, payload)
        # Any failure from this point intentionally leaves an unresolved durable intent.
        file_id = client.upload(payload['project'], metadata, directory / artifact['filename'])
        journal.record(entry, 'success', ACCEPTED + str(file_id))
        print(f'Submitted to CurseForge: {artifact["filename"]} (file {file_id}; approval is separate)')


def reconcile(directory, journal, filename, file_id=None, not_uploaded=False):
    release.require((positive_id(file_id) and not not_uploaded) or (file_id is None and not_uploaded),
                    'choose a positive file ID or --not-uploaded')
    _, plans = planned(directory, journal)
    matches = [plan for plan in plans if plan[0]['filename'] == filename]
    release.require(len(matches) == 1, 'artifact is not a publishable JAR in this inventory')
    _, _, _, entry, accepted = matches[0]
    release.require(entry is not None and not accepted, 'no unresolved upload intent for this artifact')
    journal.record(entry, 'failure' if not_uploaded else 'success',
                   ABSENT if not_uploaded else ACCEPTED + str(file_id))
    print('Recorded operator reconciliation for ' + filename)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    publish_parser = commands.add_parser('publish')
    publish_parser.add_argument('--directory', type=Path, required=True)
    recovery = commands.add_parser('reconcile', description='Record a manually verified upload outcome; never upload.')
    recovery.add_argument('--directory', type=Path, required=True)
    recovery.add_argument('--artifact', required=True)
    outcome = recovery.add_mutually_exclusive_group(required=True)
    outcome.add_argument('--file-id', type=int)
    outcome.add_argument('--not-uploaded', action='store_true')
    args = parser.parse_args()
    try:
        if args.command == 'publish':
            publish(args.directory, CurseForge(os.environ.get('CURSEFORGE_TOKEN')), Journal())
        else:
            reconcile(args.directory, Journal(), args.artifact, args.file_id, args.not_uploaded)
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile) as error:
        parser.exit(1, f'CurseForge transfer failed: {error}\n')


if __name__ == '__main__':
    main()

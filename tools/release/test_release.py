# The Compukters Developers
# Copyright 2026 Vsevolod Petrov (lazyhat)
# SPDX-License-Identifier: Apache-2.0
import copy
from email import policy
from email.parser import BytesParser
import io
import hashlib
import urllib.error
from unittest.mock import patch
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

import release


def command(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.DEVNULL, text=True)


def init_repository(root):
    root.mkdir(parents=True)
    command(root, 'init', '-q')
    command(root, 'config', 'user.name', 'Release test')
    command(root, 'config', 'user.email', 'release-test@example.invalid')


class PublicationFixture:
    def __init__(self, manifest, notes):
        self.existing = []
        self.uploads = []
        self.fail = False
        self.manifest, self.notes = manifest, notes

    def versions(self, project):
        assert project == release.PROJECT
        return self.existing

    def entry(self, artifact):
        return dict(id='fixture', project_id=release.PROJECT, version_number=artifact['version_number'],
                    game_versions=[artifact['minecraft']], loaders=['neoforge'], version_type='release',
                    status='listed', dependencies=[], changelog=self.notes,
                    files=[dict(primary=True, filename=artifact['filename'], size=artifact['bytes'],
                                hashes=artifact['hashes'])])

    def upload(self, metadata, path):
        if self.fail:
            raise ValueError('simulated upload failure')
        artifact = next(a for a in self.manifest['artifacts'] if a['filename'] == path.name)
        assert metadata['file_parts'] == ['file'] and metadata['primary_file'] == 'file'
        assert metadata['game_versions'] == [artifact['minecraft']]
        assert metadata['version_number'] == artifact['version_number']
        self.uploads.append(metadata)
        result = self.entry(artifact)
        self.existing.append(result)
        return result


class GitHubFixture:
    def __init__(self, manifest, directory):
        self.revision = manifest['revision']
        self.existing = None
        self.directory = directory
        self.uploads = []
        self.asset_bytes = {}
        self.finished = 0
        self.fail_after = None

    def tag_revision(self, tag):
        return self.revision

    def get(self, tag):
        return self.existing

    def create(self, tag, title, notes):
        self.existing = {'tag_name': tag, 'name': title, 'body': notes.read_text(),
                         'draft': True, 'assets': []}

    def asset_digest(self, asset):
        return hashlib.sha256(self.asset_bytes[asset['name']]).hexdigest()

    def upload(self, tag, path):
        if self.fail_after is not None and len(self.uploads) >= self.fail_after:
            raise ValueError('simulated GitHub failure')
        self.uploads.append(path.name)
        self.asset_bytes[path.name] = path.read_bytes()
        self.existing['assets'].append({'name': path.name, 'size': path.stat().st_size})

    def finish(self, tag):
        self.finished += 1
        self.existing['draft'] = False


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        base = Path(self.temporary.name)
        vm = base / 'vm'
        init_repository(vm)
        (vm / 'source').write_text('fixture')
        command(vm, 'add', '.')
        command(vm, 'commit', '-qm', 'VM fixture')
        self.root = base / 'project'
        init_repository(self.root)
        command(self.root, '-c', 'protocol.file.allow=always', 'submodule', 'add', '-q', str(vm), 'host/compukter-vm')
        (self.root / 'gradle.properties').write_text('version = 0.5.0\n')
        (self.root / '.gitignore').write_text('build\n')
        (self.root / 'docs').mkdir()
        (self.root / 'docs/CHANGELOG.md').write_text('## 0.5.0 — 2026-10-09\n\nNew capability.\n\n## 0.4.0 — 2026-09-12\n\nOld capability.\n')
        for minecraft, family, transport in release.TARGETS:
            path = self.root / f'modules/minecraft/{family}/{family}-neoforge/build/libs' / f'compukters-{minecraft}-neoforge-0.5.0.jar'
            path.parent.mkdir(parents=True)
            with zipfile.ZipFile(path, 'w') as archive:
                for platform, filename in [('linux', f'libcompukter_{transport}.so'), ('windows', f'compukter_{transport}.dll')]:
                    archive.writestr(f'META-INF/natives/{platform}/x86_64/{filename}', b'native fixture')
                archive.writestr(release.TOOLING + '.bundle', 'format=1\nbundleSha256=' + 'a' * 64 + '\n')
                archive.writestr(release.TOOLING + '.zip.xz', b'carrier fixture')
        command(self.root, 'add', '.')
        command(self.root, 'commit', '-qm', 'Release fixture')
        command(self.root, 'tag', 'v0.5.0')
        self.output = self.root / 'build/release'

    def prepared(self):
        manifest = release.prepare(self.root, self.output, 'v0.5.0')
        client = PublicationFixture(manifest, (self.output / 'release-notes.md').read_text())
        return manifest, client

    def test_inventory_records_both_targets_and_shared_components(self):
        manifest, _ = self.prepared()
        self.assertEqual(manifest, release.load_release(self.output))
        self.assertEqual([a['minecraft'] for a in manifest['artifacts']], ['1.21.1', '26.1.2'])
        self.assertEqual(manifest['components']['tooling']['delivery'], 'bundled')
        self.assertEqual((self.output / 'release-notes.md').read_text(), 'New capability.\n')
        self.assertEqual(len((self.output / 'checksums.sha256').read_text().splitlines()), 4)

    def test_wrong_tag_and_dirty_checkout_are_rejected_before_staging(self):
        with self.assertRaisesRegex(ValueError, 'exact version tag'):
            release.prepare(self.root, self.output, 'v0.4.0')
        (self.root / 'gradle.properties').write_text('version = 0.5.0\n# modified\n')
        with self.assertRaisesRegex(ValueError, 'dirty'):
            release.prepare(self.root, self.output, 'v0.5.0')
        self.assertFalse(self.output.exists())

    def test_unreleased_changelog_rejected(self):
        path = self.root / 'docs/CHANGELOG.md'
        path.write_text('## 0.5.0 — In development\n\nNext.\n')
        with self.assertRaisesRegex(ValueError, 'In development'):
            release.changelog(self.root, '0.5.0')

    def test_missing_native_or_tooling_rejected(self):
        for artifact in self.root.glob('modules/**/build/libs/*.jar'):
            with zipfile.ZipFile(artifact, 'w') as archive:
                archive.writestr('unrelated', b'data')
        with self.assertRaisesRegex(ValueError, 'autonomous universal'):
            release.prepare(self.root, self.output, 'v0.5.0')

    def test_modified_artifact_is_rejected_before_network(self):
        manifest, client = self.prepared()
        (self.output / manifest['artifacts'][0]['filename']).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'changed after staging'):
            release.publish_modrinth(self.output, client)
        self.assertEqual(client.uploads, [])

    def test_publication_and_repeat_are_idempotent(self):
        _, client = self.prepared()
        release.publish_modrinth(self.output, client)
        release.publish_modrinth(self.output, client)
        self.assertEqual(len(client.uploads), 2)

    def test_partial_publication_resumes_missing_target(self):
        manifest, client = self.prepared()
        client.existing = [client.entry(manifest['artifacts'][0])]
        release.publish_modrinth(self.output, client)
        self.assertEqual(len(client.uploads), 1)
        self.assertEqual(client.uploads[0]['game_versions'], ['26.1.2'])

    def test_conflict_on_second_target_prevents_all_uploads(self):
        manifest, client = self.prepared()
        conflict = client.entry(manifest['artifacts'][1])
        conflict['files'][0]['hashes'] = {'sha512': 'b' * 128}
        client.existing = [conflict]
        with self.assertRaisesRegex(ValueError, 'conflicting'):
            release.publish_modrinth(self.output, client)
        self.assertEqual(client.uploads, [])

    def test_metadata_conflicts_and_duplicate_versions_are_rejected(self):
        manifest, client = self.prepared()
        for field, value in [('loaders', ['forge']), ('game_versions', ['1.20.1']), ('status', 'draft')]:
            entry = copy.deepcopy(client.entry(manifest['artifacts'][0]))
            entry[field] = value
            client.existing = [entry]
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'conflicting'):
                release.publish_modrinth(self.output, client)
        entry = client.entry(manifest['artifacts'][0])
        client.existing = [entry, entry]
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            release.publish_modrinth(self.output, client)
        self.assertEqual(client.uploads, [])

    def test_github_publication_and_retry_preserve_assets(self):
        manifest, _ = self.prepared()
        client = GitHubFixture(manifest, self.output)
        release.publish_github(self.output, client)
        release.publish_github(self.output, client)
        self.assertEqual(len(client.uploads), 5)
        self.assertEqual(client.finished, 1)
        self.assertFalse(client.existing['draft'])

    def test_github_partial_upload_keeps_draft_and_retry_completes(self):
        manifest, _ = self.prepared()
        client = GitHubFixture(manifest, self.output)
        client.fail_after = 2
        with self.assertRaisesRegex(ValueError, 'GitHub failure'):
            release.publish_github(self.output, client)
        self.assertTrue(client.existing['draft'])
        self.assertEqual(client.finished, 0)
        client.fail_after = None
        release.publish_github(self.output, client)
        self.assertEqual(len(client.uploads), 5)
        self.assertEqual(client.finished, 1)

    def test_github_conflicting_asset_prevents_missing_uploads(self):
        manifest, _ = self.prepared()
        client = GitHubFixture(manifest, self.output)
        client.create(manifest['tag'], 'Compukters 0.5.0', self.output / 'release-notes.md')
        path = self.output / manifest['artifacts'][0]['filename']
        client.upload(manifest['tag'], path)
        client.asset_bytes[path.name] = b'x' * path.stat().st_size
        with self.assertRaisesRegex(ValueError, 'conflicting GitHub asset'):
            release.publish_github(self.output, client)
        self.assertEqual(len(client.uploads), 1)
        self.assertTrue(client.existing['draft'])

    def test_github_changed_remote_tag_is_rejected(self):
        manifest, _ = self.prepared()
        client = GitHubFixture(manifest, self.output)
        client.revision = '0' * 40
        with self.assertRaisesRegex(ValueError, 'remote tag differs'):
            release.publish_github(self.output, client)
        self.assertIsNone(client.existing)

    def test_changed_notes_are_rejected_before_publication(self):
        _, client = self.prepared()
        (self.output / 'release-notes.md').write_text('Changed release notes.')
        with self.assertRaisesRegex(ValueError, 'checksum inventory'):
            release.publish_modrinth(self.output, client)
        self.assertEqual(client.uploads, [])

    def test_multipart_upload_is_readable_by_a_standard_consumer(self):
        manifest, _ = self.prepared()
        path = self.output / manifest['artifacts'][0]['filename']
        metadata = {'project_id': release.PROJECT, 'file_parts': ['file'], 'primary_file': 'file'}
        def receive(request, timeout):
            self.assertEqual(request.method, 'POST')
            self.assertEqual(request.full_url, 'https://api.modrinth.com/v2/version')
            message = BytesParser(policy=policy.default).parsebytes(
                ('Content-Type: ' + request.get_header('Content-type') + '\r\n\r\n').encode() + request.data)
            parts = list(message.iter_parts())
            self.assertEqual(json.loads(parts[0].get_payload(decode=True)), metadata)
            self.assertEqual(parts[1].get_filename(), path.name)
            self.assertEqual(parts[1].get_payload(decode=True), path.read_bytes())
            self.assertEqual(parts[1].get_param('name', header='content-disposition'), 'file')
            return io.BytesIO(b'{"id":"created"}')
        with patch('urllib.request.urlopen', side_effect=receive):
            self.assertEqual(release.Modrinth('fixture-token').upload(metadata, path), {'id': 'created'})

    def test_http_failure_does_not_expose_credentials_or_response(self):
        failure = urllib.error.HTTPError('https://api.modrinth.com/v2/version', 401, 'denied', {}, io.BytesIO(b'private'))
        with patch('urllib.request.urlopen', side_effect=failure):
            with self.assertRaises(ValueError) as caught:
                release.Modrinth('fixture-secret').request('POST', '/version')
        self.assertEqual(str(caught.exception), 'Modrinth POST /version failed: HTTP 401')

    def test_upload_failure_is_propagated(self):
        _, client = self.prepared()
        client.fail = True
        with self.assertRaisesRegex(ValueError, 'upload failure'):
            release.publish_modrinth(self.output, client)


if __name__ == '__main__':
    unittest.main()

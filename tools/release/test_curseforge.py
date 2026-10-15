# The Compukters Developers
# Copyright 2026 Vsevolod Petrov (lazyhat)
# SPDX-License-Identifier: Apache-2.0
import copy
from email import policy
from email.parser import BytesParser
import io
import json
from pathlib import Path
import tempfile
from unittest.mock import patch
import unittest
import urllib.error

import curseforge
import release
import test_release as fixtures


class MemoryJournal:
    def __init__(self, manifest):
        self.manifest = manifest
        self.attempts = []
        self.history = {}
        self.fail_record = False

    def tag_revision(self, tag):
        return self.manifest['revision']

    def entries(self, manifest):
        return copy.deepcopy(self.attempts)

    def statuses(self, entry):
        return self.history.get(entry['id'], [])

    def begin(self, manifest, payload):
        entry = {'id': len(self.attempts) + 1, 'sha': manifest['revision'], 'payload': copy.deepcopy(payload)}
        self.attempts.append(entry)
        return entry

    def record(self, entry, state, description):
        if self.fail_record:
            raise ValueError('simulated journal failure')
        self.history.setdefault(entry['id'], []).insert(0, {'state': state, 'description': description})


class Uploads:
    def __init__(self):
        self.files = []
        self.projects = []
        self.fail_at = None
        self.available = {'1.21.1', '26.1.2', 'NeoForge', 'Client', 'Server'}

    def versions(self):
        return self.available

    def upload(self, project, metadata, path):
        self.projects.append(project)
        self.files.append((metadata, path.name, path.read_bytes()))
        if len(self.files) == self.fail_at:
            raise ValueError('simulated ambiguous upload')
        return len(self.files) + 100


class TransferTest(unittest.TestCase):
    setUp = fixtures.ReleaseTest.setUp

    def prepared(self):
        manifest = release.prepare(self.root, self.output, 'v0.5.0')
        return manifest, Uploads(), MemoryJournal(manifest)

    def test_submits_six_jars_to_five_projects_with_exact_metadata_and_skips_retry(self):
        manifest, client, journal = self.prepared()
        curseforge.publish(self.output, client, journal)
        curseforge.publish(self.output, client, journal)
        self.assertEqual(len(client.files), 6)
        self.assertEqual(client.projects, [1528063, 1528063, 1746054, 1746058, 1746063, 1746068])
        for (metadata, filename, data), artifact in zip(client.files, manifest['artifacts'] + manifest['addons']):
            self.assertEqual(filename, artifact['filename'])
            self.assertEqual(data, (self.output / filename).read_bytes())
            self.assertEqual(metadata['gameVersionNames'], [artifact['minecraft'], 'NeoForge', 'Client', 'Server'])
            self.assertEqual(metadata['releaseType'], 'release')
            self.assertEqual(metadata['changelogType'], 'markdown')
            self.assertEqual(metadata['changelog'], 'New capability.\n')
        self.assertEqual(len(journal.attempts), 6)

    def test_ambiguous_second_upload_blocks_retry_until_manually_reconciled(self):
        manifest, client, journal = self.prepared()
        client.fail_at = 2
        with self.assertRaisesRegex(ValueError, 'ambiguous'):
            curseforge.publish(self.output, client, journal)
        with self.assertRaisesRegex(ValueError, 'Unresolved'):
            curseforge.publish(self.output, client, journal)
        self.assertEqual(len(client.files), 2)
        curseforge.reconcile(self.output, journal, manifest['artifacts'][1]['filename'], file_id=202)
        curseforge.publish(self.output, client, journal)
        self.assertEqual(len(client.files), 6)

    def test_confirmed_absence_allows_new_attempt(self):
        manifest, client, journal = self.prepared()
        client.fail_at = 1
        with self.assertRaises(ValueError):
            curseforge.publish(self.output, client, journal)
        curseforge.reconcile(self.output, journal, manifest['artifacts'][0]['filename'], not_uploaded=True)
        client.fail_at = None
        curseforge.publish(self.output, client, journal)
        self.assertEqual(len(client.files), 7)
        self.assertEqual(len(journal.attempts), 7)

    def test_conflicting_later_intent_prevents_any_upload(self):
        _, client, journal = self.prepared()
        manifest, plans = curseforge.planned(self.output, journal)
        payload = copy.deepcopy(plans[1][2])
        payload['metadata_sha256'] = '0' * 64
        journal.begin(manifest, payload)
        with self.assertRaisesRegex(ValueError, 'conflicting'):
            curseforge.publish(self.output, client, journal)
        self.assertEqual(client.files, [])

    def test_unresolved_later_intent_prevents_uploading_earlier_missing_target(self):
        _, client, journal = self.prepared()
        manifest, plans = curseforge.planned(self.output, journal)
        journal.begin(manifest, plans[1][2])
        with self.assertRaisesRegex(ValueError, 'Unresolved'):
            curseforge.publish(self.output, client, journal)
        self.assertEqual(client.files, [])

    def test_addons_require_base_and_matching_upstream_projects(self):
        manifest, client, journal = self.prepared()
        curseforge.publish(self.output, client, journal)
        expected = {
            'create': [('compukters', 1528063), ('create', 328085)],
            'createaddition': [('compukters', 1528063), ('create', 328085), ('createaddition', 439890)],
            'sable': [('compukters', 1528063), ('sable', 1312371)],
            'propulsion': [('compukters', 1528063), ('create', 328085),
                           ('create-propulsion-simulated', 1522736)],
        }
        for (metadata, _, _), artifact in zip(client.files[2:], manifest['addons']):
            relations = metadata['relations']['projects']
            self.assertEqual([(r['slug'], r['projectID']) for r in relations], expected[artifact['addon']])
            self.assertTrue(all(r['type'] == 'requiredDependency' for r in relations))
            self.assertIn(artifact['version'], metadata['displayName'])
        self.assertTrue(all('relations' not in metadata for metadata, _, _ in client.files[:2]))

    def test_legacy_inventories_keep_base_only_submission_and_retry(self):
        self.prepared()
        for schema in [4, 3, 2, 1]:
            with self.subTest(schema=schema):
                manifest = fixtures.legacy_inventory(self.output, schema)
                client, journal = Uploads(), MemoryJournal(manifest)
                curseforge.publish(self.output, client, journal)
                curseforge.publish(self.output, client, journal)
                self.assertEqual(client.projects, [1528063, 1528063])
                self.assertEqual(len(journal.attempts), 2)

    def test_pending_last_addon_blocks_all_earlier_missing_uploads(self):
        _, client, journal = self.prepared()
        manifest, plans = curseforge.planned(self.output, journal)
        journal.begin(manifest, plans[-1][2])
        with self.assertRaisesRegex(ValueError, 'project 1746068'):
            curseforge.publish(self.output, client, journal)
        self.assertEqual(client.files, [])
        curseforge.reconcile(self.output, journal, manifest['addons'][-1]['filename'], file_id=999)
        curseforge.publish(self.output, client, journal)
        self.assertEqual(client.projects, [1528063, 1528063, 1746054, 1746058, 1746063])

    def test_string_relation_ids_require_confirmed_absence_before_numeric_retry(self):
        manifest, client, journal = self.prepared()
        _, plans = curseforge.planned(self.output, journal)
        artifact, metadata, payload, _, _ = plans[2]
        old_metadata = copy.deepcopy(metadata)
        for relation in old_metadata['relations']['projects']:
            relation['projectID'] = str(relation['projectID'])
        old_payload = dict(payload, metadata_sha256=curseforge.json_hash(old_metadata))
        entry = journal.begin(manifest, old_payload)
        # Neither an unresolved attempt nor an accepted one permits metadata changes.
        for status in [None, ('success', curseforge.ACCEPTED + '123')]:
            if status:
                journal.record(entry, *status)
            with self.assertRaisesRegex(ValueError, 'conflicting'):
                curseforge.publish(self.output, client, journal)
            self.assertEqual(client.files, [])
        journal.record(entry, 'failure', curseforge.ABSENT)
        curseforge.publish(self.output, client, journal)
        relations = client.files[2][0]['relations']['projects']
        self.assertTrue(all(type(r['projectID']) is int for r in relations))
        self.assertEqual(len(client.files), 6)
        curseforge.publish(self.output, client, journal)
        self.assertEqual(len(client.files), 6)
        # Confirmed absence never permits changing the JAR identity.
        entry['payload']['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'conflicting'):
            curseforge.planned(self.output, journal)

    def test_ambiguous_addon_upload_resumes_remaining_addons_only(self):
        manifest, client, journal = self.prepared()
        client.fail_at = 4
        with self.assertRaisesRegex(ValueError, r'compukters-createaddition-.*\(project 1746058\): .*ambiguous'):
            curseforge.publish(self.output, client, journal)
        with self.assertRaisesRegex(ValueError, 'project 1746058'):
            curseforge.publish(self.output, client, journal)
        self.assertEqual(len(client.files), 4)
        curseforge.reconcile(self.output, journal, manifest['addons'][1]['filename'], file_id=444)
        curseforge.publish(self.output, client, journal)
        self.assertEqual(client.projects, [1528063, 1528063, 1746054, 1746058, 1746063, 1746068])

    def test_journal_write_failure_prevents_upload(self):
        _, client, journal = self.prepared()
        with patch.object(journal, 'begin', side_effect=ValueError('journal unavailable')):
            with self.assertRaisesRegex(ValueError, 'journal unavailable'):
                curseforge.publish(self.output, client, journal)
        self.assertEqual(client.files, [])

    def test_receipt_failure_preserves_intent_and_prevents_duplicate(self):
        _, client, journal = self.prepared()
        journal.fail_record = True
        with self.assertRaisesRegex(ValueError, 'journal failure'):
            curseforge.publish(self.output, client, journal)
        journal.fail_record = False
        with self.assertRaisesRegex(ValueError, 'Unresolved'):
            curseforge.publish(self.output, client, journal)
        self.assertEqual(len(client.files), 1)

    def test_unknown_game_version_fails_before_intent_or_upload(self):
        _, client, journal = self.prepared()
        client.available.remove('26.1.2')
        with self.assertRaisesRegex(ValueError, 'does not recognize'):
            curseforge.publish(self.output, client, journal)
        self.assertEqual(client.files, [])
        self.assertEqual(journal.attempts, [])

    def test_tampered_staging_and_moved_tag_fail_before_upload(self):
        _, client, journal = self.prepared()
        with patch.object(journal, 'tag_revision', return_value='0' * 40):
            with self.assertRaisesRegex(ValueError, 'remote tag'):
                curseforge.publish(self.output, client, journal)
        (self.output / 'release-notes.md').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            curseforge.publish(self.output, client, journal)
        self.assertEqual(client.files, [])

    def test_reconcile_requires_one_pending_jar_and_positive_id(self):
        manifest, _, journal = self.prepared()
        filename = manifest['artifacts'][0]['filename']
        with self.assertRaisesRegex(ValueError, 'no unresolved'):
            curseforge.reconcile(self.output, journal, filename, file_id=123)
        for value in [0, -1, True]:
            with self.assertRaisesRegex(ValueError, 'positive'):
                curseforge.reconcile(self.output, journal, filename, file_id=value)
        with self.assertRaisesRegex(ValueError, 'not a publishable'):
            curseforge.reconcile(self.output, journal, 'addon.jar', file_id=123)

    def test_multiple_pending_intents_are_not_silently_reconciled(self):
        _, client, journal = self.prepared()
        manifest, plans = curseforge.planned(self.output, journal)
        journal.begin(manifest, plans[0][2])
        journal.begin(manifest, plans[0][2])
        with self.assertRaisesRegex(ValueError, 'multiple unresolved'):
            curseforge.publish(self.output, client, journal)
        self.assertEqual(client.files, [])


class ApiTest(unittest.TestCase):
    def test_requires_token(self):
        with self.assertRaisesRegex(ValueError, 'CURSEFORGE_TOKEN'):
            curseforge.CurseForge('')

    def test_author_upload_multipart_and_authentication(self):
        client = curseforge.CurseForge('secret-fixture')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'mod.jar'
            path.write_bytes(b'jar fixture')
            with patch.object(client.opener, 'open') as opened:
                opened.return_value.__enter__.return_value.read.return_value = b'{"id": 42}'
                self.assertEqual(client.upload(curseforge.PROJECT, {'releaseType': 'release'}, path), 42)
            request = opened.call_args.args[0]
            self.assertEqual(request.full_url, curseforge.BASE + '/api/projects/1528063/upload-file')
            self.assertEqual(request.get_header('X-api-token'), 'secret-fixture')
            self.assertEqual(request.get_method(), 'POST')
            message = BytesParser(policy=policy.default).parsebytes(
                ('Content-Type: ' + request.get_header('Content-type') + '\r\n\r\n').encode() + request.data)
            metadata, file = list(message.iter_parts())
            self.assertEqual(metadata.get_param('name', header='content-disposition'), 'metadata')
            self.assertEqual(json.loads(metadata.get_payload(decode=True)), {'releaseType': 'release'})
            self.assertEqual(file.get_filename(), 'mod.jar')
            self.assertEqual(file.get_payload(decode=True), b'jar fixture')

    def test_addon_upload_uses_exact_project_endpoint(self):
        client = curseforge.CurseForge('fixture')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'addon.jar'
            path.write_bytes(b'addon')
            for project in [1746054, 1746058, 1746063, 1746068]:
                with patch.object(client, 'request', return_value={'id': 42}) as request:
                    self.assertEqual(client.upload(project, {}, path), 42)
                    self.assertEqual(request.call_args.args[0], f'/api/projects/{project}/upload-file')
            with patch.object(client, 'request') as request:
                with self.assertRaisesRegex(ValueError, 'unreviewed'):
                    client.upload(123, {}, path)
                request.assert_not_called()

    def test_http_and_transport_errors_never_expose_token_or_response(self):
        client = curseforge.CurseForge('secret-fixture')
        errors = [urllib.error.HTTPError(curseforge.BASE, 401, 'secret-fixture', {}, io.BytesIO(b'secret-fixture')),
                  urllib.error.URLError('secret-fixture')]
        for error in errors:
            with patch.object(client.opener, 'open', side_effect=error) as opened:
                with self.assertRaises(ValueError) as caught:
                    client.versions()
                self.assertNotIn('secret-fixture', str(caught.exception))
                self.assertEqual(opened.call_count, 1)

    def test_redirects_are_rejected(self):
        with self.assertRaises(ValueError):
            curseforge.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.invalid')

    def test_api_validation_message_is_bounded_and_redacts_credentials(self):
        client = curseforge.CurseForge('secret-fixture')
        body = json.dumps({'errorMessage': 'Invalid relation\nsecret-fixture ' + 'x' * 1000,
                           'debug': 'private-response-data'}).encode()
        error = urllib.error.HTTPError(curseforge.BASE, 400, 'ignored', {}, io.BytesIO(body))
        with patch.object(client.opener, 'open', side_effect=error) as opened:
            with self.assertRaises(ValueError) as caught:
                client.versions()
        message = str(caught.exception)
        self.assertIn('HTTP 400: Invalid relation [REDACTED]', message)
        self.assertNotIn('secret-fixture', message)
        self.assertNotIn('private-response-data', message)
        self.assertNotIn('\n', message)
        self.assertLess(len(message), 600)
        self.assertEqual(opened.call_count, 1)

    def test_unstructured_and_oversized_error_bodies_are_not_logged(self):
        client = curseforge.CurseForge('secret-fixture')
        for body in [b'<html>private-response-data</html>', b'[]',
                     json.dumps({'message': 'private-response-data' * 1000}).encode()]:
            error = urllib.error.HTTPError(curseforge.BASE, 400, 'ignored', {}, io.BytesIO(body))
            with patch.object(client.opener, 'open', side_effect=error):
                with self.assertRaisesRegex(ValueError, '^CurseForge request failed: HTTP 400$'):
                    client.versions()

    def test_invalid_upload_id_is_not_success(self):
        client = curseforge.CurseForge('fixture')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'mod.jar'
            path.write_bytes(b'jar')
            for response in [{}, {'id': True}, {'id': 0}, {'id': '42'}]:
                with patch.object(client, 'request', return_value=response):
                    with self.assertRaisesRegex(ValueError, 'valid file ID'):
                        client.upload(curseforge.PROJECT, {}, path)

    def test_deployment_contract_and_pagination(self):
        journal = curseforge.Journal()
        manifest = {'tag': 'v0.6.0', 'revision': 'a' * 40}
        payload = {'filename': 'mod.jar', 'project': curseforge.PROJECT}
        result = {'id': 1, 'sha': manifest['revision'], 'payload': payload}
        with patch('curseforge.subprocess.run') as run:
            run.return_value.returncode = 0
            run.return_value.stdout = json.dumps(result)
            journal.begin(manifest, payload)
            body = json.loads(run.call_args.kwargs['input'])
            self.assertFalse(body['auto_merge'])
            self.assertEqual(body['required_contexts'], [])
            self.assertEqual(body['payload'], payload)
            self.assertEqual(body['task'], curseforge.TASK)
            run.return_value.stdout = '{}'
            journal.record(result, 'success', 'CurseForge accepted file 123')
            self.assertFalse(json.loads(run.call_args.kwargs['input'])['auto_inactive'])
            run.return_value.stdout = json.dumps([[result], []])
            self.assertEqual(journal.entries(manifest), [result])
            self.assertIn('--paginate', run.call_args.args[0])
            self.assertIn('--slurp', run.call_args.args[0])


if __name__ == '__main__':
    unittest.main()

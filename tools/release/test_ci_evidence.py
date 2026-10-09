# The Compukters Developers
# Copyright 2026 Vsevolod Petrov (lazyhat)
# SPDX-License-Identifier: Apache-2.0
import json
import unittest
import zipfile

import ci
import release
import test_release as fixtures


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        fixture = fixtures.ReleaseTest('runTest')
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.root = fixture.root
        self.output = self.root / 'build/verified'
        fixtures.command(self.root, 'tag', '-d', 'v0.5.0')
        config = self.root / 'config/runtime-release.properties'
        config.parent.mkdir()
        config.write_text('version=0.21.2\nvmCommit=' + 'b' * 40 + '\n')
        fixtures.command(self.root, 'add', '.')
        fixtures.command(self.root, 'commit', '-qm', 'CI identity fixture')
        for minecraft, _, _ in release.TARGETS:
            stable = self.root / 'dist' / minecraft / f'compukters-{minecraft}-neoforge-0.5.0.jar'
            with zipfile.ZipFile(stable) as archive:
                entries = {name: archive.read(name) for name in archive.namelist()}
            entries['META-INF/neoforge.mods.toml'] = b'[[mods]]\nmodId="compukters"\nversion="0.5.0"\n'
            with zipfile.ZipFile(stable, 'w') as archive:
                for name, value in entries.items():
                    archive.writestr(name, value)
            snapshot = stable.with_name(stable.name.replace('0.5.0.jar', '0.5.0-S.jar'))
            entries['META-INF/neoforge.mods.toml'] = b'[[mods]]\nmodId="compukters"\nversion="0.5.0-S"\n'
            with zipfile.ZipFile(snapshot, 'w') as archive:
                for name, value in entries.items():
                    archive.writestr(name, value)

    def test_snapshot_evidence_can_be_reused_after_tag_on_same_commit(self):
        manifest = ci.stage(self.root, self.output)
        self.assertEqual('0.5.0-S', manifest['effective_version'])
        self.assertEqual(5, len(manifest['artifacts']))
        fixtures.command(self.root, 'tag', 'v0.5.0')
        ci.validate_tag(self.root, 'v0.5.0')
        self.assertEqual(manifest, ci.verify(self.root, self.output))
        self.assertFalse((self.output / 'release.json').exists())

    def test_tagged_evidence_contains_verified_stable_release(self):
        fixtures.command(self.root, 'tag', 'v0.5.0')
        manifest = ci.stage(self.root, self.output)
        self.assertEqual('0.5.0', manifest['effective_version'])
        self.assertEqual(manifest, ci.verify(self.root, self.output))
        self.assertEqual(manifest['revision'], release.load_release(self.output)['revision'])

    def test_other_source_or_runtime_selection_is_rejected(self):
        ci.stage(self.root, self.output)
        proof = self.output / 'verification.json'
        original = json.loads(proof.read_text())
        for field in ['revision', 'vm_revision', 'version', 'repository', 'runtime_release']:
            changed = dict(original, **{field: 'other'})
            proof.write_text(json.dumps(changed))
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'source identity'):
                ci.verify(self.root, self.output)

    def test_missing_and_modified_archive_is_rejected(self):
        manifest = ci.stage(self.root, self.output)
        path = self.output / manifest['artifacts'][0]['filename']
        original = path.read_bytes()
        path.unlink()
        with self.assertRaisesRegex(ValueError, 'regular artifact'):
            ci.verify(self.root, self.output)
        path.write_bytes(original + b'changed')
        with self.assertRaisesRegex(ValueError, 'changed after verification'):
            ci.verify(self.root, self.output)

    def test_unsupported_snapshot_identity_and_unknown_fields_are_rejected(self):
        ci.stage(self.root, self.output)
        proof = self.output / 'verification.json'
        original = json.loads(proof.read_text())
        for change in [{'effective_version': '0.5.0-RC'}, {'extra': True}]:
            proof.write_text(json.dumps(original | change))
            with self.subTest(change=change), self.assertRaises(ValueError):
                ci.verify(self.root, self.output)

    def test_archive_metadata_must_match_snapshot_name(self):
        path = self.root / 'dist/1.21.1/compukters-1.21.1-neoforge-0.5.0-S.jar'
        stable = path.with_name(path.name.replace('-S.jar', '.jar'))
        path.write_bytes(stable.read_bytes())
        with self.assertRaisesRegex(ValueError, 'product identity'):
            ci.stage(self.root, self.output)

    def test_tag_identity_does_not_accept_another_version_or_untagged_head(self):
        with self.assertRaisesRegex(ValueError, 'requested tag'):
            ci.validate_tag(self.root, 'v0.5.0')
        fixtures.command(self.root, 'tag', 'v0.5.0')
        for tag in ['main', 'v0.5.1', 'v0.6.0', 'v0.5.0\n']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                ci.validate_tag(self.root, tag)


if __name__ == '__main__':
    unittest.main()

#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('preview', Path(__file__).with_name('preview.py'))
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)


class PreviewTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.project = self.root / 'addons/create'
        self.source = self.project / 'src/main/resources'
        self.output = self.project / 'build/resources/main'
        self.source.mkdir(parents=True)
        self.output.mkdir(parents=True)

    def test_reload_updates_nested_assets_without_touching_unrelated_outputs(self):
        (self.source / 'assets').mkdir()
        (self.source / 'assets/pump.json').write_text('candidate')
        (self.output / 'other.txt').write_text('retain')
        self.assertEqual(1, preview.reload_resources(self.project))
        self.assertEqual('candidate', (self.output / 'assets/pump.json').read_text())
        self.assertEqual('retain', (self.output / 'other.txt').read_text())

    def test_reload_rejects_destination_symlink_escape_before_any_copy(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (self.source / 'a.txt').write_text('must not copy')
        (self.source / 'z').mkdir()
        (self.source / 'z/asset').write_text('candidate')
        (self.output / 'z').symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError):
            preview.reload_resources(self.project)
        self.assertFalse((self.output / 'a.txt').exists())
        self.assertFalse((outside / 'asset').exists())

    def test_fingerprint_detects_additions_edits_and_deletions(self):
        empty = preview.fingerprint(self.project)
        p = self.source / 'model.json'
        p.write_text('first')
        first = preview.fingerprint(self.project)
        self.assertNotEqual(empty, first)
        p.write_text('second')
        self.assertNotEqual(first, preview.fingerprint(self.project))
        p.unlink()
        self.assertEqual(empty, preview.fingerprint(self.project))

    def test_capture_and_approval_reject_stale_candidate_and_changed_screenshot(self):
        evidence = self.root / 'evidence'
        image = self.root / 'screen.png'
        image.write_bytes(b'\x89PNG\r\n\x1a\nrecorded')
        state = dict(revision='candidate', resources='first')
        with patch.object(preview, 'identity', return_value=state):
            review = preview.capture(self.root, self.project, evidence, image, 'front', 'Observe corners')
            approved = preview.approve(self.root, self.project, evidence, review, 'User: looks good')
            self.assertEqual('User: looks good', json.loads(approved.read_text())['approval'])
            state['resources'] = 'edited'
            with self.assertRaises(ValueError):
                preview.approve(self.root, self.project, evidence, review, 'old approval')
            state['resources'] = 'first'
            (evidence / json.loads(review.read_text())['screenshot']).write_bytes(b'tampered')
            with self.assertRaises(ValueError):
                preview.approve(self.root, self.project, evidence, review, 'old approval')

    def test_run_preserves_world_spaces_and_escapes_groovy_interpolation(self):
        (self.project / 'build.gradle.kts').touch()
        (self.project / '.git').touch()
        name = 'Pump $preview "world"'
        world = self.project / 'run/client/saves' / name
        world.mkdir(parents=True)
        (world / 'level.dat').touch()
        with patch.object(preview, 'ROOT', self.root), \
                patch.object(preview, 'git', return_value=str(self.root.parent / '.git')), \
                patch.object(preview, 'identity', return_value={}), \
                patch.object(preview.sys, 'argv', ['visual-preview', 'run', '--world', name, '--scenario', 'Inspect rings']), \
                patch.object(preview.subprocess, 'call', return_value=0) as runner:
            self.assertEqual(0, preview.main())
        command = runner.call_args.args[0]
        script = Path(command[command.index('--init-script') + 1])
        self.assertTrue(script.is_absolute())
        self.assertIn('\\$preview', script.read_text())
        self.assertEqual('runClient', command[-1])

    def test_evidence_label_cannot_escape_directory(self):
        with self.assertRaises(ValueError):
            preview.evidence_path(self.root, '../escape', '.png')


if __name__ == '__main__':
    unittest.main()

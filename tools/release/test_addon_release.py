import tempfile
import unittest
import zipfile
from pathlib import Path

import addon_release
from test_release import GitHubFixture, command, init_repository


class AddonReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / 'addon'
        init_repository(self.root)
        (self.root / 'gradle.properties').write_text('addonVersion=2.0\n')
        (self.root / '.gitignore').write_text('build/\n')
        command(self.root, 'add', '.')
        command(self.root, 'commit', '-qm', 'addon fixture')
        command(self.root, 'tag', 'v2.0')
        self.sdk = Path(self.temporary.name) / 'sdk'
        self.sdk.mkdir()
        (self.sdk / 'sdk.properties').write_text('format=1\nsdkVersion=0.5.0\nmodVersion=0.6.0\nmodBuildVersion=0.6.0\n')
        metadata = ('[[mods]]\nmodId="compukters_create"\nversion="2.0"\n'
                    '[[dependencies.compukters_create]]\nmodId="compukters"\ntype="required"\n'
                    'versionRange="[0.6.0,0.7.0)"\n')
        for relative in ['build/distribution/compukters-create-1.21.1-neoforge-0.6-2.0.jar',
                         'build/devlibs/compukters-create-development-mod.jar']:
            jar = self.root / relative
            jar.parent.mkdir(parents=True)
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('META-INF/neoforge.mods.toml', metadata)
                archive.writestr('META-INF/compukters/addons/create.cagb', b'Guest bundle')
                archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nFabric-Loom-Remap: false\r\n\r\n')
        bundle = self.root / 'build/compukters-addon/compukters-create.cagb'
        bundle.parent.mkdir(parents=True)
        bundle.write_bytes(b'Guest bundle')
        self.output = self.root / 'build/release'

    def prepare(self):
        return addon_release.prepare(self.root, self.output, 'create', self.sdk, 'CertifiedBadIdeas/Compukers-create')

    def test_release_contains_production_and_development_dependencies(self):
        manifest = self.prepare()
        self.assertEqual(manifest, addon_release.verify(self.output))
        self.assertEqual({a['role'] for a in manifest['artifacts']}, {'production', 'development', 'guest'})
        client = GitHubFixture(manifest, self.output)
        addon_release.publish(self.output, client)
        self.assertEqual(len(client.uploads), 6)
        self.assertFalse(client.existing['draft'])
        addon_release.publish(self.output, client)
        self.assertEqual(len(client.uploads), 6)

    def test_snapshot_sdk_and_untagged_checkout_are_rejected(self):
        command(self.root, 'tag', '-d', 'v2.0')
        with self.assertRaisesRegex(ValueError, 'exact addon tag'):
            self.prepare()
        command(self.root, 'tag', 'v2.0')
        (self.sdk / 'sdk.properties').write_text('format=1\nsdkVersion=0.5.0\nmodVersion=0.6.0\nmodBuildVersion=0.6.0-SNAPSHOT\n')
        with self.assertRaisesRegex(ValueError, 'stable Compukters SDK'):
            self.prepare()

    def test_mismatched_guest_bundle_prevents_staging(self):
        (self.root / 'build/compukters-addon/compukters-create.cagb').write_bytes(b'wrong Guest bundle')
        with self.assertRaisesRegex(ValueError, 'Guest bundle differs'):
            self.prepare()
        self.assertFalse(self.output.exists())

    def test_partial_upload_can_resume_without_rebuilding(self):
        manifest = self.prepare()
        client = GitHubFixture(manifest, self.output)
        client.fail_after = 2
        with self.assertRaisesRegex(ValueError, 'simulated GitHub failure'):
            addon_release.publish(self.output, client)
        self.assertTrue(client.existing['draft'])
        client.fail_after = None
        addon_release.publish(self.output, client)
        self.assertEqual(len(client.uploads), 6)
        self.assertFalse(client.existing['draft'])

    def test_corrupt_asset_and_incomplete_published_release_cannot_upload(self):
        manifest = self.prepare()
        client = GitHubFixture(manifest, self.output)
        addon_release.publish(self.output, client)
        client.existing['assets'].pop()
        with self.assertRaisesRegex(ValueError, 'incomplete asset set'):
            addon_release.publish(self.output, client)
        self.assertEqual(len(client.uploads), 6)
        artifact = self.output / manifest['artifacts'][0]['filename']
        artifact.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'asset changed'):
            addon_release.publish(self.output, client)
        self.assertEqual(len(client.uploads), 6)

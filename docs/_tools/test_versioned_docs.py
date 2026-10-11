# SPDX-License-Identifier: Apache-2.0
"""Behavioral checks for the published documentation URL contract."""
import json
from pathlib import Path
import tempfile
import unittest

from build_versioned_docs import compose, rewrite_url


class VersionedDocumentationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sources, self.sites = {}, {}
        for version in ('0.5.1', 'nightly'):
            source = self.root / 'sources' / version
            site = self.root / 'sites' / version
            source.mkdir(parents=True)
            site.mkdir(parents=True)
            metadata = {
                'id': version, 'baseurl': '/Compukters', 'revision': 'a' * 40 if version == '0.5.1' else 'b' * 40,
                'source_rewrites': {'https://github.com/CertifiedBadIdeas/Compukters/blob/dev/':
                                    'https://github.com/CertifiedBadIdeas/Compukters/blob/' + 'a' * 40 + '/'},
            }
            (source / '.documentation-version.json').write_text(json.dumps(metadata))
            self.sources[version], self.sites[version] = source, site
            for page in ('index.html', 'GUIDE/index.html', 'guest-api/index.html', 'guest-api/guest/index.html',
                         'guest-api/create/index.html', 'guest-api/sable/index.html', 'guest-api/propulsion/index.html'):
                self.page(version, page, version + ' content')
        self.output = self.root / 'published'

    def page(self, version, page, content):
        target = self.sites[version] / page
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text('<!doctype html><html><head><title>Docs</title></head><body>' + content + '</body></html>')

    def build(self):
        compose(self.sources, self.sites, self.output)

    def test_keeps_release_content_separate_and_identifies_nightly(self):
        self.build()
        release = (self.output / '0.5.1/GUIDE/index.html').read_text()
        nightly = (self.output / 'nightly/GUIDE/index.html').read_text()
        self.assertIn('0.5.1 content', release)
        self.assertNotIn('nightly content', release)
        self.assertIn('nightly content', nightly)
        self.assertIn('b' * 40, nightly)
        self.assertIn('/Compukters/nightly/GUIDE/', release)
        self.assertIn('/Compukters/0.5.1/GUIDE/', nightly)

    def test_missing_equivalent_page_falls_back_to_selected_home(self):
        self.page('nightly', 'NEW/index.html', 'new feature')
        self.build()
        page = (self.output / 'nightly/NEW/index.html').read_text()
        self.assertIn('href="/Compukters/0.5.1/"', page)
        self.assertNotIn('href="/Compukters/0.5.1/NEW/"', page)
        self.assertIn('/Compukters/nightly/NEW/', (self.output / 'NEW/index.html').read_text())

    def test_old_urls_default_to_release_and_preserve_browser_query_and_fragment(self):
        self.build()
        page = (self.output / 'GUIDE/index.html').read_text()
        self.assertIn('/Compukters/0.5.1/GUIDE/', page)
        self.assertIn('location.search + location.hash', page)
        self.assertIn('/Compukters/0.5.1/', (self.output / 'index.html').read_text())

    def test_api_reference_also_has_a_version_selector(self):
        self.build()
        page = (self.output / '0.5.1/guest-api/create/index.html').read_text()
        self.assertIn('aria-label="Documentation version"', page)
        self.assertIn('/Compukters/nightly/guest-api/create/', page)
        self.assertTrue((self.output / 'assets/docs-versions.css').is_file())

    def test_dokka_navigation_fragment_stays_a_fragment(self):
        fragment = self.sites['0.5.1'] / 'guest-api/navigation.html'
        fragment.write_text('<div><a href="/Compukters/guest-api/guest/">Core</a></div>')
        self.build()
        content = (self.output / '0.5.1/guest-api/navigation.html').read_text()
        self.assertNotIn('docs-version-bar', content)
        self.assertIn('/Compukters/0.5.1/guest-api/guest/', content)
        self.assertFalse((self.output / 'guest-api/navigation.html').exists())

    def test_internal_links_and_assets_remain_in_selected_version(self):
        self.page('nightly', 'GUIDE/index.html', '''<a href="https://certifiedbadideas.github.io/Compukters/WIKI/?q=1&amp;x=2#anchor">Wiki</a>
<img src="/Compukters/assets/logo.svg"><a href="https://other.example/Compukters/">External</a>''')
        self.build()
        page = (self.output / 'nightly/GUIDE/index.html').read_text()
        self.assertIn('/Compukters/nightly/WIKI/?q=1&amp;x=2#anchor', page)
        self.assertIn('/Compukters/nightly/assets/logo.svg', page)
        self.assertIn('https://other.example/Compukters/', page)

    def test_source_links_use_the_actual_revision(self):
        metadata = json.loads((self.sources['0.5.1'] / '.documentation-version.json').read_text())
        url = rewrite_url('https://github.com/CertifiedBadIdeas/Compukters/blob/dev/source.kt#L1', metadata)
        self.assertEqual(url, 'https://github.com/CertifiedBadIdeas/Compukters/blob/' + 'a' * 40 + '/source.kt#L1')
        self.assertEqual(rewrite_url('/Compukters/nightly/GUIDE/', metadata), '/Compukters/nightly/GUIDE/')

    def test_missing_api_output_aborts_before_creating_published_site(self):
        (self.sites['0.5.1'] / 'guest-api/create/index.html').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing 0.5.1 output'):
            self.build()
        self.assertFalse(self.output.exists())

    def test_does_not_overwrite_an_existing_artifact(self):
        self.output.mkdir()
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.build()


if __name__ == '__main__':
    unittest.main()

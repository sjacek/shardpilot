"""Exercise Pages path/anchor validation independently of the rendered site."""
from pathlib import Path
import tempfile
import unittest

from validate_docs import markdown_anchors, validate


class ValidateDocsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repository = Path(temporary.name)
        self.output = self.repository / 'build/site'
        (self.output / 'guide').mkdir(parents=True)
        (self.output / 'guide/index.html').write_text('<h1 id="setup">Setup</h1>', encoding='utf-8')
        (self.output / 'icon.svg').write_text('<svg/>', encoding='utf-8')
        (self.repository / 'README.md').write_text('# Project\n\n## Local development\n', encoding='utf-8')

    def index(self, text):
        (self.output / 'index.html').write_text(text, encoding='utf-8')

    def check(self):
        return validate(self.output, self.repository)

    def test_relative_prefixed_absolute_and_canonical_site_links_resolve(self):
        self.index('<a href="guide/#setup">Relative</a><img src="/shardpilot/icon.svg">'
                   '<a href="/shardpilot/guide/#setup">Prefixed</a>'
                   '<link rel="canonical" href="https://sjacek.github.io/shardpilot/">')
        result = self.check()
        self.assertEqual(2, result['pages'])
        self.assertEqual(4, result['internal_links_and_assets'])
        self.assertEqual([], result['external_urls'])

    def test_repository_files_and_markdown_anchors_are_checked_against_checkout(self):
        self.index('<a href="https://github.com/sjacek/shardpilot/blob/main/README.md#local-development">Source</a>')
        self.assertEqual(1, self.check()['repository_links'])
        for target in ('missing.md', 'README.md#unknown', '%2e%2e/outside.md'):
            with self.subTest(target=target):
                self.index(f'<a href="https://github.com/sjacek/shardpilot/blob/main/{target}">Source</a>')
                with self.assertRaisesRegex(ValueError, 'missing repository (target|anchor)'):
                    self.check()

    def test_links_outside_the_publication_directory_fail_even_if_repository_file_exists(self):
        self.index('<a href="../../README.md">Source</a>')
        with self.assertRaisesRegex(ValueError, 'escapes artifact'):
            self.check()

    def test_missing_assets_pages_and_fragments_fail(self):
        for target in ('missing.svg', 'missing/', 'guide/#unknown'):
            with self.subTest(target=target):
                self.index(f'<a href="{target}">Link</a>')
                with self.assertRaisesRegex(ValueError, 'missing (target|anchor)'):
                    self.check()

    def test_encoded_traversal_and_missing_site_prefix_fail(self):
        for target in ('/guide/', '/shardpilot/%2e%2e/outside.html'):
            with self.subTest(target=target):
                self.index(f'<a href="{target}">Link</a>')
                with self.assertRaisesRegex(ValueError, 'prefix|escapes artifact'):
                    self.check()

    def test_duplicate_anchors_and_executable_schemes_fail(self):
        for text in ('<h1 id="same">One</h1><h2 id="same">Two</h2>',
                     '<a href="javascript:alert(1)">Link</a>'):
            with self.subTest(text=text):
                self.index(text)
                with self.assertRaisesRegex(ValueError, 'duplicate anchor|unsupported link scheme'):
                    self.check()

    def test_external_urls_are_inventoried_without_network_access(self):
        self.index('<a href="https://example.invalid/guide#anchor">External</a>'
                   '<a href="mailto:team@example.invalid">Email</a>')
        result = self.check()
        self.assertEqual(['https://example.invalid/guide#anchor'], result['external_urls'])
        self.assertEqual(0, result['internal_links_and_assets'])

    def test_empty_output_or_invalid_prefix_cannot_pass(self):
        with self.assertRaisesRegex(ValueError, 'no index.html'):
            self.check()
        self.index('<h1>Home</h1>')
        with self.assertRaisesRegex(ValueError, 'Invalid site base URL'):
            validate(self.output, self.repository, '/../wrong')

    def test_markdown_anchor_normalization_skips_code_and_numbers_duplicate_headings(self):
        anchors = markdown_anchors('## A `Test` &amp; [link](guide.md)\n'
                                   '```markdown\n## Not a heading\n```\n'
                                   '## Repeated\n## Repeated\n<a id="custom"></a>\n')
        self.assertEqual({'a-test--link', 'repeated', 'repeated-1', 'custom'}, anchors)


if __name__ == '__main__':
    unittest.main()

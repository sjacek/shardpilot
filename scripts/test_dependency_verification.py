"""Verifier bootstrap and policy tampering regressions; tests never download or execute Maven code."""
import hashlib
from concurrent.futures import ThreadPoolExecutor
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import dependency_policy
import maven_verification as maven


class MavenVerificationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.baseline = self.root / '.mvn/checksums.sha256'
        self.baseline.parent.mkdir(parents=True)
        self.baseline.write_text('example:library:jar:1.0 ' + 'a' * 64 + '\n', encoding='utf-8')
        self.addCleanup(patch.stopall)
        patch.object(maven, 'ROOT', self.root).start()
        patch.object(maven, 'BASELINE', self.baseline).start()
        patch.object(maven, 'CONSUMERS', self.root / 'build/consumer-checksums.sha256').start()
        self.payload = b'fake verifier bytes; never executed'
        policy = self.root / 'security/dependency-verification.json'
        policy.parent.mkdir(parents=True)
        policy.write_text(json.dumps({'schemaVersion': 1, 'mavenExtension': {
            'version': '0.0.5', 'sha256': hashlib.sha256(self.payload).hexdigest(),
            'url': 'https://repo.maven.apache.org/maven2/codes/rafael/mavenchecksumextension/'
                   'maven-checksum-extension/0.0.5/maven-checksum-extension-0.0.5.jar'}}), encoding='utf-8')

    def test_bootstrap_checks_download_and_sets_early_enforcement(self):
        with patch.object(maven.urllib.request, 'urlopen', return_value=io.BytesIO(self.payload)) as download:
            environment = maven.environment(original={})
            self.assertIn('-Dmaven.ext.class.path=', environment['MAVEN_OPTS'])
            self.assertIn('.mode=enforce', environment['MAVEN_OPTS'])
            self.assertIn('.algorithm=SHA-256', environment['MAVEN_OPTS'])
            self.assertIn('.snapshots=true', environment['MAVEN_OPTS'])
            self.assertIn('.locals=true', environment['MAVEN_OPTS'])
            self.assertIn('.reactor=false', environment['MAVEN_OPTS'])
            self.assertNotIn('relaxed', environment['MAVEN_OPTS'])
            maven.environment(original={})
            self.assertEqual(1, download.call_count)

    def test_corrupt_cached_verifier_is_rejected_instead_of_refetched_or_loaded(self):
        with patch.object(maven.urllib.request, 'urlopen', return_value=io.BytesIO(self.payload)):
            path = maven.extension()
        path.write_bytes(b'changed')
        with patch.object(maven.urllib.request, 'urlopen') as download:
            with self.assertRaisesRegex(ValueError, 'Cached.*checksum'):
                maven.environment(original={})
            download.assert_not_called()

    def test_bad_download_never_becomes_an_executable_extension(self):
        with patch.object(maven.urllib.request, 'urlopen', return_value=io.BytesIO(b'wrong bytes')):
            with self.assertRaisesRegex(ValueError, 'download checksum'):
                maven.extension()
        self.assertEqual([], list((self.root / 'build').rglob('*.jar')))

    def test_posix_jvm_options_do_not_contain_literal_shell_quotes(self):
        with patch.object(maven.os, 'name', 'posix'):
            self.assertEqual('/tmp/verifier.jar', maven.quoted('/tmp/verifier.jar'))
            with self.assertRaisesRegex(ValueError, 'without whitespace'):
                maven.quoted('/tmp/with spaces/verifier.jar')

    def test_collection_is_forbidden_in_ci(self):
        for environment in ({'CI': 'true'}, {'GITHUB_ACTIONS': 'true'}):
            with self.subTest(environment=environment), self.assertRaisesRegex(ValueError, 'forbidden in CI'):
                maven.environment(collect=True, original=environment)

    def test_conflicting_startup_options_cannot_weaken_the_verifier(self):
        with patch.object(maven, 'extension', return_value=self.root / 'verifier.jar'):
            for options in ('-Dcodes.rafael.mavenchecksumextension.relaxed=true', '-Dmaven.ext.class.path=elsewhere'):
                with self.subTest(options=options), self.assertRaisesRegex(ValueError, 'Conflicting'):
                    maven.environment(original={'MAVEN_OPTS': options})

    def test_baseline_rejects_duplicate_missing_and_weak_checksums(self):
        valid = self.baseline.read_text(encoding='utf-8')
        for text in ('', valid + valid, 'example:library:jar:1.0 abc\n', '../unsafe ' + 'a' * 64):
            self.baseline.write_text(text, encoding='utf-8')
            with self.subTest(text=text), self.assertRaises(ValueError):
                maven.load_checksums(self.baseline)

    def candidates(self):
        repository = self.root / 'repository'
        for artifact in ('shardpilot-parent', 'shardpilot-core', 'shardpilot-maven-plugin'):
            for kind in (('pom',) if artifact == 'shardpilot-parent' else ('pom', 'jar')):
                path = repository / 'io/github/sjacek' / artifact / '0.4.0' / f'{artifact}-0.4.0.{kind}'
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((artifact + kind).encode())
        return repository

    def test_consumer_policy_binds_only_five_installed_candidates_and_preserves_external_sums(self):
        original = self.baseline.read_bytes()
        target = maven.consumer_checksums(self.candidates(), '0.4.0')
        values = maven.load_checksums(target)
        self.assertEqual(6, len(values))
        self.assertEqual('a' * 64, values['example:library:jar:1.0'])
        self.assertEqual(original, self.baseline.read_bytes())
        self.assertNotIn('io.github.sjacek:other:jar:0.4.0', values)

    def test_consumer_policy_requires_current_invoker_installation(self):
        with self.assertRaisesRegex(ValueError, 'install the current reactor'):
            maven.consumer_checksums(self.root / 'empty', '0.4.0')
        self.assertFalse(maven.CONSUMERS.exists())

    def test_parallel_invoker_hooks_share_only_complete_identical_candidate_policies(self):
        repository = self.candidates()
        with ThreadPoolExecutor(max_workers=4) as threads:
            paths = list(threads.map(lambda _: maven.consumer_checksums(repository, '0.4.0'), range(16)))
        self.assertEqual(1, len(set(paths)))
        self.assertEqual(6, len(maven.load_checksums(paths[0])))
        self.assertEqual([], list(paths[0].parent.glob('consumer-*.tmp')))

    def test_windows_replace_denial_accepts_only_an_identical_concurrent_policy(self):
        repository = self.candidates()
        for identical in (True, False):
            maven.CONSUMERS.unlink(missing_ok=True)

            def competing_writer(temporary, destination):
                destination.write_bytes(temporary.read_bytes() if identical else b'different policy')
                raise PermissionError('A Windows reader holds the destination open')

            with self.subTest(identical=identical), patch.object(Path, 'replace', new=competing_writer):
                if identical:
                    self.assertEqual(6, len(maven.load_checksums(maven.consumer_checksums(repository, '0.4.0'))))
                else:
                    with self.assertRaises(PermissionError):
                        maven.consumer_checksums(repository, '0.4.0')
            self.assertEqual([], list(maven.CONSUMERS.parent.glob('consumer-*.tmp')))


class DependencyPolicyTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for name in ('security', '.github/workflows', 'gradle', '.mvn'):
            (self.root / name).mkdir(parents=True, exist_ok=True)
        self.sha = 'b' * 40
        (self.root / 'security/action-pins.json').write_text(json.dumps({'schemaVersion': 1,
            'pins': [{'action': 'actions/checkout', 'sha': self.sha}]}), encoding='utf-8')
        self.workflow = self.root / '.github/workflows/test.yml'
        self.workflow.write_text('steps:\n  - uses: actions/checkout@' + self.sha + '\n', encoding='utf-8')
        self.metadata = self.root / 'gradle/verification-metadata.xml'
        self.metadata.write_text('<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
            '<configuration><verify-metadata>true</verify-metadata></configuration><components>'
            '<component group="example" name="library" version="1.0"><artifact name="library.jar">'
            '<sha256 value="' + 'a' * 64 + '"/></artifact></component></components></verification-metadata>', encoding='utf-8')
        (self.root / '.mvn/checksums.sha256').write_text('example:library:jar:1.0 ' + 'a' * 64 + '\n', encoding='utf-8')
        (self.root / '.mvn/maven.config').write_text('--strict-checksums\n', encoding='utf-8')

    def test_valid_policy_counts_pins_and_reviewed_artifacts(self):
        self.assertEqual({'pinnedActionReferences': 1, 'gradleArtifacts': 1, 'mavenArtifacts': 1},
                         dependency_policy.verify(self.root))

    def test_mutable_foreign_and_unreviewed_action_pins_are_rejected(self):
        for reference in ('actions/checkout@v7', 'actions/checkout@' + 'c' * 40,
                          'foreign/action@' + self.sha, './.github/workflows/../outside.yml'):
            self.workflow.write_text('steps:\n  - uses: ' + reference + '\n', encoding='utf-8')
            with self.subTest(reference=reference), self.assertRaises(ValueError):
                dependency_policy.verify(self.root)

    def test_ci_cannot_record_checksums_or_disable_verification(self):
        original = self.workflow.read_text(encoding='utf-8')
        for command in ('gradlew --write-verification-metadata sha256', 'verifier run --collect',
                        'gradlew -Dorg.gradle.dependency.verification=off'):
            self.workflow.write_text(original + '  - run: ' + command + '\n', encoding='utf-8')
            with self.subTest(command=command), self.assertRaises(ValueError):
                dependency_policy.verify(self.root)

    def test_gradle_artifacts_cannot_be_exempted_or_reduced_to_sha1(self):
        original = self.metadata.read_text(encoding='utf-8')
        for text in (original.replace('<verify-metadata>true', '<verify-metadata>false'),
                     original.replace('</configuration>', '<trusted-artifacts/></configuration>'),
                     original.replace('sha256', 'sha1')):
            self.metadata.write_text(text, encoding='utf-8')
            with self.subTest(text=text), self.assertRaises(ValueError):
                dependency_policy.verify(self.root)


if __name__ == '__main__':
    unittest.main()

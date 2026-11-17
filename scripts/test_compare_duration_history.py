"""Migration-tool boundaries; the real released core is exercised separately, without a fake parser."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import compare_duration_history as migration


class HistoryComparisonTest(unittest.TestCase):
    def test_published_gradle_fixture_wrapper_matches_reviewed_root_toolchain(self):
        """An IDE-generated Wrapper must not introduce another unpinned distribution or JAR."""
        root = migration.ROOT
        fixture = root / 'testing/history-migration/adapters/gradle'

        def properties(path):
            return dict(line.split('=', 1) for line in path.read_text(encoding='utf-8').splitlines()
                        if line and not line.startswith('#'))

        reviewed = properties(root / 'gradle/wrapper/gradle-wrapper.properties')
        actual = properties(fixture / 'gradle/wrapper/gradle-wrapper.properties')
        self.assertEqual(reviewed['distributionUrl'], actual['distributionUrl'])
        self.assertEqual(reviewed['distributionSha256Sum'], actual['distributionSha256Sum'])
        self.assertRegex(actual['distributionSha256Sum'], r'^[a-f0-9]{64}$')
        self.assertEqual('true', actual['validateDistributionUrl'])
        self.assertEqual(migration.digest(root / 'gradle/wrapper/gradle-wrapper.jar'),
                         migration.digest(fixture / 'gradle/wrapper/gradle-wrapper.jar'))
        for launcher in ('gradlew', 'gradlew.bat'):
            self.assertEqual((root / launcher).read_bytes(), (fixture / launcher).read_bytes())

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sources = self.root / 'src'
        package = self.sources / 'java/example'
        package.mkdir(parents=True)
        (package / 'AlphaIT.java').write_text('private source sentinel', encoding='utf-8')
        (package / 'package-info.java').write_text('package example;', encoding='utf-8')
        self.reports = self.root / 'reports'
        self.reports.mkdir()
        (self.reports / 'TEST-alpha.xml').write_text('<testsuite name="example.AlphaIT" time="1"/>', encoding='utf-8')
        nested = self.reports / 'nested'
        nested.mkdir()
        (nested / 'TEST-other.xml').write_text('<testsuite/>', encoding='utf-8')
        self.script = self.root / 'legacy.py'
        self.script.write_text('# reference', encoding='utf-8')
        self.core = self.root / 'core.jar'
        with zipfile.ZipFile(self.core, 'w') as archive:
            archive.writestr('META-INF/maven/io.github.sjacek/shardpilot-core/pom.properties',
                             'groupId=io.github.sjacek\nartifactId=shardpilot-core\nversion=0.5.0\n')

    def test_snapshot_preserves_root_aliases_but_not_source_contents(self):
        output = self.root / 'output'
        output.mkdir()
        snapshot, manifest = migration.snapshot_inputs(output, self.script, self.sources, None,
                                                       [self.reports, self.reports])
        self.assertEqual(['reports/input-1', 'reports/input-1'], manifest['reportRoots'])
        self.assertEqual(1, len(manifest['reports']))
        self.assertEqual(b'', (snapshot / 'sources/java/example/AlphaIT.java').read_bytes())
        self.assertEqual('example.AlphaIT\n', (snapshot / 'eligible.txt').read_text())
        self.assertFalse(manifest['sourceInventoryIsNativeEligibility'])
        self.assertEqual(migration.digest(self.reports / 'TEST-alpha.xml'), manifest['reports'][0]['sha256'])
        self.assertNotIn('private source sentinel', json.dumps(manifest))

    def test_rejects_wrong_core_before_running_tools(self):
        with patch.object(migration, 'execute') as execute:
            with self.assertRaisesRegex(ValueError, 'checksum'):
                migration.compare(self.script, self.sources, None, [self.reports], self.core, '0' * 64,
                                  self.root / 'output')
            execute.assert_not_called()
        self.assertFalse((self.root / 'output').exists())

    def test_refuses_to_reuse_evidence_directory(self):
        output = self.root / 'output'
        output.mkdir()
        sentinel = output / 'candidate.tsv'
        sentinel.write_text('do not overwrite', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'immutable'):
            migration.compare(self.script, self.sources, None, [self.reports], self.core,
                              migration.digest(self.core), output)
        self.assertEqual('do not overwrite', sentinel.read_text())

    def test_rejects_output_nested_in_report_inputs(self):
        with self.assertRaisesRegex(ValueError, 'overlap'):
            migration.compare(self.script, self.sources, None, [self.reports], self.core,
                              migration.digest(self.core), self.reports / 'output')

    def test_rejects_nonfinite_negative_and_duplicate_tsv(self):
        path = self.root / 'durations.tsv'
        for content in ('example.A\tNaN\n', 'example.A\tInfinity\n', 'example.A\t-1\n',
                        'example.A\t1\nexample.A\t2\n'):
            with self.subTest(content=content):
                path.write_text(content, encoding='utf-8')
                with self.assertRaises(ValueError):
                    migration.records(path)

    def test_failed_core_never_emits_canonical_candidate(self):
        output = self.root / 'output'

        def run(command, log):
            if log.name == 'javac.log':
                return 0
            if log.parent.name == 'legacy':
                (log.parent / 'durations.tsv').write_text('example.AlphaIT\t1.0\n', encoding='utf-8')
                return 0
            migration.write_json(log.parent / 'durations.json', {'status': 'FAILED', 'reason': 'DUPLICATE_SUITE'})
            return 1

        with patch.object(migration, 'execute', side_effect=run):
            result = migration.compare(self.script, self.sources, None, [self.reports], self.core,
                                       migration.digest(self.core), output)
        self.assertEqual('REVIEW_REQUIRED', result['comparison'])
        self.assertIsNone(result['candidate'])
        self.assertFalse((output / 'candidate.tsv').exists())
        self.assertFalse(result['adoption']['allowed'])

    def test_invalid_legacy_output_is_diagnostic_not_success(self):
        def run(command, log):
            if log.name == 'javac.log':
                return 0
            (log.parent / 'durations.tsv').write_text('example.AlphaIT\tNaN\n' if log.parent.name == 'legacy' else '', encoding='utf-8')
            if log.parent.name != 'legacy':
                migration.write_json(log.parent / 'durations.json', {'status': 'PASSED'})
            return 0

        with patch.object(migration, 'execute', side_effect=run):
            result = migration.compare(self.script, self.sources, None, [self.reports], self.core,
                                       migration.digest(self.core), self.root / 'output')
        self.assertEqual('INVALID_TSV', result['legacy']['outputError'])
        self.assertEqual('REVIEW_REQUIRED', result['comparison'])

    def test_difference_explains_floor_and_repeated_input(self):
        from decimal import Decimal
        suites = [{'name': 'example.AlphaIT', 'seconds': Decimal('.04'), 'executed': True,
                   'failed': False, 'retried': False, 'ambiguous': False}]
        rows = migration.differences({'example.AlphaIT': Decimal('0')}, {'example.AlphaIT': Decimal('.1')},
                                    suites, {'example.AlphaIT'}, True)
        self.assertEqual(['INPUT_ROOT_REPEATED', 'MINIMUM_FLOOR'], rows[0]['reasons'])


if __name__ == '__main__':
    unittest.main()

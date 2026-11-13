"""Native framework fixtures and independent checks against deliberately corrupted evidence."""
import base64
from contextlib import redirect_stdout
import hashlib
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import audit_runner as runner
import check_evidence
import published_artifacts
import run_published_artifact_audit as launcher


def synthetic_evidence(root):
    """Small, fully retained cohort: signature/registry stubs plus real event/XML/trace parsers.

    This exercises evidence validation without substituting for GPG, Docker or native framework
    runs. The full audit separately verifies those against the published registry bytes.
    """
    def write(relative, value):
        runner.write(root / relative, json.dumps(value) if isinstance(value, dict) else value)

    version, commit = '0.5.0', 'a' * 40
    artifacts = []
    for index in range(15):
        file = 'central/shardpilot-core-0.5.0.jar' if index == 0 else f'portal/artifact-{index}.jar'
        write(file, f'public bytes {index}')
        artifacts.append({'file': file, 'sha256': hashlib.sha256((root / file).read_bytes()).hexdigest()})
    write('registry-verification.json', {'version': version, 'sourceCommit': commit, 'artifacts': artifacts})
    write('source-tag.json', {'verification': {'payload': f'object {commit}\ntype commit\ntag v{version}\n'}})
    fingerprint = published_artifacts.audit_policy()['publisherPrimaryFingerprint']
    write('signatures/signatures.json', {'passed': True, 'verifiedSignatures': [
        {'file': 'source-tag' if index == 0 else f'artifact-{index}', 'primaryFingerprint': fingerprint}
        for index in range(10)]})
    for phase in ('signatures', 'warm', 'isolated'):
        write(phase + '/container-policy.json', {'imageId': 'sha256:fixture', 'network': 'bridge' if phase == 'warm' else 'none'})
    write('isolated/positive-control.strace', 'connect(5, {sa_family=AF_INET, sin_port=htons(443), sin_addr=inet_addr("198.51.100.1")}, 16) = -1 ENETUNREACH\n')
    for warm in (True, False):
        phase = 'warm' if warm else 'isolated'
        cases = []
        for scenario in runner.SCENARIOS:
            tool, framework = scenario['tool'], scenario['framework']
            for name, _, count, node in runner.cohorts(warm):
                relative = f'{phase}/{tool}/{framework}/{name}'
                families = [family for family in range(scenario['nativeTests'] // 2) if family % count == node - 1]
                events = []
                for family in families:
                    for iteration in (1, 2):
                        identity = (f'[engine:{scenario["engine"]}]/[class:Family{family}]/[iteration:{iteration}]'
                                    if scenario['engine'] else f'example.equivalence.Family{family}#rows/parameters=[1]/invocation={iteration}')
                        encoded = base64.urlsafe_b64encode(identity.encode()).decode().rstrip('=')
                        events.append(f'v1\t{scenario["listener"]}\tTEST\t{encoded}\tPASSED\n')
                write(relative + '/events.tsv', ''.join(events))
                write(relative + '/build.strace', 'connect(4, {sa_family=AF_UNIX, sun_path="/tmp/daemon"}, 16) = 0\n')
                write(relative + '/unit-xml/TEST-fixture.xml',
                      f'<testsuite tests="{len(events)}" failures="0" errors="0" skipped="0"/>')
                cases.append({'tool': tool, 'framework': framework, 'cohort': name, 'outboundAttempts': []})
        write(phase + '/summary.json', {'executionSchemaVersion': runner.EXECUTION_SCHEMA_VERSION,
              'version': version, 'phase': phase, 'passed': True, 'cases': cases})


class FrameworkFixtureTest(unittest.TestCase):
    def test_spock_and_testng_use_their_native_engine_and_repeated_data_rows(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runner.fixtures(root, '1.2.3')
            spock = root / 'gradle/spock'
            testng = root / 'gradle/testng'
            self.assertIn("id 'groovy'", (spock / 'build.gradle').read_text())
            self.assertIn("includeEngines 'spock'", (spock / 'build.gradle').read_text())
            self.assertIn('groovy:5.1.3', (spock / 'build.gradle').read_text())
            self.assertIn('value << [1, 1]', (spock / 'src/test/groovy/example/equivalence/AlphaSpec.groovy').read_text())
            service = 'src/test/resources/META-INF/services/org.junit.platform.launcher.TestExecutionListener'
            self.assertEqual('example.equivalence.JunitExecutionListener', (spock / service).read_text().strip())
            self.assertIn("useTestNG { listeners << 'example.equivalence.TestNgExecutionListener' }", (testng / 'build.gradle').read_text())
            self.assertFalse((testng / service).exists())
            self.assertIn('new Object[][] {{1}, {1}}', (testng / 'src/test/java/example/equivalence/AlphaTest.java').read_text())
            self.assertEqual(4, len(list(spock.glob('src/test/groovy/**/*Spec.groovy'))))
            self.assertEqual(4, len(list(testng.glob('src/test/java/**/*Test.java'))))

    def test_warmup_requires_both_paths_for_every_framework_even_with_a_cached_plugin(self):
        # Mock only process/filesystem boundaries; execute the actual scheduling branch.
        with patch.object(runner.Path, 'mkdir'), patch.object(runner.Path, 'unlink'), \
                patch.object(runner.Path, 'is_file', return_value=True), patch.object(runner, 'toolchain'), \
                patch.object(runner, 'restore_seed'), patch.object(runner.shutil, 'copytree'), \
                patch.object(runner, 'execute') as execute, patch.object(runner, 'native_events') as events, \
                patch.object(runner, 'cached_artifacts', return_value=[]), patch.object(runner, 'checkpoint_seed'), \
                patch.object(runner, 'write') as write, redirect_stdout(io.StringIO()):
            runner.runs(True)
        self.assertEqual(8, execute.call_count)
        self.assertEqual(8, events.call_count)
        summary = json.loads(write.call_args.args[1])
        self.assertTrue(summary['restoredDependencySeed'])
        self.assertEqual({(item['tool'], item['framework'], name) for item in runner.SCENARIOS
                          for name, _, _, _ in runner.cohorts(True)},
                         {(item['tool'], item['framework'], item['cohort']) for item in summary['cases']})


class FrameworkEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        synthetic_evidence(self.root)

    def verify(self):
        with redirect_stdout(io.StringIO()):
            return launcher.summarize(self.root)

    def rejected(self, text):
        (self.root / 'verified-evidence.json').write_text('{"passed":true}')
        with self.assertRaisesRegex(ValueError, text):
            self.verify()
        self.assertFalse((self.root / 'verified-evidence.json').exists())

    def test_full_cohort_checks_28_runs_and_eight_framework_comparisons(self):
        result = self.verify()
        self.assertEqual(28, len(result['cases']))
        self.assertEqual(8, len(result['comparisons']))
        self.assertTrue(all(item['passed'] for item in result['comparisons']))

    def test_all_24_negative_controls_reject_corrupted_evidence_and_remove_stale_success(self):
        workspace = self.root / 'private-workspace'
        (workspace / 'build').mkdir(parents=True)
        with patch.object(check_evidence, 'ROOT', workspace), redirect_stdout(io.StringIO()):
            check_evidence.check(self.root)
        result = json.loads((self.root / 'negative-controls.json').read_text())
        self.assertTrue(result['passed'])
        self.assertEqual(24, len(set(result['rejectedMutations'])))
        self.assertEqual(runner.negative_control_names(), result['rejectedMutations'])

    def test_invalid_source_evidence_removes_an_earlier_green_negative_control_report(self):
        report = self.root / 'negative-controls.json'
        report.write_text('{"passed":true}')
        (self.root / 'isolated/gradle/testng/baseline/events.tsv').write_text('')
        with self.assertRaisesRegex(ValueError, 'Unexpected native observation count'):
            check_evidence.check(self.root)
        self.assertFalse(report.exists())

    def test_legacy_jupiter_only_success_metadata_cannot_downgrade_the_required_cohort(self):
        path = self.root / 'isolated/summary.json'
        summary = json.loads(path.read_text())
        summary.pop('executionSchemaVersion')
        summary['cases'] = [item for item in summary['cases'] if item['framework'] == 'junit']
        path.write_text(json.dumps(summary))
        self.rejected('Incomplete framework cohort')

    def test_missing_spock_raw_evidence_is_rejected_even_when_metadata_claims_success(self):
        shutil.rmtree(self.root / 'isolated/gradle/spock')
        self.rejected('Unexpected framework evidence')

    def test_testng_warmup_cannot_be_omitted(self):
        shutil.rmtree(self.root / 'warm/gradle/testng/2-1')
        self.rejected('Incomplete native framework execution')

    def test_warmup_requires_native_execution_evidence_not_just_a_directory_and_success_flag(self):
        path = self.root / 'warm/gradle/testng/2-1/events.tsv'
        path.write_text('')
        self.rejected('Unexpected native observation count')

    def test_duplicate_metadata_cannot_hide_an_omitted_framework_cohort(self):
        path = self.root / 'isolated/summary.json'
        summary = json.loads(path.read_text())
        summary['cases'][-1] = summary['cases'][0]
        path.write_text(json.dumps(summary))
        self.rejected('Incomplete framework cohort')

    def test_spock_cannot_reuse_jupiter_engine_identities(self):
        path = self.root / 'isolated/gradle/spock/baseline/events.tsv'
        path.write_bytes((self.root / 'isolated/gradle/junit/baseline/events.tsv').read_bytes())
        self.rejected('another engine')

    def test_native_identities_must_be_distinct_well_formed_and_from_the_requested_listener(self):
        path = self.root / 'isolated/gradle/testng/baseline/events.tsv'
        original = path.read_text()
        rows = original.splitlines(keepends=True)
        for changed, message in ((''.join([rows[0], rows[0], *rows[2:]]), 'Repeated native execution identity'),
                                 (original.replace('v1\ttestng', 'v1\tjunit-platform', 1), 'Unexpected native observation stream'),
                                 ('v1\ttestng\tTEST\t!invalid!\tPASSED\n', 'Invalid native execution identity')):
            with self.subTest(message=message):
                path.write_text(changed)
                with self.assertRaisesRegex(ValueError, message):
                    runner.native_events(path, runner.SCENARIOS[2], 1 if message.startswith('Invalid') else 8)


if __name__ == '__main__':
    unittest.main()

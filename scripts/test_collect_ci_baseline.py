"""Portable hostile-cohort regressions; fixtures contain no consumer source or data."""
import copy
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import collect_ci_baseline as capture


def manifest(*names):
    return ('schemaVersion: 1\nclasses:\n' + ''.join('  - ' + n + '\n' for n in sorted(names))).encode()


def xml(name, duplicate=False):
    test = f'<testcase name="native test" classname="{name}"/>'
    return (f'<testsuite name="{name}" tests="{2 if duplicate else 1}" skipped="0" failures="0" errors="0">'
            + test * (2 if duplicate else 1) + '</testsuite>').encode()


class CiBaselineTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.downloads = self.root / 'downloads'
        self.downloads.mkdir()
        self.contract = {'schemaVersion': 1, 'project': 7, 'pipeline': 11, 'revision': 'a' * 40, 'shards': 2,
                         'baseline': 'build/plan/baseline.yaml', 'planned': 'build/plan/planned-{index}.yaml',
                         'actual': 'build/actual/actual-{index}.yaml', 'xml': 'build/test-results/integrationTest',
                         'coverage': 'build/coverage.json', 'history': 'build/history.tsv', 'jobs': [],
                         'nativeSuite': 'integrationTest', 'reportJobs': []}
        self.contract.update(eligibilityRoots=['build/classes'], dedicatedClasses=[], requiredDirectories=['build/classes'])
        self.pipeline = {'project_id': 7, 'id': 11, 'sha': 'a' * 40, 'status': 'success', 'source': 'schedule'}
        (self.downloads / 'pipeline.json').write_text(json.dumps(self.pipeline))
        self.contract['pipelineSha256'] = capture.sha256(self.downloads / 'pipeline.json')
        self.payloads = {
            'compile': {'build/plan/baseline.yaml': manifest('example.AlphaIT', 'example.BetaIT'),
                        'build/plan/planned-1.yaml': manifest('example.AlphaIT'),
                        'build/plan/planned-2.yaml': manifest('example.BetaIT'),
                        'build/classes/example/AlphaIT.class': b'compiled-testdata',
                        'build/classes/example/BetaIT.class': b'compiled-testdata'},
            'shard-1': {'build/actual/actual-1.yaml': manifest('example.AlphaIT'),
                        'build/test-results/integrationTest/TEST-Alpha.xml': xml('example.AlphaIT', True)},
            'shard-2': {'build/actual/actual-2.yaml': manifest('example.BetaIT'),
                        'build/test-results/integrationTest/TEST-Beta.xml': xml('example.BetaIT')},
            'coverage': {'build/coverage.json': json.dumps({'status': 'passed', 'classes': 2, 'actualVerified': True,
                         'shards': [{'index': i, 'classes': 1, 'sha256': hashlib.sha256(n.encode()).hexdigest()}
                                    for i, n in enumerate(['example.AlphaIT', 'example.BetaIT'], 1)]}).encode()},
            'history': {'build/history.tsv': b'example.AlphaIT\t1\nexample.BetaIT\t2\n'}}
        for index, role in enumerate(self.payloads, 1):
            pin = {'id': index, 'role': role, 'name': role}
            self.contract['jobs'].append(pin)
            self.write_job(pin)
        cases = [{'classname': 'example.' + name + 'IT', 'name': 'native test', 'status': 'success'} for name in ('Alpha', 'Beta')]
        counts = {'total_count': 2, 'success_count': 2, 'failed_count': 0, 'skipped_count': 0, 'error_count': 0}
        suite = {'name': 'integrationTest', **counts, 'suite_error': None, 'test_cases': cases}
        self.native = {**counts, 'test_suites': [suite]}
        self.summary = {'test_suites': [{k: v for k, v in suite.items() if k != 'test_cases'} | {'build_ids': [2, 3]}]}
        self.write_reports()
        for pin in self.contract['jobs']:
            if pin['role'].startswith('shard-'):
                self.contract['reportJobs'].append({k: pin[k] for k in ('id', 'name', 'metadataSha256')} | {'suite': 'integrationTest'})

    def write_reports(self):
        for filename, value, key in [('native-test-report.json', self.native, 'testReportSha256'),
                                     ('native-test-summary.json', self.summary, 'testSummarySha256')]:
            path = self.downloads / filename
            path.write_text(json.dumps(value))
            self.contract[key] = capture.sha256(path)

    def write_job(self, pin):
        archive = self.downloads / f"job-{pin['id']}.zip"
        with zipfile.ZipFile(archive, 'w') as output:
            for name, data in self.payloads[pin['role']].items():
                output.writestr(name, data)
        pin.update(bytes=archive.stat().st_size, sha256=capture.sha256(archive))
        metadata = {'id': pin['id'], 'name': pin['name'], 'status': 'success', 'allow_failure': False,
                    'pipeline': self.pipeline, 'commit': {'id': self.contract['revision']},
                    'artifacts_file': {'size': pin['bytes']}}
        file = self.downloads / f"job-{pin['id']}.json"
        file.write_text(json.dumps(metadata))
        pin['metadataSha256'] = capture.sha256(file)

    def collect(self):
        contract = self.root / 'contract.json'
        contract.write_text(json.dumps(self.contract))
        return capture.collect(contract, self.downloads, self.root / 'verified')

    def test_complete_cohort_preserves_duplicate_native_occurrences(self):
        result = self.collect()
        self.assertEqual(2, result['classes'])
        self.assertEqual(3, result['nativeReport']['rootXmlOccurrences'])
        self.assertEqual(2, result['nativeReport']['rootApiDisplayIdentities'])
        self.assertEqual(1, result['nativeReport']['rootDuplicateDisplayOccurrences'])
        self.assertEqual(b'compiled-testdata', (self.root / 'verified/compiled/build/classes/example/AlphaIT.class').read_bytes())

    def test_archive_and_metadata_hashes_are_independently_enforced(self):
        for suffix in ('zip', 'json'):
            file = self.downloads / f'job-1.{suffix}'
            original = file.read_bytes()
            file.write_bytes(original + b'changed')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                self.collect()
            file.write_bytes(original)
        self.assertFalse((self.root / 'verified/report.json').exists())

    def test_mixed_revision_pipeline_and_unsuccessful_jobs_are_rejected(self):
        pin = self.contract['jobs'][0]
        path = self.downloads / 'job-1.json'
        original = json.loads(path.read_text())
        for kind in ('revision', 'pipeline', 'status', 'optional'):
            data = copy.deepcopy(original)
            if kind == 'revision':
                data['commit']['id'] = 'b' * 40
            elif kind == 'pipeline':
                data['pipeline']['id'] = 12
            elif kind == 'status':
                data['status'] = 'failed'
            else:
                data['allow_failure'] = True
            path.write_text(json.dumps(data))
            pin['metadataSha256'] = capture.sha256(path)
            with self.assertRaisesRegex(ValueError, 'cohort'):
                self.collect()

    def test_incomplete_overlapping_and_foreign_plans_fail(self):
        original = self.payloads['compile']['build/plan/planned-2.yaml']
        for names in [('example.AlphaIT',), ('example.ForeignIT',), ()]:
            self.payloads['compile']['build/plan/planned-2.yaml'] = manifest(*names)
            self.write_job(self.contract['jobs'][0])
            with self.assertRaises(ValueError):
                self.collect()
        self.payloads['compile']['build/plan/planned-2.yaml'] = original

    def test_actual_manifest_and_missing_native_xml_fail(self):
        pin = self.contract['jobs'][1]
        original = self.payloads['shard-1'].copy()
        for mutation in ('actual', 'xml'):
            self.payloads['shard-1'] = original.copy()
            if mutation == 'actual':
                self.payloads['shard-1']['build/actual/actual-1.yaml'] = manifest('example.BetaIT')
            else:
                del self.payloads['shard-1']['build/test-results/integrationTest/TEST-Alpha.xml']
            self.write_job(pin)
            with self.assertRaises(ValueError):
                self.collect()

    def test_missing_compiled_eligible_class_and_required_tree_are_rejected(self):
        del self.payloads['compile']['build/classes/example/BetaIT.class']
        self.write_job(self.contract['jobs'][0])
        with self.assertRaisesRegex(ValueError, 'Compiled candidate ownership'):
            self.collect()

    def test_incomplete_shard_cohort_and_required_tree_are_rejected(self):
        pin = self.contract['jobs'].pop(2)
        with self.assertRaisesRegex(ValueError, 'shard cohort'):
            self.collect()
        self.contract['jobs'].insert(2, pin)
        self.contract['requiredDirectories'].append('build/missing')
        with self.assertRaisesRegex(ValueError, 'required compilation tree'):
            self.collect()

    def test_unsafe_paths_aliases_and_symlinks_fail_before_extraction(self):
        bad_paths = ['../escape', '/absolute', 'C:/drive', 'build\\escape', 'build/NUL.txt', 'build/trailing.', 'build//empty']
        for name in bad_paths:
            with self.subTest(name=name), self.assertRaises(ValueError):
                capture.relative(name)
        for kind in ('case', 'link', 'collision'):
            file = self.root / f'{kind}.zip'
            with zipfile.ZipFile(file, 'w') as archive:
                archive.writestr('build/file', b'1')
                if kind == 'case':
                    archive.writestr('build/FILE', b'2')
                elif kind == 'collision':
                    archive.writestr('build/FILE/child', b'2')
                else:
                    info = zipfile.ZipInfo('build/link')
                    info.create_system = 3
                    info.external_attr = (stat.S_IFLNK | 0o777) << 16
                    archive.writestr(info, b'file')
            with self.assertRaises(ValueError):
                capture.Archive(file)

    def test_duplicate_job_role_and_old_output_are_rejected(self):
        self.contract['jobs'].append(self.contract['jobs'][0].copy())
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.collect()
        self.contract['jobs'].pop()
        (self.root / 'verified').mkdir()
        with self.assertRaisesRegex(ValueError, 'fresh'):
            self.collect()

    def test_failed_xml_and_entity_documents_are_rejected(self):
        archive = self.root / 'bad-xml.zip'
        for raw in [xml('example.AlphaIT').replace(b'tests="1"', b'tests="2"'),
                    b'<testsuite name="example.AlphaIT" tests="1" skipped="0" failures="1" errors="0">'
                    b'<testcase name="failed test" classname="example.AlphaIT"><failure/></testcase></testsuite>',
                    b'<!DOCTYPE testsuite [<!ENTITY secret "payload">]><testsuite/>']:
            with zipfile.ZipFile(archive, 'w') as output:
                output.writestr('xml/result.xml', raw)
            opened = capture.Archive(archive)
            try:
                with self.assertRaises(ValueError):
                    capture.xml_summary(opened, 'xml')
            finally:
                opened.close()

    def test_resource_bounds_and_duplicate_metadata_fields_are_rejected(self):
        archive = self.downloads / 'job-1.zip'
        for name, limit in [('MAX_ENTRIES', 1), ('MAX_FILE', 2), ('MAX_TOTAL', 2)]:
            with patch.object(capture, name, limit), self.assertRaises(ValueError):
                capture.Archive(archive)
        with self.assertRaisesRegex(ValueError, 'Duplicate metadata'):
            capture.document('{"id":1,"id":2}')

    def test_pipeline_display_identity_and_job_binding_cannot_hide_missing_tests(self):
        self.native['test_suites'][0]['test_cases'][0]['name'] = 'another test'
        self.write_reports()
        with self.assertRaisesRegex(ValueError, 'identities differ'):
            self.collect()
        self.summary['test_suites'][0]['build_ids'] = [999]
        self.write_reports()
        with self.assertRaisesRegex(ValueError, 'another job'):
            self.collect()

    def test_legacy_seal_rejects_changed_and_extra_compilation_files(self):
        self.contract.update(legacyManifest='build/legacy.json', legacyRoots=['build/classes'])
        legacy = {'version': 1, 'commit': 'a' * 40, 'integrationSuite': 'full',
                  'files': {f'build/classes/example/{name}IT.class': hashlib.sha256(b'compiled-testdata').hexdigest()
                            for name in ('Alpha', 'Beta')}}
        self.payloads['compile']['build/legacy.json'] = json.dumps(legacy).encode()
        self.payloads['compile']['build/classes/Extra.class'] = b'extra'
        self.write_job(self.contract['jobs'][0])
        with self.assertRaisesRegex(ValueError, 'seal file set'):
            self.collect()

    @unittest.skipUnless(os.environ.get('SHARDPILOT_SELECTION_CLASSPATH'), 'Run through ConsumerBaselineFunctionalTest for the real core')
    def test_real_core_seals_and_evaluates_the_captured_baseline(self):
        self.collect()
        base = self.root / 'verified/compiled'
        java = os.environ.get('SHARDPILOT_SELECTION_JAVA', 'java')
        command = [java, '-cp', os.environ['SHARDPILOT_SELECTION_CLASSPATH'],
                   'io.github.sjacek.shardpilot.testing.ConsumerBaselineProbe']
        seal = subprocess.run(command + ['seal', str(base), str(self.root / 'c3.json'), 'a' * 40, 'full-testdata', 'build'],
                              capture_output=True, text=True, timeout=45)
        self.assertEqual(0, seal.returncode, seal.stderr)
        digest = capture.sha256(self.root / 'c3.json')
        verify = command + ['verify', str(base), str(self.root / 'c3.json'), 'b' * 40, 'full-testdata', digest, 'build']
        rejected = subprocess.run(verify, capture_output=True, text=True, timeout=45)
        self.assertNotEqual(0, rejected.returncode)
        self.assertIn('REVISION_MISMATCH', rejected.stderr)
        verify[verify.index('b' * 40)] = 'a' * 40
        changed = base / 'build/classes/example/AlphaIT.class'
        original = changed.read_bytes()
        changed.write_bytes(b'corrupt-testdata')
        rejected = subprocess.run(verify, capture_output=True, text=True, timeout=45)
        self.assertNotEqual(0, rejected.returncode)
        self.assertIn('FILE_CONTENT_MISMATCH', rejected.stderr)
        changed.write_bytes(original)
        policy = self.root / 'policy.yaml'
        policy.write_text(json.dumps({'schemaVersion': 2, 'profiles': {
            'full-testdata': {'mode': 'full'}, 'daily-testdata': {'mode': 'affected',
            'mandatory': {'classes': ['example.AlphaIT']}, 'onUnmatchedChanges': 'full',
            'impact': {'changedTests': [{'path': 'src/integrationTest/java', 'extensions': ['java']}]}}}}))
        cases = self.root / 'cases.tsv'
        cases.write_text('full-testdata\tfull-testdata\nchanged-testdata\tdaily-testdata\tsrc/integrationTest/java/example/BetaIT.java\n'
                         'unknown-testdata\tdaily-testdata\tnew/module.java\n')
        result = subprocess.run(command + ['policy', str(self.root / 'verified/baseline.yaml'), str(policy),
                                str(self.root / 'verified/history.tsv'), 'a' * 40, str(cases), str(self.root / 'decisions')],
                                capture_output=True, text=True, timeout=45)
        self.assertEqual(0, result.returncode, result.stderr)
        text = (self.root / 'decisions/decisions.tsv').read_text()
        self.assertIn('full-testdata\tPASSED\tFULL\t2', text)
        self.assertIn('changed-testdata\tPASSED\tSUBSET\t2\t2', text)
        self.assertIn('unknown-testdata\tPASSED\tFULL_FALLBACK\t2', text)


if __name__ == '__main__':
    unittest.main()

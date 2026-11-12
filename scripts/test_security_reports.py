"""Native-like inventories, real JAR contents and scanner failures without remote services."""
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import security_reports as security


VERSION = '0.4.0'
COMMIT = 'a' * 40
NOW = datetime(2026, 11, 12, 10, tzinfo=timezone.utc)


def component(group, name):
    purl = f'pkg:maven/{group}/{name}@{VERSION}'
    return {'type': 'library', 'group': group, 'name': name, 'version': VERSION,
            'purl': purl, 'bom-ref': purl}


def bom(root, components, edges):
    return {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
            'metadata': {'component': root}, 'components': components,
            'dependencies': [{'ref': owner['bom-ref'], 'dependsOn': [child['bom-ref'] for child in children]}
                             for owner, children in edges]}


class SecurityReportsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.addCleanup(patch.stopall)
        patch.object(security, 'ROOT', self.root).start()
        patch.object(security.subprocess, 'check_output', return_value='').start()
        self.policy = {'schemaVersion': 1, 'scannerVersion': '0.121.3', 'severityCutoff': 'high',
                       'databaseMaxAgeHours': 120, 'includeUnfixed': True}
        self.write('security/scan-policy.json', self.policy)
        self.core = component('io.github.sjacek', 'shardpilot-core')
        self.library = component('example.library', 'parser')

    def write(self, name, value):
        path = self.root / name
        security.write_json(path, value)
        return path

    def jar(self, name, files):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, 'w') as archive:
            for entry, content in files.items():
                archive.writestr(entry, content)
        return path

    def gradle(self):
        root = component('io.github.sjacek', 'shardpilot')
        plugin = bom(root, [self.library], [(root, [self.library]), (self.library, [])])
        core = bom(self.core, [self.library], [(self.core, [self.library]), (self.library, [])])
        self.write('shardpilot-gradle-plugin/build/reports/security/gradle/bom.raw.json', plugin)
        self.write('shardpilot-core/build/reports/security/gradle/bom.raw.json', core)
        classes = self.root / 'shardpilot-core/build/classes/java/main/example/Core.class'
        classes.parent.mkdir(parents=True)
        classes.write_bytes(b'compiled-core')
        jar = self.jar('shardpilot-gradle-plugin/build/libs/shardpilot-' + VERSION + '.jar',
                       {'example/Core.class': b'compiled-core'})
        return plugin, classes, jar

    def maven(self):
        root = component('io.github.sjacek', 'shardpilot-maven-plugin')
        document = bom(root, [self.core, self.library],
                       [(root, [self.core]), (self.core, [self.library]), (self.library, [])])
        descriptor = '<plugin><dependencies>' + ''.join(
            f'<dependency><groupId>{item["group"]}</groupId><artifactId>{item["name"]}</artifactId>'
            f'<version>{item["version"]}</version></dependency>' for item in (self.core, self.library)) \
            + '</dependencies></plugin>'
        jar = self.jar('shardpilot-maven-plugin/target/shardpilot-maven-plugin-' + VERSION + '.jar',
                       {'META-INF/maven/plugin.xml': descriptor})
        core_jar = self.jar('shardpilot-core/target/shardpilot-core-' + VERSION + '.jar',
                            {'example/Core.class': b'compiled-core'})
        self.core['hashes'] = [{'alg': 'SHA-256', 'content': security.sha256(core_jar)}]
        self.write('shardpilot-maven-plugin/target/reports/security/maven/bom.raw.json', document)
        return document, jar, core_jar

    def scan(self):
        self.gradle()
        security.prepare('gradle', COMMIT)
        output = self.root / 'build/security/gradle'
        database = {'valid': True, 'schemaVersion': 'v6.1.10', 'built': NOW.isoformat(),
                    'from': 'https://example.invalid/database'}
        report = {'matches': [], 'source': {'type': 'sbom-file', 'target': str(output / 'bom.cdx.json')},
                  'descriptor': {'name': 'grype', 'version': self.policy['scannerVersion'],
                                 'db': {'status': database},
                                 'configuration': {'only-fixed': False, 'only-notfixed': False,
                                                   'show-suppressed': True, 'exclude': [], 'vex-documents': [],
                                                   'fail-on-severity': 'high'}}}
        self.write('build/security/gradle/scanner.json', {'version': self.policy['scannerVersion']})
        self.write('build/security/gradle/database.json', database)
        self.write('build/security/gradle/findings.json', report)
        self.write('build/security/gradle/scan-status.json', {'exitCode': 0})
        return output, report, database

    def test_gradle_inventory_proves_embedded_class_bytes_and_connects_core_graph(self):
        _, _, artifact = self.gradle()
        security.prepare('gradle', COMMIT)
        output = self.root / 'build/security/gradle'
        final = security.read_json(output / 'bom.cdx.json')
        inventory = security.read_json(output / 'inventory.json')
        root, refs, graph = security.validate_bom(final)
        self.assertIn(self.core['bom-ref'], graph[root['bom-ref']])
        self.assertEqual([self.library['bom-ref']], graph[self.core['bom-ref']])
        self.assertEqual('embedded-classes', inventory['core']['delivery'])
        self.assertEqual(1, inventory['core']['classCount'])
        self.assertEqual(security.sha256(artifact), root['hashes'][0]['content'])
        self.assertEqual(3, len(refs))
        self.assertFalse(inventory['publishedArtifactsVerified'])

    def test_gradle_rejects_mismatched_core_bytes_and_removes_old_green_reports(self):
        _, classes, _ = self.gradle()
        security.prepare('gradle', COMMIT)
        self.write('build/security/gradle/summary.json', {'passed': True})
        classes.write_bytes(b'changed-after-assembly')
        with self.assertRaisesRegex(ValueError, 'bytecode differs'):
            security.prepare('gradle', COMMIT)
        self.assertFalse((self.root / 'build/security/gradle/summary.json').exists())
        self.assertFalse((self.root / 'build/security/gradle/bom.cdx.json').exists())

    def test_gradle_rejects_core_libraries_missing_from_the_plugin_runtime(self):
        document, _, _ = self.gradle()
        document['components'] = []
        document['dependencies'] = [{'ref': document['metadata']['component']['bom-ref'], 'dependsOn': []}]
        self.write('shardpilot-gradle-plugin/build/reports/security/gradle/bom.raw.json', document)
        with self.assertRaisesRegex(ValueError, 'every resolved core dependency'):
            security.prepare('gradle', COMMIT)

    def test_maven_inventory_matches_plugin_descriptor_and_binds_both_candidate_jars(self):
        self.maven()
        security.prepare('maven', COMMIT)
        inventory = security.read_json(self.root / 'build/security/maven/inventory.json')
        self.assertEqual('external-artifact', inventory['core']['delivery'])
        self.assertEqual(2, len(inventory['artifacts']))
        self.assertIn('provided/system/test scopes', inventory['excludedScopes'])

    def test_maven_rejects_unshipped_dependencies_and_stale_candidate_hashes(self):
        document, _, core_jar = self.maven()
        core_jar.write_bytes(b'changed artifact')
        with self.assertRaisesRegex(ValueError, 'hash does not match'):
            security.prepare('maven', COMMIT)
        self.core.pop('hashes')
        document['components'].remove(self.library)
        document['dependencies'] = document['dependencies'][:2]
        document['dependencies'][1]['dependsOn'] = []
        self.write('shardpilot-maven-plugin/target/reports/security/maven/bom.raw.json', document)
        with self.assertRaisesRegex(ValueError, 'descriptor and runtime SBOM'):
            security.prepare('maven', COMMIT)

    def test_runtime_graph_rejects_duplicate_dangling_unreachable_and_test_components(self):
        root = component('example', 'root')
        valid = bom(root, [self.library], [(root, [self.library]), (self.library, [])])
        for kind in ('duplicate', 'dangling', 'unreachable', 'test', 'excluded', 'missing-purl'):
            with self.subTest(kind=kind):
                value = copy.deepcopy(valid)
                if kind == 'duplicate':
                    value['components'].append(value['components'][0])
                elif kind == 'dangling':
                    value['dependencies'][0]['dependsOn'].append('missing')
                elif kind == 'unreachable':
                    value['dependencies'][0]['dependsOn'] = []
                elif kind == 'test':
                    value['components'][0]['properties'] = [{'name': 'cdx:maven:package:test', 'value': 'true'}]
                elif kind == 'excluded':
                    value['components'][0]['scope'] = 'excluded'
                else:
                    value['components'][0].pop('purl')
                with self.assertRaises(ValueError):
                    security.validate_bom(value)

    def test_zero_findings_with_complete_evidence_passes(self):
        output, _, _ = self.scan()
        self.assertEqual(0, security.summarize('gradle', NOW))
        summary = security.read_json(output / 'summary.json')
        self.assertTrue(summary['passed'])
        self.assertEqual(COMMIT, summary['sourceCommit'])
        self.assertEqual(0, summary['blockingMatches'])

    def test_high_and_critical_findings_block_even_without_available_fixes_and_keep_reports(self):
        output, report, _ = self.scan()
        report['matches'] = [{'vulnerability': {'id': 'CVE-example-' + level, 'severity': level,
                                               'fix': {'state': 'not-fixed', 'versions': []}},
                              'artifact': {'name': 'parser', 'purl': self.library['purl']}}
                             for level in ('Low', 'Medium', 'High', 'Critical', 'Unknown')]
        self.write('build/security/gradle/findings.json', report)
        self.write('build/security/gradle/scan-status.json', {'exitCode': 2})
        self.assertEqual(2, security.summarize('gradle', NOW))
        summary = security.read_json(output / 'summary.json')
        self.assertEqual(2, summary['blockingMatches'])
        self.assertEqual(5, len(summary['findings']))
        self.assertTrue((output / 'summary.md').is_file())

    def test_invalid_or_weakened_scan_evidence_cannot_leave_an_old_green_summary(self):
        output, original, _ = self.scan()
        for kind in ('missing-matches', 'wrong-version', 'wrong-source', 'wrong-db', 'invalid-db',
                     'only-fixed', 'vex', 'hidden-suppressions', 'suppressed', 'unknown-severity'):
            with self.subTest(kind=kind):
                report = copy.deepcopy(original)
                if kind == 'missing-matches':
                    report.pop('matches')
                elif kind == 'wrong-version':
                    report['descriptor']['version'] = '0.0.0'
                elif kind == 'wrong-source':
                    report['source']['target'] = 'another.json'
                elif kind == 'wrong-db':
                    report['descriptor']['db']['status']['schemaVersion'] = 'old'
                elif kind == 'invalid-db':
                    report['descriptor']['db']['status']['valid'] = False
                elif kind == 'only-fixed':
                    report['descriptor']['configuration']['only-fixed'] = True
                elif kind == 'vex':
                    report['descriptor']['configuration']['vex-documents'] = ['unexpected.json']
                elif kind == 'hidden-suppressions':
                    report['descriptor']['configuration']['show-suppressed'] = False
                elif kind == 'suppressed':
                    report['ignoredMatches'] = [{'id': 'hidden'}]
                else:
                    report['matches'] = [{'vulnerability': {'severity': 'nonsense'}, 'artifact': {}}]
                self.write('build/security/gradle/findings.json', report)
                self.write('build/security/gradle/summary.json', {'passed': True})
                with self.assertRaises((ValueError, KeyError)):
                    security.summarize('gradle', NOW)
                self.assertFalse((output / 'summary.json').exists())

    def test_stale_database_and_modified_sbom_fail_closed(self):
        output, _, _ = self.scan()
        with self.assertRaisesRegex(ValueError, 'stale'):
            security.summarize('gradle', NOW + timedelta(days=6))
        (output / 'bom.cdx.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'modified'):
            security.summarize('gradle', NOW)

    def test_scanner_process_errors_and_exit_result_disagreement_fail_closed(self):
        output, _, _ = self.scan()
        for status in (1, 2, True, None, '0'):
            with self.subTest(status=status):
                self.write('build/security/gradle/scan-status.json', {'exitCode': status})
                with self.assertRaises(ValueError):
                    security.summarize('gradle', NOW)
                self.assertFalse((output / 'summary.json').exists())

    def test_policy_cannot_drop_unfixed_findings_or_inject_output_values(self):
        for changes in ({'includeUnfixed': False}, {'scannerVersion': '1.2.3\nunsafe=x'},
                        {'severityCutoff': 'none'}, {'databaseMaxAgeHours': True}):
            with self.subTest(changes=changes):
                self.write('security/scan-policy.json', {**self.policy, **changes})
                with self.assertRaises(ValueError):
                    security.policy()


if __name__ == '__main__':
    unittest.main()

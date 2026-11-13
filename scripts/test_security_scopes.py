"""Producer scopes retain real transitive membership and fail independently, without network."""
import copy
import json
from pathlib import Path
import subprocess
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch

import maven_security_inventory
import security_scopes as scopes
import security_reports as reports
import test_security_reports as fixtures


class SecurityScopesTest(unittest.TestCase):
    def test_authored_guard_identity_is_allowed_only_in_tooling_and_with_an_exact_source_identity(self):
        candidate = fixtures.component('example', 'candidate')
        guard = fixtures.component('shardpilot', 'maven-tooling-guard')
        guard.update(version='a' * 64, purl='pkg:generic/shardpilot/maven-tooling-guard@' + 'a' * 64)
        guard['bom-ref'] = guard['purl']
        guard['properties'] = [{'name': 'shardpilot:identity-kind', 'value': 'authored-maven-startup-guard'}]
        document = fixtures.bom(candidate, [guard], [(candidate, [guard])])
        reports.validate_bom(document, tooling=True)
        with self.assertRaises(ValueError):
            reports.validate_bom(document)
        document['components'][0]['properties'] = []
        with self.assertRaises(ValueError):
            reports.validate_bom(document, tooling=True)

    def setUp(self):
        self.fixture = fixtures.SecurityReportsTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        self.candidate = fixtures.component('io.github.sjacek', 'shardpilot')

    def test_test_components_keep_native_scope_but_are_included_in_dedicated_scan(self):
        library = fixtures.component('example', 'test-framework')
        library['scope'] = 'excluded'
        library['properties'] = [{'name': 'cdx:maven:package:test', 'value': 'true'}]
        native = fixtures.bom(self.candidate, [library], [(self.candidate, [library])])
        origin = {'project': 'core', 'scope': 'test', 'configurations': ['testRuntimeClasspath']}
        merged = scopes.merge_documents(self.candidate, [(native, origin)])
        component = merged['components'][0]
        self.assertEqual('required', component['scope'])
        self.assertIn({'name': 'shardpilot:native-scope', 'value': 'excluded'}, component['properties'])
        self.assertIn('testRuntimeClasspath', str(component['properties']))
        self.assertEqual('excluded', native['components'][0]['scope'], 'native evidence must remain intact')
        with self.assertRaises(ValueError):
            reports.validate_bom(merged)

    def test_sdk_maven_identity_requires_exact_reviewed_artifact_bytes(self):
        sdk = {'name': 'groovy-4.0.33.jar', 'version': '9.9.1', 'sha256': 'a' * 64,
               'configuration': 'compileClasspath'}
        entry = {'file': sdk['name'], 'group': 'org.apache.groovy', 'name': 'groovy', 'version': '4.0.33',
                 'sha256': sdk['sha256'],
                 'source': 'https://repo.maven.apache.org/maven2/org/apache/groovy/groovy/4.0.33/groovy-4.0.33.jar'}
        registry = {'schemaVersion': 1, 'artifacts': [entry]}
        component = scopes.sdk_component(sdk, registry)
        self.assertEqual('pkg:maven/org.apache.groovy/groovy@4.0.33?type=jar', component['purl'])
        self.assertEqual('4.0.33', component['version'], 'a library version must not be replaced by the Gradle version')
        self.assertIn({'name': 'shardpilot:provided-by-gradle-version', 'value': '9.9.1'}, component['properties'])
        for change in ('hash', 'source', 'coordinate', 'duplicate'):
            value = copy.deepcopy(registry)
            if change == 'hash':
                value['artifacts'][0]['sha256'] = 'b' * 64
            elif change == 'source':
                value['artifacts'][0]['source'] = 'https://unreviewed.invalid/groovy.jar'
            elif change == 'coordinate':
                value['artifacts'][0]['version'] = '4.0.32'
                value['artifacts'][0]['source'] = entry['source'].replace('/4.0.33/', '/4.0.32/')
            else:
                value['artifacts'].append(copy.deepcopy(entry))
            with self.subTest(change=change), self.assertRaises(ValueError):
                scopes.sdk_component(sdk, value)

    def test_sdk_generated_files_remain_explicit_and_unreviewed_libraries_block_preparation(self):
        sdk = {'name': 'gradle-api-9.9.1.jar', 'version': '9.9.1', 'sha256': 'a' * 64,
               'configuration': 'compileClasspath'}
        registry = {'schemaVersion': 1, 'artifacts': []}
        component = scopes.sdk_component(sdk, registry)
        self.assertTrue(component['purl'].startswith('pkg:generic/gradle/'))
        sdk['name'] = 'unknown-library-1.0.jar'
        with self.assertRaisesRegex(ValueError, 'Unreviewed third-party'):
            scopes.sdk_component(sdk, registry)

    def test_scope_merge_preserves_multiple_origins_and_rejects_conflicting_bytes(self):
        library = fixtures.component('example', 'shared-library')
        library['hashes'] = [{'alg': 'SHA-256', 'content': 'a' * 64}]
        native = fixtures.bom(self.candidate, [library], [(self.candidate, [library])])
        other = copy.deepcopy(native)
        sources = [(native, {'project': 'core'}), (other, {'project': 'adapter'})]
        merged = scopes.merge_documents(self.candidate, sources)
        self.assertEqual(1, len(merged['components']))
        self.assertEqual(2, len([p for p in merged['components'][0]['properties'] if p['name'] == 'shardpilot:origin']))
        other['components'][0]['hashes'][0]['content'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'Conflicting'):
            scopes.merge_documents(self.candidate, sources)

    def test_gradle_buildscript_root_is_an_alias_not_an_unidentified_dependency(self):
        detached = fixtures.component('unspecified', 'unspecified')
        detached.update({'version': 'unspecified', 'purl': 'pkg:maven/unspecified/unspecified@unspecified',
                         'bom-ref': 'detached'})
        library = fixtures.component('example', 'producer-plugin')
        native = fixtures.bom(self.candidate, [detached, library], [(detached, [library])])
        result = scopes.merge_documents(self.candidate, [(native, {'scope': 'build', 'configurations': ['classpath']})])
        self.assertEqual(['producer-plugin'], [item['name'] for item in result['components']])
        with self.assertRaisesRegex(ValueError, 'unreachable'):
            scopes.merge_documents(self.candidate, [(native, {'scope': 'test'})])

    def test_provided_scope_retains_transitive_compile_libraries_and_drops_unrelated_runtime(self):
        api = fixtures.component('example', 'host-api')
        transitive = fixtures.component('example', 'host-api-transitive')
        runtime = fixtures.component('example', 'own-runtime')
        native = fixtures.bom(self.candidate, [api, transitive, runtime],
                              [(self.candidate, [api, runtime]), (api, [transitive])])
        tree = {'children': [{'groupId': 'example', 'artifactId': 'host-api', 'version': fixtures.VERSION,
                              'scope': 'provided'}]}
        result = scopes.select_provided(native, tree)
        self.assertEqual({'host-api', 'host-api-transitive'}, {item['name'] for item in result['components']})
        reports.validate_bom(result)
        tree['children'][0]['artifactId'] = 'missing-api'
        with self.assertRaisesRegex(ValueError, 'missing'):
            scopes.select_provided(result, tree)

    def test_maven_plugin_realms_bind_resolved_files_include_classifiers_and_fail_on_partial_reports(self):
        plugin = self.fixture.jar('repo/plugin.jar', {})
        dependency = self.fixture.jar('repo/library-tests.jar', {})
        path = self.root / 'plugins.raw.txt'
        path.write_text('The following plugins have been resolved:\n'
                        f'   example:plugin:jar:1.0:{plugin}\n'
                        f'      example:library:jar:tests:2.0:{dependency}\n', encoding='utf-8')
        result = scopes.maven_build_inventory(path, self.candidate)
        self.assertEqual(2, len(result['components']))
        classified = next(item for item in result['components'] if item['name'] == 'library')
        self.assertEqual('2.0', classified['version'])
        self.assertTrue(classified['purl'].endswith('?classifier=tests'))
        self.assertEqual(reports.sha256(dependency), classified['hashes'][0]['content'])
        reports.validate_bom(result)
        dependency.unlink()
        with self.assertRaisesRegex(ValueError, 'missing'):
            scopes.maven_build_inventory(path, self.candidate)
        for text in ('', 'The following plugins have been resolved:\n',
                     'The following plugins have been resolved:\n unexpected output\n'):
            path.write_text(text, encoding='utf-8')
            with self.assertRaises(ValueError):
                scopes.maven_build_inventory(path, self.candidate)

    def test_maven_release_model_is_only_resolved_and_never_executes_release_phases(self):
        with patch.object(maven_security_inventory, 'maven') as maven, \
                patch.object(maven_security_inventory, 'effective_tooling_model') as effective_model, \
                patch.object(maven_security_inventory, 'fixture_inventories') as fixtures_inventory:
            maven_security_inventory.main()
        effective_model.assert_called_once_with()
        fixtures_inventory.assert_called_once_with()
        self.assertEqual(('-Psecurity-sbom', '-DskipTests', '-DskipITs', 'install'), maven.call_args_list[0].args)
        self.assertEqual(('-Psecurity-sbom,central-release', '-Dcentral.skipUpload=true',
                          'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins'),
                         maven.call_args_list[1].args)
        maven.side_effect = subprocess.CalledProcessError(1, 'native resolution')
        with patch.object(maven_security_inventory, 'maven', maven), self.assertRaises(subprocess.CalledProcessError):
            maven_security_inventory.resolve_plugins()

    def test_effective_tooling_model_distinguishes_site_bindings_without_retaining_settings(self):
        path = self.root / 'effective-pom.xml'
        plugin = '''<plugin><artifactId>maven-site-plugin</artifactId><version>3.22.0</version>
            <configuration><password>private-value</password></configuration></plugin>'''
        bound = plugin.replace('</plugin>', '''<executions><execution><id>default-site</id><phase>site</phase>
            <goals><goal>site</goal></goals></execution></executions></plugin>''')
        project = '''<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>example</artifactId>
            <properties><credential>private-value</credential></properties><build><pluginManagement><plugins>'''
        path.write_text('<projects>' + project + plugin + '</plugins></pluginManagement><plugins>'
                        + bound + '</plugins></build></project></projects>', encoding='utf-8')
        report = maven_security_inventory.tooling_model(path)
        self.assertNotIn('private-value', json.dumps(report))
        self.assertNotIn('password', json.dumps(report))
        entry = report['projects'][0]['plugins'][0]
        self.assertEqual('effective-build-plugin', entry['modelRole'])
        self.assertEqual('site-lifecycle-only', entry['binding'])
        self.assertEqual('org.apache.maven.plugins', entry['groupId'])
        self.assertEqual([{'id': 'default-site', 'phase': 'site', 'goals': ['site']}], entry['executions'])
        # An explicit execution outside the site lifecycle must invalidate the site-only label.
        path.write_text(path.read_text(encoding='utf-8').replace('<phase>site</phase>', '<phase>verify</phase>'), encoding='utf-8')
        self.assertEqual('other-or-unspecified', maven_security_inventory.tooling_model(path)['projects'][0]['plugins'][0]['binding'])
        path.write_text(project + plugin + '</plugins></pluginManagement></build></project>', encoding='utf-8')
        self.assertEqual('managed-only', maven_security_inventory.tooling_model(path)['projects'][0]['plugins'][0]['modelRole'])
        for invalid in ('<projects/>', '<projects><project/></projects>',
                        path.read_text(encoding='utf-8').replace('<version>3.22.0</version>', '')):
            path.write_text(invalid, encoding='utf-8')
            with self.assertRaises(ValueError):
                maven_security_inventory.tooling_model(path)

    def test_effective_tooling_generation_removes_complete_model_and_invalidates_old_reports_on_failure(self):
        output = self.root / 'build/reports/security/maven/tooling-model.json'
        models = []

        def resolve(*arguments):
            self.assertEqual(('-Psecurity-sbom,central-release', '-Dcentral.skipUpload=true',
                              'org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom'), arguments[:3])
            path = Path(arguments[-1].removeprefix('-Doutput='))
            models.append(path)
            path.write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>example</artifactId>
                <build><pluginManagement><plugins><plugin><artifactId>maven-site-plugin</artifactId>
                <version>3.22.0</version></plugin></plugins></pluginManagement></build></project>''', encoding='utf-8')

        with patch.object(maven_security_inventory, 'ROOT', self.root), \
                patch.object(maven_security_inventory, 'maven', side_effect=resolve):
            maven_security_inventory.effective_tooling_model()
        self.assertTrue(output.is_file())
        self.assertFalse(models[0].exists(), 'the complete model must not become an uploaded artifact')
        with patch.object(maven_security_inventory, 'ROOT', self.root), \
                patch.object(maven_security_inventory, 'maven', side_effect=subprocess.CalledProcessError(1, 'Maven')):
            with self.assertRaises(subprocess.CalledProcessError):
                maven_security_inventory.effective_tooling_model()
        self.assertFalse(output.exists(), 'a failed native goal must not leave stale explanatory evidence')

    def test_native_maven_fixture_tree_keeps_launcher_and_hashes_only_selected_artifacts(self):
        repository = self.root / 'repository'
        artifact = self.fixture.jar('repository/org.junit.platform/junit-platform-launcher/6.1.3/unused.jar', {})
        selected = repository / 'org/junit/platform/junit-platform-launcher/6.1.3/junit-platform-launcher-6.1.3.jar'
        selected.parent.mkdir(parents=True)
        selected.write_bytes(artifact.read_bytes())
        tree = {'groupId': 'example', 'artifactId': 'fixture', 'version': '1', 'type': 'pom',
                'children': [{'groupId': 'org.junit.platform', 'artifactId': 'junit-platform-launcher',
                              'version': '6.1.3', 'scope': 'test', 'type': 'jar'}]}
        native = scopes.maven_fixture_inventory(tree, repository)
        selected_component = copy.deepcopy(native['components'][0])
        reactor = fixtures.bom(self.candidate, [selected_component], [(self.candidate, [selected_component])])
        result = scopes.merge_documents(self.candidate, [(reactor, {'scope': 'test', 'project': 'reactor'}),
                                                        (native, {'scope': 'test', 'project': 'fixture'})])
        self.assertEqual(['junit-platform-launcher'], [item['name'] for item in result['components']])
        self.assertEqual(reports.sha256(selected), result['components'][0]['hashes'][0]['content'])
        selected.unlink()
        with self.assertRaises(FileNotFoundError):
            scopes.maven_fixture_inventory(tree, repository)

    def test_fixture_generation_resolves_pom_only_models_and_invalidates_failed_runs(self):
        pom = '''<project xmlns="http://maven.apache.org/POM/4.0.0"><version>0.4.0</version>
            <properties><junit.version>6.1.3</junit.version><surefire.version>3.6.1</surefire.version></properties></project>'''
        (self.root / 'pom.xml').write_text(pom, encoding='utf-8')
        for source in ('shardpilot-maven-plugin/src/it', 'examples/maven'):
            target = self.root / source / 'fixture'
            target.mkdir(parents=True)
            (target / 'pom.xml').write_text(pom.replace('0.4.0', '@project.version@'), encoding='utf-8')
            (target / 'verify.groovy').write_text('must never run', encoding='utf-8')

        def resolve(*arguments):
            self.assertEqual('org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree', arguments[2])
            self.assertEqual(('-DoutputType=json', '-DoutputFile=security-dependencies.raw.json'), arguments[3:])
            model = Path(arguments[1])
            self.assertNotIn('@project.version@', model.read_text(encoding='utf-8'))
            self.assertFalse(model.with_name('verify.groovy').exists())
            self.fixture.write(str(model.parent.relative_to(self.root) / 'security-dependencies.raw.json'),
                               {'groupId': 'example', 'artifactId': 'fixture', 'version': '1'})

        with patch.object(maven_security_inventory, 'ROOT', self.root), \
                patch.object(maven_security_inventory, 'maven', side_effect=resolve) as maven:
            maven_security_inventory.fixture_inventories()
            self.assertEqual(3, maven.call_count)
            provider = ET.parse(self.root / 'build/security-fixtures/maven/native-provider/pom.xml')
            self.assertEqual('surefire-junit-platform', provider.findtext('m:dependencies/m:dependency/m:artifactId',
                                                                         namespaces=maven_security_inventory.NS))
            self.assertEqual('3.6.1', provider.findtext('m:dependencies/m:dependency/m:version',
                                                       namespaces=maven_security_inventory.NS))
            manifest = self.root / 'build/reports/security/maven/test/fixtures.json'
            self.assertEqual(3, len(reports.read_json(manifest)['fixtures']))
            maven.side_effect = subprocess.CalledProcessError(1, 'native tree resolution')
            with self.assertRaises(subprocess.CalledProcessError):
                maven_security_inventory.fixture_inventories()
            self.assertFalse(manifest.exists())

    def seed_scans(self, distribution='gradle'):
        base, native, _ = self.fixture.scan()
        inventory = reports.read_json(base / 'inventory.json')
        if distribution == 'maven':
            inventory['distribution'] = distribution
            reports.write_json(base / 'inventory.json', inventory)
        for scope in scopes.scan_scopes(distribution)[1:]:
            output = base / scope
            document = reports.read_json(base / 'bom.cdx.json')
            reports.write_json(output / 'bom.cdx.json', document)
            evidence = output / 'native/raw.json'
            reports.write_json(evidence, {'native': True})
            scoped = {**inventory, 'scope': scope, 'candidateArtifacts': inventory['artifacts'],
                      'sbomSha256': reports.sha256(output / 'bom.cdx.json'),
                      'inputs': [{'retainedFile': evidence.relative_to(base).as_posix(),
                                  'sha256': reports.sha256(evidence)}]}
            reports.write_json(output / 'inventory.json', scoped)
            report = copy.deepcopy(native)
            report['source']['target'] = str(output / 'bom.cdx.json')
            reports.write_json(output / 'findings.json', report)
            reports.write_json(output / 'scan-status.json', {'exitCode': 0})
        return base

    def test_full_maven_findings_are_visible_but_missing_or_invalid_diagnostics_fail_closed(self):
        # Use the same synthetic candidate fixture, relocating it to the Maven report boundary.
        original = self.seed_scans('maven')
        base = scopes.directory('maven', 'runtime')
        original.rename(base)
        for scope in scopes.scan_scopes('maven'):
            output = scopes.directory('maven', scope)
            inventory = reports.read_json(output / 'inventory.json')
            if scope != 'runtime':
                for entry in inventory['inputs']:
                    old = original / entry['retainedFile']
                    entry['retainedFile'] = old.relative_to(original).as_posix()
            reports.write_json(output / 'inventory.json', inventory)
            findings = reports.read_json(output / 'findings.json')
            findings['source']['target'] = str(output / 'bom.cdx.json')
            reports.write_json(output / 'findings.json', findings)
        output = base / 'build-full'
        findings = reports.read_json(output / 'findings.json')
        findings['matches'] = [{'vulnerability': {'id': 'CVE-diagnostic', 'severity': 'High'},
                                'artifact': {'name': 'unused-server'}}]
        reports.write_json(output / 'findings.json', findings)
        reports.write_json(output / 'scan-status.json', {'exitCode': 2})
        self.assertEqual(0, scopes.summarize('maven', now=fixtures.NOW))
        summary = reports.read_json(base / 'scopes-summary.json')
        self.assertTrue(summary['passed'])
        self.assertEqual(1, summary['scopes']['build-full']['blockingMatches'])
        self.assertFalse(summary['scopes']['build-full']['passed'])
        # Scanner failures are not findings and must never be downgraded to diagnostic results.
        reports.write_json(output / 'scan-status.json', {'exitCode': 1})
        self.assertEqual(1, scopes.summarize('maven', now=fixtures.NOW))
        (output / 'findings.json').unlink()
        self.assertEqual(1, scopes.summarize('maven', now=fixtures.NOW))

    def test_maven_scan_keeps_full_findings_diagnostic_but_native_errors_and_active_findings_block(self):
        original = self.seed_scans('maven')
        original.rename(scopes.directory('maven', 'runtime'))
        for statuses, expected in (((0, 0, 0, 0, 2), 0), ((0, 0, 0, 0, 1), 1), ((0, 2, 0, 0, 2), 1)):
            with self.subTest(statuses=statuses), patch.object(scopes.subprocess, 'run') as run:
                run.side_effect = [subprocess.CompletedProcess([], status) for status in statuses]
                self.assertEqual(expected, scopes.scan('maven', 'grype'))
                self.assertEqual(5, run.call_count)
                self.assertTrue((scopes.directory('maven', 'build-full') / 'scan-status.json').is_file())

    def test_high_build_finding_does_not_prevent_scanning_test_and_provided_scopes(self):
        base = self.seed_scans()
        with patch.object(scopes.subprocess, 'run') as run:
            run.side_effect = [subprocess.CompletedProcess([], status) for status in (0, 2, 0, 0)]
            self.assertEqual(1, scopes.scan('gradle', 'grype'))
        self.assertEqual(4, run.call_count)
        for scope, call in zip(scopes.SCOPES, run.call_args_list):
            self.assertIn('sbom:' + str(scopes.directory('gradle', scope) / 'bom.cdx.json'), call.args[0])
            self.assertIn('--only-fixed=false', call.args[0])
            self.assertEqual('high', call.args[0][call.args[0].index('--fail-on') + 1])
        self.assertEqual(2, reports.read_json(base / 'build/scan-status.json')['exitCode'])
        self.assertTrue((base / 'provided/scan-status.json').is_file())

    def test_missing_sbom_or_scanner_error_cannot_skip_later_scopes(self):
        base = self.seed_scans()
        (base / 'build/inventory.json').unlink()
        with patch.object(scopes.subprocess, 'run') as run:
            run.side_effect = [OSError('scanner unavailable'), subprocess.CompletedProcess([], 0),
                               subprocess.CompletedProcess([], 0)]
            self.assertEqual(1, scopes.scan('gradle', 'missing-scanner'))
        self.assertEqual(3, run.call_count)
        self.assertEqual(1, reports.read_json(base / 'scan-status.json')['exitCode'])
        self.assertEqual(1, reports.read_json(base / 'build/scan-status.json')['exitCode'])
        self.assertEqual(0, reports.read_json(base / 'provided/scan-status.json')['exitCode'])

    def test_all_scopes_are_required_and_an_unfixed_test_finding_blocks_the_aggregate(self):
        base = self.seed_scans()
        self.assertEqual(0, scopes.summarize('gradle', now=fixtures.NOW))
        report = reports.read_json(base / 'test/findings.json')
        report['matches'] = [{'vulnerability': {'id': 'CVE-synthetic', 'severity': 'High',
                                               'fix': {'state': 'not-fixed', 'versions': []}},
                              'artifact': {'name': 'test-framework'}}]
        reports.write_json(base / 'test/findings.json', report)
        reports.write_json(base / 'test/scan-status.json', {'exitCode': 2})
        self.assertEqual(1, scopes.summarize('gradle', now=fixtures.NOW))
        aggregate = reports.read_json(base / 'scopes-summary.json')
        self.assertFalse(aggregate['passed'])
        self.assertEqual(set(scopes.SCOPES), set(aggregate['scopes']))
        self.assertTrue(aggregate['scopes']['provided']['passed'])
        self.assertEqual(1, aggregate['scopes']['test']['blockingMatches'])
        (base / 'provided/findings.json').unlink()
        self.assertEqual(1, scopes.summarize('gradle', now=fixtures.NOW))
        self.assertFalse((base / 'provided/summary.json').exists())

    def test_other_commit_candidate_scope_or_changed_native_evidence_cannot_leave_green_summaries(self):
        base = self.seed_scans()
        path = base / 'build/inventory.json'
        original = reports.read_json(path)
        for kind in ('commit', 'candidate', 'scope', 'native', 'missing-evidence'):
            with self.subTest(kind=kind):
                value = copy.deepcopy(original)
                if kind == 'commit':
                    value['sourceCommit'] = 'b' * 40
                elif kind == 'candidate':
                    value['candidateArtifacts'] = []
                elif kind == 'scope':
                    value['scope'] = 'provided'
                elif kind == 'missing-evidence':
                    value['inputs'] = []
                else:
                    value['inputs'][0]['sha256'] = '0' * 64
                reports.write_json(path, value)
                self.assertEqual(1, scopes.summarize('gradle', now=fixtures.NOW))
                self.assertFalse(reports.read_json(base / 'scopes-summary.json')['passed'])
                self.assertFalse((base / 'build/summary.json').exists())

    def test_failed_preparation_invalidates_all_old_scope_findings_and_aggregate(self):
        base = self.seed_scans()
        reports.write_json(base / 'scopes-summary.json', {'passed': True})
        (self.root / 'shardpilot-gradle-plugin/build/reports/security/gradle/bom.raw.json').unlink()
        with self.assertRaises(FileNotFoundError):
            scopes.prepare('gradle', fixtures.COMMIT)
        self.assertFalse((base / 'scopes-summary.json').exists())
        for scope in scopes.SCOPES:
            self.assertFalse((scopes.directory('gradle', scope) / 'findings.json').exists())


if __name__ == '__main__':
    unittest.main()

"""Packaged consumer trust, clean-cache boundaries and current release consistency."""
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import release_consumers as release
import run_selection_workflow as workflow


class ReleaseConsumerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.repository = self.root / 'repository'
        self.repository.mkdir()
        self.version = release.version()
        self.manifest = self.root / 'publication.json'
        for relative in release.artifact_paths(self.version):
            file = self.repository / relative
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(b'packaged-fixture-testdata')
            if release.MARKER in relative:
                file.write_text(f'<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies><dependency>'
                                f'<groupId>io.github.sjacek</groupId><artifactId>shardpilot</artifactId>'
                                f'<version>{self.version}</version></dependency></dependencies></project>')
            elif relative.endswith('.module'):
                file.write_text(json.dumps({'component': {'group': 'io.github.sjacek', 'module': 'shardpilot', 'version': self.version},
                                            'variants': []}))
        self.seal()

    def seal(self):
        self.manifest.write_text(json.dumps({'schemaVersion': 1, 'version': self.version,
            'source': 'candidate-publication', 'provenance': {'fixture': True}, 'files': {
                name: {'sha256': release.digest(self.repository / name), 'bytes': (self.repository / name).stat().st_size}
                for name in release.artifact_paths(self.version)}}))

    def test_complete_candidate_has_binary_metadata_and_source_artifacts(self):
        data = release.verify_receipt(self.manifest)
        self.assertEqual(15, len(data['files']))
        self.assertEqual(3, sum(name.endswith('-sources.jar') for name in data['files']))
        self.assertEqual(3, sum(name.endswith('-javadoc.jar') for name in data['files']))
        self.assertIn(f'{release.GROUP}/shardpilot/{self.version}/shardpilot-{self.version}.module', data['files'])

    def test_missing_changed_extra_and_foreign_version_artifacts_fail(self):
        path = self.repository / release.artifact_paths(self.version)[0]
        original = path.read_bytes()
        path.write_bytes(original + b'changed')
        with self.assertRaisesRegex(ValueError, 'changed'):
            release.verify_receipt(self.manifest)
        path.write_bytes(original)
        path.unlink()
        with self.assertRaisesRegex(ValueError, 'file set'):
            release.verify_receipt(self.manifest)
        path.write_bytes(original)
        (self.repository / 'extra.jar').write_bytes(b'extra')
        with self.assertRaisesRegex(ValueError, 'file set'):
            release.verify_receipt(self.manifest)

    def test_marker_cannot_redirect_to_an_old_cached_implementation(self):
        marker = next(self.repository.rglob(release.MARKER + '-' + self.version + '.pom'))
        marker.write_text(marker.read_text().replace('<version>' + self.version, '<version>0.0.1'))
        self.seal()
        with self.assertRaisesRegex(ValueError, 'marker'):
            release.verify_receipt(self.manifest)

    def test_duplicate_receipt_keys_and_escaping_paths_fail(self):
        original = self.manifest.read_text()
        self.manifest.write_text(original.replace('"schemaVersion": 1', '"schemaVersion": 1, "schemaVersion": 1'))
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            release.verify_receipt(self.manifest)
        value = json.loads(original)
        value['files']['../outside'] = {'bytes': 1, 'sha256': 'a' * 64}
        self.manifest.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            release.verify_receipt(self.manifest)

    def test_gradle_module_cannot_bind_a_different_binary_or_version(self):
        module = next(self.repository.rglob('*.module'))
        original = json.loads(module.read_text())
        modified = dict(original)
        modified['variants'] = [{'files': [{'name': f'shardpilot-{self.version}.jar', 'sha256': '0' * 64}]}]
        module.write_text(json.dumps(modified))
        self.seal()
        with self.assertRaisesRegex(ValueError, 'module artifact checksum'):
            release.verify_receipt(self.manifest)
        original['component']['version'] = '0.0.1'
        module.write_text(json.dumps(original))
        self.seal()
        with self.assertRaisesRegex(ValueError, 'another implementation'):
            release.verify_receipt(self.manifest)

    def test_native_gradle_verification_keeps_third_party_policy_and_exact_candidate_pins(self):
        path = self.root / 'verification-metadata.xml'
        path.write_text(f'<verification-metadata xmlns="{release.GRADLE_NS}"><configuration>'
                        '<verify-metadata>true</verify-metadata></configuration><components>'
                        '<component group="example.thirdparty" name="library" version="1"><artifact name="library.jar">'
                        '<sha256 value="' + 'a' * 64 + '"/></artifact></component></components></verification-metadata>')
        release.gradle_checksums(self.manifest, path)
        xml = ET.parse(path).getroot()
        self.assertIsNone(xml.find('.//{' + release.GRADLE_NS + '}trusted-artifacts'))
        self.assertEqual('true', xml.find('.//{' + release.GRADLE_NS + '}verify-metadata').text)
        sums = xml.findall('.//{' + release.GRADLE_NS + '}sha256')
        self.assertEqual(7, len(sums))
        self.assertIn('a' * 64, [row.attrib['value'] for row in sums])
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            release.gradle_checksums(self.manifest, path)

    def test_maven_local_candidates_must_match_the_independent_receipt(self):
        local = self.root / 'fresh-maven'
        shutil.copytree(self.repository, local)
        release.verify_maven_candidates(self.manifest, local)
        binary = next(local.rglob('shardpilot-core-' + self.version + '.jar'))
        binary.write_bytes(b'altered-cached-candidate')
        with self.assertRaisesRegex(ValueError, 'differs'):
            release.verify_maven_candidates(self.manifest, local)

    def test_download_seed_excludes_our_binaries_settings_and_build_outputs(self):
        source = self.root / 'seed'
        paths = ['caches/modules-2/files-2.1/org.snakeyaml/snakeyaml-engine/3.2/hash/snakeyaml-engine-3.2.jar',
                 'caches/modules-2/files-2.1/io.github.sjacek/shardpilot/1/hash/plugin.jar',
                 'caches/modules-2/files-2.1/io.github.sjacek.shardpilot/marker/1/marker.pom',
                 'caches/build-cache-1/output', 'gradle.properties', 'settings.xml', 'daemon/state']
        for name in paths:
            file = source / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(b'fixture')
        target = self.root / 'home'
        release.seed_downloads(source, target, 'gradle')
        self.assertEqual([paths[0]], [p.relative_to(target).as_posix() for p in target.rglob('*') if p.is_file()])

    def test_fresh_gradle_driver_uses_packaged_coordinates_without_source_substitution(self):
        output = self.root / 'consumer'
        with patch.object(workflow.subprocess, 'check_output', return_value='a' * 40), \
                patch.object(workflow, 'observe', return_value={'exitCode': 1}) as observed:
            with self.assertRaisesRegex(RuntimeError, 'planning.log'):
                workflow.run('gradle', output, ['gradle'], publication=self.manifest)
        command = observed.call_args.args[0]
        self.assertIn('-Pexample.pluginRepository=' + str(self.repository), command)
        self.assertFalse(any('pluginSourceDirectory' in argument for argument in command))
        self.assertIn('--dependency-verification=strict', command)
        self.assertIn('--offline', command)

    def test_registry_mode_requires_completed_audit_and_matching_version(self):
        registry = self.root / 'registry'
        registry.mkdir()
        (registry / 'registry-verification.json').write_text(json.dumps({'version': '0.0.1'}))
        with patch('run_published_artifact_audit.summarize') as audit, patch.object(release, 'launch') as launch:
            with self.assertRaisesRegex(ValueError, 'version differs'):
                release.prepare(self.root / 'import', ['gradle'], ['maven'], self.root / 'm2', registry)
            audit.assert_called_once_with(registry)
            launch.assert_not_called()

    def test_existing_outputs_and_unknown_profiles_do_not_start_tools(self):
        with patch.object(workflow, 'observe') as observed:
            with self.assertRaisesRegex(ValueError, 'profile'):
                workflow.run('gradle', self.root / 'new', ['gradle'], profile='../escape')
            observed.assert_not_called()
        with self.assertRaisesRegex(ValueError, 'fresh'):
            release.accept(self.root, [], [], self.root, self.root)

    def test_full_and_subset_execution_evidence_preserve_the_bridge_mode(self):
        evidence = self.root / 'evidence'
        evidence.mkdir()
        (evidence / 'eligibility.yaml').write_text('complete-eligibility-testdata')
        for mode in ('full', 'explicit'):
            (evidence / 'selection.json').write_text(json.dumps({'schemaVersion': 2, 'mode': mode}))
            binding = workflow.execution_selection(evidence)
            self.assertEqual(mode, binding['mode'])
            self.assertEqual(release.digest(evidence / 'selection.json'), binding['sha256'])
        (evidence / 'selection.json').write_text('{"mode":"unknown"}')
        with self.assertRaisesRegex(ValueError, 'mode'):
            workflow.execution_selection(evidence)


if __name__ == '__main__':
    unittest.main()

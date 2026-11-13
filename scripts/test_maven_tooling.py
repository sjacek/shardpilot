"""Plugin ownership and default Site bindings define scope, never an advisory/library denylist."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import maven_tooling as tooling


class MavenToolingTest(unittest.TestCase):
    def setUp(self):
        self.value = tooling.policy()

    def test_shared_dependencies_remain_blocking_and_full_graph_is_unchanged(self):
        active = self.component('active', 'org.apache.maven.plugins', 'maven-compiler-plugin', ['active'])
        site = self.component('site', 'org.apache.maven.plugins', 'maven-site-plugin', ['site'])
        shared = self.component('shared', 'example', 'shared-library', ['site', 'active'])
        server = self.component('server', 'org.eclipse.jetty', 'jetty-http', ['site'])
        full = {'metadata': {'component': {'bom-ref': 'candidate'}}, 'components': [active, site, shared, server]}
        original = copy.deepcopy(full)
        selected = tooling.select_blocking(full, self.value)
        self.assertEqual({'active', 'shared'}, {c['purl'] for c in selected['components']})
        self.assertEqual(original, full)
        full['components'][0]['name'] = 'new-unreviewed-plugin'
        with self.assertRaisesRegex(ValueError, 'Unclassified'):
            tooling.select_blocking(full, self.value)
        full['components'][0]['name'] = 'maven-compiler-plugin'
        full['components'][-1]['properties'] = []
        with self.assertRaisesRegex(ValueError, 'no owning'):
            tooling.select_blocking(full, self.value)

    @staticmethod
    def component(ref, group, name, owners):
        return {'purl': ref, 'bom-ref': ref, 'group': group, 'name': name,
                'properties': [{'name': 'shardpilot:maven-plugin-realm', 'value': owner} for owner in owners]}

    def test_unknown_models_and_site_execution_changes_are_rejected(self):
        site = {'groupId': 'org.apache.maven.plugins', 'artifactId': 'maven-site-plugin',
                'executions': [{'id': 'default-site', 'phase': 'site', 'goals': ['site']}]}
        model = {'projects': [{'plugins': [site]}]}
        tooling.review_model(model, self.value)
        for field, value in (('phase', 'verify'), ('goals', ['run']), ('id', 'custom-site')):
            changed = copy.deepcopy(model)
            changed['projects'][0]['plugins'][0]['executions'][0][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'Site execution'):
                tooling.review_model(changed, self.value)
        model['projects'][0]['plugins'][0]['artifactId'] = 'unknown-plugin'
        with self.assertRaisesRegex(ValueError, 'Unclassified'):
            tooling.review_model(model, self.value)

    def test_policy_rejects_ambiguous_classifications_and_arbitrary_inherited_plugins(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / tooling.POLICY
            path.parent.mkdir()
            for change in ('duplicate', 'inherited', 'missing-site'):
                value = copy.deepcopy(self.value)
                if change == 'duplicate':
                    value['plugins'].append(value['plugins'][0])
                elif change == 'inherited':
                    value['plugins'][0].update(scope='inherited', reason='not enough')
                else:
                    value['plugins'] = value['plugins'][:-1]
                path.write_text(json.dumps(value), encoding='utf-8')
                with self.subTest(change=change), self.assertRaises(ValueError):
                    tooling.policy(root)

    def test_guard_bootstrap_verifies_api_bytes_and_packages_only_authored_code_and_policy(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            policy = root / tooling.POLICY
            policy.parent.mkdir()
            policy.write_text(json.dumps(self.value), encoding='utf-8')
            source = root / 'build-logic/maven-tooling-guard/MavenToolingGuard.java'
            source.parent.mkdir(parents=True)
            source.write_text('authored test source, not executed', encoding='utf-8')
            baseline = root / '.mvn/checksums.sha256'
            baseline.parent.mkdir()
            entries = []
            for name in ('maven-core', 'maven-model', 'maven-artifact'):
                version = self.value['guardApiVersion']
                path = root / f'build/ci-dependencies/maven/org/apache/maven/{name}/{version}/{name}-{version}.jar'
                path.parent.mkdir(parents=True)
                path.write_bytes(b'known API fixture')
                entries.append(f'org.apache.maven:{name}:jar:{version} ' + hashlib.sha256(path.read_bytes()).hexdigest())
            baseline.write_text('\n'.join(entries) + '\n', encoding='utf-8')

            def compile_guard(command, **options):
                self.assertEqual('17', command[command.index('--release') + 1])
                self.assertTrue(options['check'])
                (Path(command[command.index('-d') + 1]) / 'Guard.class').write_bytes(b'authored compiled fixture')

            with patch.object(tooling.subprocess, 'run', side_effect=compile_guard), \
                    patch.object(tooling.shutil, 'which', return_value='javac'), \
                    patch.object(tooling.urllib.request, 'urlopen') as download:
                jar = tooling.guard(root)
                with zipfile.ZipFile(jar) as archive:
                    self.assertIn('plugin.org.apache.maven.plugins\\:maven-site-plugin=inherited',
                                  archive.read('maven-tooling.properties').decode())
                    self.assertEqual('authored compiled fixture', archive.read('Guard.class').decode())
                    self.assertEqual(4, len(archive.namelist()), 'Maven API classes must not be bundled')
                original = jar.read_bytes()
                self.assertEqual(jar, tooling.guard(root))
                self.assertEqual(original, jar.read_bytes(), 'identical inputs must produce identical guard bytes')
                download.assert_not_called()
                (jar.parent / ('maven-core-' + self.value['guardApiVersion'] + '.jar')).write_bytes(b'changed cached API')
                with self.assertRaisesRegex(ValueError, 'Cached Maven guard API checksum'):
                    tooling.guard(root)
                download.assert_not_called()


if __name__ == '__main__':
    unittest.main()

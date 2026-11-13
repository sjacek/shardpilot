"""Negative controls for public-byte validation and outbound-syscall classification."""
import hashlib
import io
import json
import os
from pathlib import Path
import runpy
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import published_artifacts as artifacts
import run_published_artifact_audit as launcher
import published_audit_report as reporting
import audit_runner
from audit_runner import fixtures, network_attempts, signer_cohort, tag_binding


class RegistryChecksTest(unittest.TestCase):
    def test_verified_checksums_accept_filename_suffixes_and_reject_modified_bytes(self):
        for algorithm in ('sha256', 'sha512'):
            value = hashlib.new(algorithm, b'published').hexdigest()
            self.assertEqual(value, artifacts.checked_checksum(b'published', (value + '  file.jar\n').encode(), algorithm))
            with self.assertRaises(ValueError):
                artifacts.checked_checksum(b'modified', value.encode(), algorithm)

    def test_empty_truncated_and_nonhex_checksums_are_rejected(self):
        for value in (b'', b'  ', b'abcd', b'g' * 64, b'a' * 63, b'a' * 65):
            with self.subTest(value=value), self.assertRaises(ValueError):
                artifacts.checked_checksum(b'published', value, 'sha256')

    def test_archive_paths_cannot_escape_their_namespace(self):
        for name in ('../outside', '/absolute', 'C:/outside', 'a/../../outside', 'a\\outside'):
            # ZipFile's Windows writer normalizes backslashes before storage. Model a raw
            # directory entry read from an archive produced on another platform instead.
            entry = zipfile.ZipInfo(name)
            with self.subTest(name=name), self.assertRaises(ValueError):
                artifacts.safe_archive(Mock(infolist=Mock(return_value=[entry])))

    def test_duplicate_entries_symlinks_and_excessive_expansion_are_rejected(self):
        duplicate = zipfile.ZipInfo('a')
        symlink = zipfile.ZipInfo('link')
        symlink.external_attr = 0o120777 << 16
        oversized = zipfile.ZipInfo('large')
        oversized.file_size = 64 * 1024 * 1024 + 1
        for entries in ([duplicate, duplicate], [symlink], [oversized]):
            with self.subTest(entries=entries), self.assertRaises(ValueError):
                artifacts.safe_archive(Mock(infolist=Mock(return_value=entries)))

    def test_valid_archives_remain_readable(self):
        with io.BytesIO() as data:
            with zipfile.ZipFile(data, 'w') as archive:
                archive.writestr('example/Test.java', b'package example;')
            with zipfile.ZipFile(data) as archive:
                self.assertEqual(['example/Test.java'], artifacts.safe_archive(archive))

    def test_source_comparison_checks_inventory_and_content(self):
        with io.BytesIO() as data:
            data.name = 'sources.jar'
            with zipfile.ZipFile(data, 'w') as archive:
                archive.writestr('example/Test.java', b'package example;\r\n')
            with patch.object(artifacts, 'git', side_effect=[b'module/src/main/java/example/Test.java\n', b'package example;\n']):
                self.assertEqual(1, artifacts.verify_sources(data, 'commit', ['module'])['javaSources'])
            data.seek(0)
            with patch.object(artifacts, 'git', side_effect=[b'module/src/main/java/example/Test.java\n', b'changed\n']), self.assertRaises(ValueError):
                artifacts.verify_sources(data, 'commit', ['module'])
            data.seek(0)
            with patch.object(artifacts, 'git', return_value=b'module/src/main/java/example/Other.java\n'), self.assertRaises(ValueError):
                artifacts.verify_sources(data, 'commit', ['module'])


class NetworkTraceTest(unittest.TestCase):
    def test_blocked_tcp_and_udp_dns_attempts_are_observations(self):
        trace = '''123 10 connect(4, {sa_family=AF_INET, sin_port=htons(443), sin_addr=inet_addr("198.51.100.1")}, 16) = -1 ENETUNREACH
124 11 sendto(5, "dns", 3, 0, {sa_family=AF_INET, sin_port=htons(53), sin_addr=inet_addr("192.0.2.53")}, 16) = -1 ENETUNREACH'''
        self.assertEqual(['198.51.100.1', '192.0.2.53'], [row['address'] for row in network_attempts(trace)])

    def test_loopback_and_unix_ipc_are_separate_from_external_attempts(self):
        trace = '''connect(4, {sa_family=AF_INET, sin_addr=inet_addr("127.0.0.1")}, 16) = 0
connect(5, {sa_family=AF_INET6, inet_pton(AF_INET6, "::1", &sin6_addr)}, 28) = 0
connect(6, {sa_family=AF_INET6, inet_pton(AF_INET6, "::ffff:127.0.0.1", &sin6_addr)}, 28) = 0
connect(7, {sa_family=AF_UNIX, sun_path="/tmp/local"}, 16) = 0'''
        self.assertEqual([], network_attempts(trace))

    def test_loopback_dns_resolvers_are_recorded_instead_of_hidden_as_local_ipc(self):
        trace = 'sendto(5, "dns", 3, 0, {sa_family=AF_INET, sin_port=htons(53), sin_addr=inet_addr("127.0.0.11")}, 16) = 3'
        self.assertEqual('dns', network_attempts(trace)[0]['kind'])

    def test_ipv6_and_resumed_trace_prefixes_are_supported(self):
        trace = '345 1710.123 connect(5<TCPv6:[123]>, {sa_family=AF_INET6, inet_pton(AF_INET6, "2001:db8::1", &sin6_addr)}, 28) = -1 ENETUNREACH'
        self.assertEqual('2001:db8::1', network_attempts(trace)[0]['address'])

    def test_external_ipv4_mapped_addresses_use_the_same_destination_identity(self):
        trace = 'connect(5, {sa_family=AF_INET6, inet_pton(AF_INET6, "::ffff:198.51.100.1", &sin6_addr)}, 28) = -1 ENETUNREACH'
        self.assertEqual('198.51.100.1', network_attempts(trace)[0]['address'])

    def test_unparsed_ip_destinations_fail_instead_of_reporting_zero_attempts(self):
        with self.assertRaises(ValueError):
            network_attempts('connect(4, {sa_family=AF_INET, truncated}, 16) = -1 ENETUNREACH')

    def test_bind_listen_and_interface_discovery_are_not_outbound_connections(self):
        self.assertEqual([], network_attempts('bind(4, {sa_family=AF_INET, sin_addr=inet_addr("0.0.0.0")}, 16) = 0\nconnect(5, {sa_family=AF_NETLINK}, 16) = 0'))


class SignatureBindingTest(unittest.TestCase):
    def test_signed_payload_must_bind_the_expected_tag_and_commit(self):
        tag_binding('object abc\ntype commit\ntag v0.5.0\ntagger test\n', 'abc')
        for payload in ('object changed\ntype commit\ntag v0.5.0\n', 'object abc\ntype commit\ntag v0.4.0\n'):
            with self.assertRaises(ValueError):
                tag_binding(payload, 'abc')

    def test_missing_duplicate_or_different_signer_cannot_complete_a_cohort(self):
        rows = [{'file': 'source-tag' if index == 0 else f'artifact-{index}', 'primaryFingerprint': 'publisher'} for index in range(10)]
        signer_cohort(rows)
        for changed in (rows[:-1], rows[:-1] + [rows[0]], rows[:-1] + [{'file': 'artifact-9', 'primaryFingerprint': 'other'}]):
            with self.assertRaises(ValueError):
                signer_cohort(changed)


class FixtureContractTest(unittest.TestCase):
    def test_requested_release_reaches_both_native_consumers_without_changing_test_data(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixtures(root, '1.2.3')
            self.assertIn("version '1.2.3' apply false", (root / 'gradle/build.gradle').read_text())
            ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
            pom = ET.parse(root / 'maven/pom.xml').getroot()
            versions = [plugin.findtext('m:version', namespaces=ns) for plugin in pom.findall('.//m:plugin', ns)
                        if plugin.findtext('m:artifactId', namespaces=ns) == 'shardpilot-maven-plugin']
            self.assertEqual(['1.2.3'], versions)
            self.assertIn('@ValueSource(ints = {1, 2})', (root / 'gradle/src/test/java/example/equivalence/AlphaTest.java').read_text())

    def test_native_maven_runners_have_disjoint_histories_bound_to_their_executions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixtures(root)
            ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
            pom = ET.parse(root / 'maven/pom.xml').getroot()
            executions = pom.findall('.//m:profile/m:build/m:plugins/m:plugin/m:executions/m:execution', ns)
            self.assertEqual(2, len(executions))
            histories = {}
            for execution in executions:
                runner = execution.findtext('m:configuration/m:runner', namespaces=ns)
                file = execution.findtext('m:configuration/m:durationsFile', namespaces=ns).replace('${project.basedir}/', '')
                histories[runner] = {line.split('\t')[0] for line in (root / 'maven' / file).read_text().splitlines()}
            self.assertEqual(4, len(histories['SUREFIRE']))
            self.assertEqual(4, len(histories['FAILSAFE']))
            self.assertTrue(all(name.endswith('Test') for name in histories['SUREFIRE']))
            self.assertTrue(all(name.endswith('IT') for name in histories['FAILSAFE']))
            self.assertTrue(histories['SUREFIRE'].isdisjoint(histories['FAILSAFE']))

    def test_failed_preflight_invalidates_both_earlier_green_reports(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ('audit-summary.json', 'verified-evidence.json'):
                (root / name).write_text('{"passed":true}')
            with patch.object(launcher, 'run', side_effect=RuntimeError('preflight rejected')), self.assertRaises(RuntimeError):
                launcher.audit(root, 'unused-image', True, True, ['signatures', 'warm', 'isolated'])
            self.assertFalse((root / 'audit-summary.json').exists())
            self.assertFalse((root / 'verified-evidence.json').exists())


class ReleaseInputTest(unittest.TestCase):
    def test_untrusted_release_inputs_cannot_change_shells_paths_or_version_identity(self):
        for invalid in ('', ' v0.4.0', 'v0.4.0', '0.4.0\n', '0.4.0-SNAPSHOT', '01.2.3', '../0.4.0', '0.4.0; id', None):
            with self.subTest(value=invalid), self.assertRaises(ValueError):
                artifacts.release_version(invalid)
        self.assertEqual('1.2.3', artifacts.release_version('1.2.3'))
        with tempfile.TemporaryDirectory() as temporary, self.assertRaises(ValueError):
            fixtures(Path(temporary), "1.2.3'\nthrow new Exception()")

    def test_reviewed_policy_binds_a_published_version_commit_and_signer(self):
        policy = artifacts.audit_policy()
        self.assertEqual('0.5.0', policy['defaultPublishedVersion'])
        self.assertEqual('dacbfbdb07e61f37ffa524fb286a97483c6c93e9', policy['defaultSourceCommit'])
        self.assertEqual('0AE0A30C093FDE0EA02E7502ADF2B8E6FA34B930', policy['publisherPrimaryFingerprint'])

    def test_reviewed_baseline_has_matching_retained_published_audit_evidence(self):
        policy = artifacts.audit_policy()
        evidence = json.loads((artifacts.ROOT / 'docs/evidence/published-0.5.0-2026-11-13.json').read_text(encoding='utf-8'))
        self.assertEqual(policy['defaultPublishedVersion'], evidence['version'])
        self.assertEqual(policy['defaultSourceCommit'], evidence['sourceCommit'])
        self.assertEqual(policy['publisherPrimaryFingerprint'], evidence['primaryFingerprint'])
        self.assertTrue(evidence['ciReport']['passed'])
        self.assertEqual(evidence['sourceCommit'], evidence['ciReport']['sourceCommit'])
        self.assertEqual(15, len({row['file'] for row in evidence['artifacts']}))
        self.assertTrue(evidence['tagSignatureVerified'])
        self.assertEqual(9, evidence['mavenArtifactSignaturesVerified'])
        cases = evidence['isolation']['cases']
        self.assertEqual(14, len(cases))
        self.assertTrue(all(row['outboundAttempts'] == 0 for row in cases))
        comparisons = evidence['isolation']['comparisons']
        self.assertEqual({('gradle', 2), ('gradle', 4), ('maven', 2), ('maven', 4)},
                         {(row['tool'], row['shards']) for row in comparisons if row['passed']})
        self.assertTrue(evidence['negativeControls']['passed'])
        self.assertEqual(9, len(set(evidence['negativeControls']['rejectedMutations'])))

    def test_registry_cli_uses_policy_unless_a_stable_release_is_explicit(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary).resolve()
            # A future policy version must work without another CLI constant changing.
            for arguments, expected in (([], '1.2.3'), (['--version', '0.4.0'], '0.4.0')):
                with self.subTest(arguments=arguments), patch.object(artifacts, 'fetch') as fetch, \
                        patch.object(artifacts, 'audit_policy', return_value={'defaultPublishedVersion': '1.2.3'}) as policy:
                    artifacts.main(['--output', temporary, *arguments])
                    fetch.assert_called_once_with(output, expected)
                self.assertEqual(not arguments, policy.called)
            for invalid in ('', '0.5.0-SNAPSHOT', '../0.5.0'):
                with self.subTest(invalid=invalid), patch.object(artifacts, 'fetch') as fetch, self.assertRaises(ValueError):
                    artifacts.main(['--output', temporary, '--version', invalid])
                fetch.assert_not_called()

    def test_runner_uses_policy_on_host_and_explicit_version_in_sandbox(self):
        runner_path = Path(audit_runner.__file__)
        for environment, expected in (({}, '1.2.3'), ({'SHARDPILOT_AUDIT_VERSION': '0.4.0'}, '0.4.0')):
            with self.subTest(environment=environment), patch.dict(os.environ, environment, clear=True), \
                    patch.object(Path, 'read_text', return_value='{"defaultPublishedVersion":"1.2.3"}') as policy:
                self.assertEqual(expected, runpy.run_path(str(runner_path))['VERSION'])
                self.assertEqual(not environment, policy.called)
        with patch.dict(os.environ, {'SHARDPILOT_AUDIT_VERSION': ''}), self.assertRaises(ValueError):
            runpy.run_path(str(runner_path))

    def test_default_release_rejects_wrong_commit_before_any_container_or_tag_download(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            (output / 'registry-verification.json').write_text(json.dumps({
                'version': '0.5.0', 'sourceCommit': 'a' * 40, 'artifacts': []}))
            with patch.object(launcher, 'run'), patch.object(artifacts, 'git', return_value=('a' * 40).encode()), \
                    patch.object(artifacts, 'download') as download, patch.object(launcher, 'container') as container, \
                    self.assertRaisesRegex(ValueError, 'reviewed source commit'):
                launcher.audit(output, 'unused-image', True, True, ['signatures', 'warm', 'isolated'])
            download.assert_not_called()
            container.assert_not_called()

    def test_launcher_accepts_reviewed_default_and_explicit_older_release(self):
        for version, commit in ((None, 'dacbfbdb07e61f37ffa524fb286a97483c6c93e9'),
                                ('0.4.0', '58daef5436a01295ce46feea56aa307c41bbd0a0')):
            selected = version or '0.5.0'
            with self.subTest(version=version), tempfile.TemporaryDirectory() as temporary:
                output = Path(temporary)
                (output / 'registry-verification.json').write_text(json.dumps({
                    'version': selected, 'sourceCommit': commit, 'artifacts': []}))
                (output / 'source-tag.json').write_text(json.dumps({'object': {'sha': commit}, 'tag': 'v' + selected}))
                with patch.object(launcher, 'run') as run, patch.object(artifacts, 'git', return_value=commit.encode()) as git, \
                        patch.object(artifacts, 'download') as download, \
                        patch.object(launcher.subprocess, 'check_output', side_effect=RuntimeError('stop before Docker')), \
                        self.assertRaisesRegex(RuntimeError, 'stop before Docker'):
                    launcher.audit(output, 'unused-image', True, True, ['signatures'], version)
                git.assert_called_once_with('rev-parse', 'v' + selected + '^{}')
                self.assertEqual(['--version', selected], run.call_args.args[0][-2:])
                self.assertEqual(selected, json.loads((output / 'audit-request.json').read_text())['version'])
                download.assert_not_called()

    def test_explicit_release_tag_binding_rejects_another_valid_release(self):
        tag_binding('object abc\ntype commit\ntag v1.2.3\n', 'abc', '1.2.3')
        with self.assertRaises(ValueError):
            tag_binding('object abc\ntype commit\ntag v0.4.0\n', 'abc', '1.2.3')


class SandboxContractTest(unittest.TestCase):
    def test_tool_identity_records_the_jvm_version_instead_of_its_option_banner(self):
        outputs = ['Picked up JAVA_TOOL_OPTIONS: -XX:ActiveProcessorCount=2\nopenjdk version "25.0.5"\n',
                   'Python 3.14.4\n', 'strace -- version 6.19\n', 'gpg (GnuPG) 2.4.8\n']
        with patch.object(audit_runner.subprocess, 'run', side_effect=[Mock(stdout=value) for value in outputs]), \
                patch.object(audit_runner, 'write') as write:
            audit_runner.tool_versions()
        self.assertEqual('openjdk version "25.0.5"', json.loads(write.call_args.args[1])['java'])

    def test_linux_outputs_are_writable_without_forwarding_host_credentials_or_relaxing_isolation(self):
        for phase in ('signatures', 'warm', 'isolated'):
            network = 'bridge' if phase == 'warm' else 'none'
            inspection = {'Image': 'sha256:test', 'Config': {'User': '10001:10001', 'Env': ['HOME=/work/home']},
                          'HostConfig': {'NetworkMode': network, 'ReadonlyRootfs': True, 'CapDrop': ['ALL'],
                                         'SecurityOpt': ['no-new-privileges'], 'Memory': 3 * 1024 ** 3,
                                         'NanoCpus': 2_000_000_000, 'PidsLimit': 512}, 'Mounts': []}
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(launcher.subprocess, 'check_output', side_effect=[
                        'a' * 64, json.dumps([inspection]), json.dumps([{'State': {'ExitCode': 0, 'OOMKilled': False}}])]) as docker, \
                    patch.object(launcher, 'run'), patch.dict('os.environ', {'PUBLISH_PASSWORD': 'must-not-reach-consumers'}):
                root = Path(temporary)
                launcher.container(phase, root, 'sha256:test', '1.2.3', 'A' * 40)
                arguments = docker.call_args_list[0].args[0]
                self.assertEqual(network, arguments[arguments.index('--network') + 1])
                self.assertEqual('10001:10001', arguments[arguments.index('--user') + 1])
                self.assertIn('--read-only', arguments)
                self.assertEqual('ALL', arguments[arguments.index('--cap-drop') + 1])
                self.assertIn('no-new-privileges', arguments)
                self.assertNotIn('--privileged', arguments)
                self.assertNotIn('PUBLISH_PASSWORD', ' '.join(arguments))
                self.assertNotIn('docker.sock', ' '.join(arguments))
                mounts = [arguments[index + 1] for index, value in enumerate(arguments) if value == '--mount']
                self.assertTrue(all(value.endswith(',readonly') for value in mounts
                                    if ',target=/runner' in value or ',target=/fixtures' in value or ',target=/registry' in value))
                seed = next(value for value in mounts if ',target=/seed' in value)
                self.assertEqual(phase != 'warm', seed.endswith(',readonly'))
                if os.name == 'posix':
                    self.assertEqual(0o777, (root / phase).stat().st_mode & 0o777)
                    self.assertEqual(0o777, (root / 'seed').stat().st_mode & 0o777)


class CiEvidenceTest(unittest.TestCase):
    def test_packaging_failure_cannot_leave_a_green_ci_report_after_a_valid_audit(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict('os.environ', {}, clear=True):
            root = Path(temporary)
            output = root / 'evidence'
            output.mkdir()
            native = {'passed': True, 'version': '0.4.0', 'sourceCommit': 'a' * 40}
            verified = {**native, 'registryArtifacts': 15, 'openPgpSignatures': 10,
                        'cases': [{'outboundAttempts': 0}] * 14, 'comparisons': [True] * 4}
            (output / 'audit-request.json').write_text(json.dumps({'version': '0.4.0'}))
            (output / 'audit-summary.json').write_text(json.dumps(native))
            (output / 'negative-controls.json').write_text(json.dumps({'passed': True, 'rejectedMutations': list(map(str, range(9)))}))
            with patch.object(reporting, 'summarize', return_value=verified), \
                    patch.object(reporting, 'package', side_effect=ValueError('Unsafe evidence')), \
                    self.assertRaisesRegex(ValueError, 'Unsafe evidence'):
                reporting.finalize(output, root / 'report.zip', 'success')
            result = json.loads((output / 'ci-report.json').read_text())
            self.assertFalse(result['passed'])
            self.assertEqual('ValueError', result['packagingError'])
            self.assertIn('FAILED / INCOMPLETE', (output / 'ci-summary.md').read_text())

    def test_passing_report_requires_complete_phases_and_distinct_mutation_controls(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict('os.environ', {}, clear=True):
            root = Path(temporary)
            output = root / 'evidence'
            output.mkdir()
            native = {'passed': True, 'version': '0.4.0', 'sourceCommit': 'a' * 40}
            verified = {**native, 'registryArtifacts': 15, 'openPgpSignatures': 10,
                        'cases': [{'outboundAttempts': 0}] * 14, 'comparisons': [True] * 4}
            (output / 'audit-request.json').write_text(json.dumps({'version': '0.4.0'}))
            (output / 'audit-summary.json').write_text(json.dumps(native))
            for count, expected in ((9, True), (8, False), (9, False)):
                controls = list(map(str, range(count))) if expected or count == 8 else ['duplicated'] * 9
                (output / 'negative-controls.json').write_text(json.dumps({'passed': True, 'rejectedMutations': controls}))
                with self.subTest(controls=controls), patch.object(reporting, 'summarize', return_value=verified):
                    result = reporting.finalize(output, root / 'report.zip', 'success')
                self.assertEqual(expected, result['passed'])
            self.assertFalse(json.loads((output / 'ci-report.json').read_text())['passed'])

    def test_retention_refuses_a_link_to_data_outside_the_evidence_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / 'evidence'
            (output / 'isolated').mkdir(parents=True)
            private = root / 'private.txt'
            private.write_text('must not upload')
            try:
                (output / 'isolated/leak.log').symlink_to(private)
            except OSError:
                self.skipTest('Creating symlinks requires additional privileges on this host')
            with self.assertRaisesRegex(ValueError, 'symlinks'):
                reporting.package(output, root / 'report.zip')
            self.assertFalse((root / 'report.zip').exists())

    def test_failure_keeps_partial_traces_and_cannot_reuse_an_earlier_green_ci_verdict(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / 'evidence'
            output.mkdir()
            (output / 'ci-report.json').write_text('{"passed":true}')
            (output / 'launcher.log').write_text('phase failed')
            (output / 'isolated').mkdir()
            (output / 'isolated/partial.strace').write_text('connect failed')
            with patch.object(reporting, 'summarize') as verification, patch.dict('os.environ', {}, clear=True):
                result = reporting.finalize(output, root / 'report.zip', 'failure')
            verification.assert_not_called()
            self.assertFalse(result['passed'])
            with zipfile.ZipFile(root / 'report.zip') as archive:
                self.assertIn('evidence/isolated/partial.strace', archive.namelist())
                self.assertFalse(json.loads(archive.read('evidence/ci-report.json'))['passed'])

    def test_archive_excludes_dependency_homes_downloaded_tools_and_unrelated_secrets(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / 'evidence'
            output.mkdir()
            for relative in ('seed/homes.tar', 'tool-image/gradle.zip', 'settings.xml', '.git/config'):
                path = output / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('must not upload')
            (output / 'launcher.log').write_text('public diagnostics')
            reporting.package(output, root / 'report.zip')
            with zipfile.ZipFile(root / 'report.zip') as archive:
                names = archive.namelist()
                self.assertIn('evidence/launcher.log', names)
                self.assertFalse(any(name.startswith('evidence/' + prefix) for name in names
                                     for prefix in ('seed/', 'tool-image/', 'settings.xml', '.git/')))
            expected = hashlib.sha256((root / 'report.zip').read_bytes()).hexdigest()
            self.assertTrue((root / 'report.zip.sha256').read_text().startswith(expected + '  '))

    def test_success_outcome_without_complete_evidence_is_packaged_as_failure(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict('os.environ', {}, clear=True):
            root = Path(temporary)
            output = root / 'evidence'
            result = reporting.finalize(output, root / 'report.zip', 'success')
            self.assertFalse(result['passed'])
            self.assertEqual('FileNotFoundError', result['verificationError'])
            self.assertTrue((root / 'report.zip').is_file())

    def test_a_malformed_partial_request_does_not_hide_failure_diagnostics(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict('os.environ', {}, clear=True):
            root = Path(temporary)
            output = root / 'evidence'
            output.mkdir()
            (output / 'audit-request.json').write_text('{broken')
            result = reporting.finalize(output, root / 'report.zip', 'failure')
            self.assertFalse(result['passed'])
            self.assertEqual('JSONDecodeError', result['verificationError'])
            self.assertTrue((root / 'report.zip').is_file())


if __name__ == '__main__':
    unittest.main()

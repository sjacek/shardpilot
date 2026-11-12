"""Negative controls for public-byte validation and outbound-syscall classification."""
import hashlib
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import published_artifacts as artifacts
import run_published_artifact_audit as launcher
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
        tag_binding('object abc\ntype commit\ntag v0.4.0\ntagger test\n', 'abc')
        for payload in ('object changed\ntype commit\ntag v0.4.0\n', 'object abc\ntype commit\ntag v0.3.0\n'):
            with self.assertRaises(ValueError):
                tag_binding(payload, 'abc')

    def test_missing_duplicate_or_different_signer_cannot_complete_a_cohort(self):
        rows = [{'file': 'source-tag' if index == 0 else f'artifact-{index}', 'primaryFingerprint': 'publisher'} for index in range(10)]
        signer_cohort(rows)
        for changed in (rows[:-1], rows[:-1] + [rows[0]], rows[:-1] + [{'file': 'artifact-9', 'primaryFingerprint': 'other'}]):
            with self.assertRaises(ValueError):
                signer_cohort(changed)


class FixtureContractTest(unittest.TestCase):
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


if __name__ == '__main__':
    unittest.main()

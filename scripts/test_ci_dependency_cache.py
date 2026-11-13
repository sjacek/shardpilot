"""Dependency snapshots and warmup commands are tested without network or Maven execution."""
import io
import os
from pathlib import Path
import tarfile
import tempfile
import subprocess
import unittest
from unittest.mock import patch

import ci_dependency_cache as cache


class DependencyCacheTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_snapshot_round_trip_retains_downloads_and_launcher_permissions_only(self):
        source, restored = self.root / 'source', self.root / 'restored'
        retained = ('testkit/caches/modules-2/files-2.1/org.junit/junit-bom/6/test.pom',
                    'gradle/wrapper/dists/gradle-9.9.1-bin/hash/gradle/bin/gradle',
                    'maven/org/junit/junit-bom/6/test.pom',
                    'maven-user-home/wrapper/dists/apache-maven/bin/mvn')
        excluded = ('testkit/daemon/9.9.1/daemon.log', 'gradle/caches/build-cache-1/result',
                    'testkit/caches/modules-2/modules-2.lock', 'maven/org/example/a.jar.lastUpdated',
                    'maven/io/github/sjacek/shardpilot-core/0.4.0/shardpilot-core.jar',
                    'maven-user-home/settings.xml', 'gnupg/private.key')
        for name in retained + excluded:
            path = source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(name)
            path.chmod(0o755)
        archive = self.root / 'downloads.tar'
        cache.pack(source, archive)
        cache.restore(restored, archive)
        self.assertEqual(set(retained), {p.relative_to(restored).as_posix()
                                        for p in restored.rglob('*') if p.is_file()})
        for name in retained:
            self.assertEqual(name, (restored / name).read_text())
            if os.name != 'nt':
                self.assertTrue((restored / name).stat().st_mode & 0o100)

    def test_missing_snapshot_is_a_normal_cold_run(self):
        cache.restore(self.root / 'cache', self.root / 'missing.tar')
        self.assertFalse((self.root / 'cache').exists())

    def test_snapshot_types_do_not_transfer_unrelated_tool_downloads(self):
        source = self.root / 'source'
        for name in ('testkit/caches/modules-2/example.jar', 'maven/org/example/library.jar'):
            path = source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('download')
        for kind, prefixes in cache.SNAPSHOTS.items():
            archive = self.root / (kind + '.tar')
            cache.pack(source, archive, prefixes)
            with tarfile.open(archive) as snapshot:
                names = snapshot.getnames()
            self.assertEqual(1, len(names))
            self.assertEqual(kind == 'maven', names[0].startswith('maven/'))
            other = cache.SNAPSHOTS['maven' if kind == 'gradle' else 'gradle']
            with self.assertRaises(ValueError):
                cache.restore(self.root / 'wrong-kind', archive, other)

    def test_export_does_not_follow_symbolic_links(self):
        if os.name == 'nt':
            self.skipTest('Creating Windows symlinks requires additional host privileges')
        source = self.root / 'source'
        directory = source / 'maven/org/example'
        directory.mkdir(parents=True)
        secret = self.root / 'private'
        secret.write_text('private')
        (directory / 'link.jar').symlink_to(secret)
        archive = self.root / 'downloads.tar'
        cache.pack(source, archive)
        with tarfile.open(archive) as snapshot:
            self.assertEqual([], snapshot.getnames())

    def test_hardlinked_downloads_are_exported_as_regular_files(self):
        source = self.root / 'source'
        directory = source / 'maven/org/example'
        directory.mkdir(parents=True)
        (directory / 'first.jar').write_text('download')
        os.link(directory / 'first.jar', directory / 'second.jar')
        archive = self.root / 'hardlinks.tar'
        cache.pack(source, archive)
        with tarfile.open(archive) as snapshot:
            self.assertEqual(2, len(snapshot.getmembers()))
            self.assertTrue(all(member.isfile() for member in snapshot.getmembers()))
        cache.restore(self.root / 'restored', archive)
        self.assertEqual('download', (self.root / 'restored/maven/org/example/second.jar').read_text())

    def test_import_rejects_unexpected_entries_before_extracting_anything(self):
        for name in ('../outside', '/outside', 'maven/../../outside',
                     'maven\\..\\outside', 'gradle/gradle.properties',
                     'maven/./io/github/sjacek/local.jar', 'maven//io/github/sjacek/local.jar',
                     'maven/io/github/sjacek/shardpilot-core/local.jar'):
            with self.subTest(name=name):
                archive = self.root / 'bad.tar'
                with tarfile.open(archive, 'w') as snapshot:
                    valid = tarfile.TarInfo('maven/org/example/ok.jar')
                    valid.size = 1
                    snapshot.addfile(valid, io.BytesIO(b'1'))
                    invalid = tarfile.TarInfo(name)
                    snapshot.addfile(invalid, io.BytesIO())
                with self.assertRaises(ValueError):
                    cache.restore(self.root / 'restored', archive)
                self.assertFalse((self.root / 'restored').exists())

    def test_import_rejects_links_even_inside_allowed_paths(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
            with self.subTest(kind=kind):
                archive = self.root / 'bad-link.tar'
                with tarfile.open(archive, 'w') as snapshot:
                    link = tarfile.TarInfo('maven/org/example/link.jar')
                    link.type = kind
                    link.linkname = '../../private'
                    snapshot.addfile(link)
                with self.assertRaises(ValueError):
                    cache.restore(self.root / 'restored', archive)

    def test_maven_process_preserves_paths_and_arguments_and_propagates_failures(self):
        with patch.object(cache, 'ROOT', self.root / 'with spaces'), \
                patch.object(cache, 'CACHE', self.root / 'cache with spaces'), \
                patch.dict(os.environ, {'SHARDPILOT_MAVEN_VERIFICATION': ''}), \
                patch.object(cache.subprocess, 'run') as run:
            cache.maven('-f', 'a pom.xml', cache.DEPENDENCY_PLUGIN + ':go-offline')
            args, options = run.call_args
            self.assertIn('-Dmaven.repo.local=' + str(self.root / 'cache with spaces/maven'), args[0])
            self.assertIn('a pom.xml', args[0])
            self.assertTrue(options['check'])
            self.assertNotIn('shell', options)
            run.side_effect = subprocess.CalledProcessError(1, 'maven')
            with self.assertRaises(subprocess.CalledProcessError):
                cache.maven('install', '-DskipTests', '-DskipITs')

    def test_fixture_warmup_preserves_early_checksum_enforcement(self):
        (self.root / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><version>0.4.0</version></project>')
        with patch.object(cache, 'ROOT', self.root), patch.object(cache, 'CACHE', self.root / 'downloads'), \
                patch.dict(os.environ, {'SHARDPILOT_MAVEN_VERIFICATION': 'enforce'}), \
                patch.object(cache.maven_verification, 'consumer_environment', return_value={'MAVEN_OPTS': 'verified'}) as prepare, \
                patch.object(cache.subprocess, 'run') as run:
            cache.maven('-f', 'fixture/pom.xml', cache.DEPENDENCY_PLUGIN + ':go-offline')
        prepare.assert_called_once_with(self.root / 'downloads/maven', '0.4.0')
        self.assertEqual({'MAVEN_OPTS': 'verified'}, run.call_args.kwargs['env'])

    def test_invoker_gets_its_own_writable_copy_of_external_dependencies(self):
        downloaded = self.root / 'downloads/maven/org/example/library.jar'
        downloaded.parent.mkdir(parents=True)
        downloaded.write_text('download')
        with patch.object(cache, 'ROOT', self.root), patch.object(cache, 'CACHE', self.root / 'downloads'):
            cache.seed_invoker()
        seeded = self.root / 'shardpilot-maven-plugin/target/it-repository/org/example/library.jar'
        self.assertEqual('download', seeded.read_text())
        seeded.write_text('isolated')
        self.assertEqual('download', downloaded.read_text())

    def test_maven_warmup_filters_fixture_versions_and_never_runs_fixture_lifecycles(self):
        pom = '''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <version>0.4.0</version><properties><surefire.version>3.6.1</surefire.version></properties>
            <build><plugins><plugin><artifactId>shardpilot-maven-plugin</artifactId>
            <version>@project.version@</version></plugin><plugin><artifactId>maven-surefire-plugin</artifactId>
            <version>${surefire.version}</version></plugin></plugins></build></project>'''
        (self.root / 'pom.xml').write_text(pom)
        for source in ('shardpilot-maven-plugin/src/it', 'examples/maven'):
            directory = self.root / source / 'fixture'
            directory.mkdir(parents=True)
            (directory / 'pom.xml').write_text(pom)
            (directory / 'must-not-run.groovy').write_text('throw new Exception()')
        with patch.object(cache, 'ROOT', self.root), patch.object(cache, 'maven') as maven:
            cache.warm_maven()
        calls = [call.args for call in maven.call_args_list]
        self.assertEqual(('install', '-DskipTests', '-DskipITs'), calls[0])
        self.assertEqual((cache.DEPENDENCY_PLUGIN + ':go-offline', '-Psecurity-sbom'), calls[1])
        self.assertEqual(('-Psecurity-sbom,central-release', '-Dcentral.skipUpload=true',
                          cache.DEPENDENCY_PLUGIN + ':resolve-plugins'), calls[2])
        for command in calls[3:-1]:
            self.assertEqual(cache.DEPENDENCY_PLUGIN + ':go-offline', command[-1])
            text = Path(command[1]).read_text()
            self.assertNotIn('@project.version@', text)
            self.assertFalse(Path(command[1]).with_name('must-not-run.groovy').exists())
        self.assertEqual((cache.DEPENDENCY_PLUGIN + ':get',
                          '-Dartifact=org.apache.maven.surefire:surefire-junit-platform:3.6.1'), calls[-1])


if __name__ == '__main__':
    unittest.main()

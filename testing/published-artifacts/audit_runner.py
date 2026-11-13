"""Credential-free published-consumer fixtures, OpenPGP checks and Linux syscall evidence."""
import argparse
from collections import Counter
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import zipfile

VERSION = os.environ.get('SHARDPILOT_AUDIT_VERSION', '0.4.0')
if not re.fullmatch(r'(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)', VERSION):
    raise ValueError('An immutable stable release version is required')


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')


def tool_versions():
    versions = {}
    for name, command in [('java', ['java', '-version']), ('python', ['python3', '--version']),
                          ('strace', ['strace', '--version']), ('gpg', ['gpg', '--version'])]:
        result = subprocess.run(command, check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=30)
        lines = [line for line in result.stdout.splitlines() if line and not line.startswith('Picked up JAVA_TOOL_OPTIONS:')]
        versions[name] = lines[0]
    write(Path('/evidence/tool-versions.json'), json.dumps(versions, indent=2) + '\n')


def fixtures(destination, version=VERSION):
    if not re.fullmatch(r'(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)', version):
        raise ValueError('An immutable stable release version is required')
    repository = Path(__file__).resolve().parents[2]
    for tool in ('gradle', 'maven'):
        root = destination / tool
        write(root / 'durations.tsv', ''.join(f'example.equivalence.{name}{suffix}\t10\n' for name in ('Alpha', 'Beta', 'Gamma', 'Delta') for suffix in (('Test',) if tool == 'gradle' else ('Test', 'IT'))))
        if tool == 'maven':
            for suite, suffix in (('unit', 'Test'), ('integration', 'IT')):
                write(root / (suite + '-durations.tsv'), ''.join(f'example.equivalence.{name}{suffix}\t10\n' for name in ('Alpha', 'Beta', 'Gamma', 'Delta')))
        source = root / 'src/test/java/example/equivalence'
        source.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(repository / 'testing/execution-equivalence/junit/src/example/equivalence/JunitExecutionListener.java', source / 'JunitExecutionListener.java')
        write(root / 'src/test/resources/META-INF/services/org.junit.platform.launcher.TestExecutionListener', 'example.equivalence.JunitExecutionListener\n')
        for name in ('Alpha', 'Beta', 'Gamma', 'Delta'):
            for suffix in (('Test',) if tool == 'gradle' else ('Test', 'IT')):
                write(source / (name + suffix + '.java'), f'''package example.equivalence;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertTrue;
class {name}{suffix} {{
    @ParameterizedTest
    @ValueSource(ints = {{1, 2}})
    void rows(int value) {{ assertTrue(value > 0); }}
}}
''')
    write(destination / 'gradle/settings.gradle', "rootProject.name='published-artifact-audit'\n")
    write(destination / 'gradle/gradle.properties', 'org.gradle.workers.max=1\norg.gradle.jvmargs=-Xmx384m -Dfile.encoding=UTF-8\n')
    write(destination / 'gradle/build.gradle', '''plugins {
    id 'java'
    id 'io.github.sjacek.shardpilot' version '@SHARDPILOT_VERSION@' apply false
}
def active = providers.gradleProperty('auditPlugin').getOrElse('false').toBoolean()
if (active) apply plugin: 'io.github.sjacek.shardpilot'
repositories { mavenCentral() }
dependencies {
    testImplementation platform('org.junit:junit-bom:6.1.3')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'org.junit.platform:junit-platform-launcher'
}
tasks.withType(JavaCompile).configureEach { options.release = 17 }
test {
    useJUnitPlatform()
    maxHeapSize = '192m'
    systemProperty 'execution.evidence', providers.gradleProperty('auditEvidence').get()
    if (active) shardPilot {
        enabled = true
        durationsFile = layout.projectDirectory.file('durations.tsv')
    }
}
'''.replace('@SHARDPILOT_VERSION@', version))
    write(destination / 'maven/pom.xml', '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>example.shardpilot</groupId><artifactId>published-audit</artifactId><version>1</version>
<properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding><shardpilot.count>1</shardpilot.count><shardpilot.index>1</shardpilot.index></properties>
<dependencies>
<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>6.1.3</version><scope>test</scope></dependency>
<dependency><groupId>org.junit.platform</groupId><artifactId>junit-platform-launcher</artifactId><version>6.1.3</version><scope>test</scope></dependency>
</dependencies>
<build><plugins>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.6.1</version><configuration><includes><include>**/*Test.java</include></includes><argLine>-Xmx192m</argLine><systemPropertyVariables><execution.evidence>${auditEvidence}</execution.evidence></systemPropertyVariables></configuration></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId><version>3.6.1</version><configuration><includes><include>**/*IT.java</include></includes><argLine>-Xmx192m</argLine><systemPropertyVariables><execution.evidence>${auditEvidence}</execution.evidence></systemPropertyVariables></configuration><executions><execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution></executions></plugin>
</plugins></build>
<profiles><profile><id>sharded</id><build><plugins>
<plugin><groupId>io.github.sjacek</groupId><artifactId>shardpilot-maven-plugin</artifactId><version>@SHARDPILOT_VERSION@</version><configuration><durationsFile>${project.basedir}/durations.tsv</durationsFile></configuration><executions>
<execution><id>unit</id><phase>process-test-classes</phase><goals><goal>select</goal></goals><configuration><runner>SUREFIRE</runner><durationsFile>${project.basedir}/unit-durations.tsv</durationsFile></configuration></execution>
<execution><id>integration</id><phase>pre-integration-test</phase><goals><goal>select</goal></goals><configuration><runner>FAILSAFE</runner><durationsFile>${project.basedir}/integration-durations.tsv</durationsFile></configuration></execution>
</executions></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><configuration><excludesFile>${project.build.directory}/shardpilot/unit/excludes.txt</excludesFile></configuration></plugin>
<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId><configuration><excludesFile>${project.build.directory}/shardpilot/integration/excludes.txt</excludesFile></configuration></plugin>
</plugins></build></profile></profiles>
</project>
'''.replace('@SHARDPILOT_VERSION@', version))


def network_attempts(trace):
    attempts = []
    for line in trace.splitlines():
        if not re.search(r'\b(connect|sendto|sendmsg|sendmmsg)\(', line):
            continue
        addresses = re.findall(r'inet_addr\("([^"]+)"\)|inet_pton\(AF_INET6, "([^"]+)"', line)
        if 'sa_family=AF_INET' in line and not addresses:
            raise ValueError('Unparsed IP destination in network trace')
        for address in addresses:
            ip = ipaddress.ip_address(address[0] or address[1])
            comparable = ip.ipv4_mapped if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped else ip
            dns = re.search(r'sin(?:6)?_port=htons\(53\)', line) is not None
            if dns or not comparable.is_loopback:
                attempts.append({'address': str(comparable), 'socketAddress': str(ip), 'kind': 'dns' if dns else 'ip', 'syscall': line})
    return attempts


def execute(command, directory, log, trace=False):
    log.parent.mkdir(parents=True, exist_ok=True)
    if trace:
        command = ['strace', '-f', '-ttt', '-yy', '-s', '256', '-e', 'trace=network', '-o', str(log.with_suffix('.strace')), *command]
    with log.open('w', encoding='utf-8') as stream:
        result = subprocess.run(command, cwd=directory, stdout=stream, stderr=subprocess.STDOUT, timeout=360)
    if result.returncode:
        raise RuntimeError(f'Consumer failed ({result.returncode}): {log}')


def command(tool, evidence, active, count, index, offline):
    if tool == 'gradle':
        result = ['gradle', '--no-daemon', '--console=plain', '--rerun-tasks', 'test', '-PauditPlugin=' + str(active).lower(), '-PauditEvidence=' + str(evidence), '-Pshardpilot.count=' + str(count), '-Pshardpilot.index=' + str(index)]
        return result + (['--offline'] if offline else [])
    result = ['mvn', '-B', '-ntp', '-Dmaven.repo.local=/work/home/.m2/repository', '-DauditEvidence=' + str(evidence), '-Dshardpilot.count=' + str(count), '-Dshardpilot.index=' + str(index), 'verify']
    return result + (['-Psharded'] if active else []) + (['--offline'] if offline else [])


def cached_artifacts():
    report = json.loads(Path('/registry/registry-verification.json').read_text())
    found = []
    for record in report['artifacts']:
        name = record['file'].replace('\\', '/').split('/')[-1]
        if name not in tuple(artifact + '-' + VERSION + '.jar' for artifact in ('shardpilot', 'shardpilot-core', 'shardpilot-maven-plugin')):
            continue
        files = list(Path('/work/home').rglob(name))
        if not files or any(hashlib.sha256(path.read_bytes()).hexdigest() != record['sha256'] for path in files):
            raise ValueError('Resolved artifact differs from verified registry bytes: ' + name)
        found.append({'file': name, 'sha256': record['sha256']})
    if len(found) != 3:
        raise ValueError('Missing resolved plugin artifacts')
    return found


def toolchain():
    tools = Path('/work/tools')
    tools.mkdir()
    inputs = [('gradle.zip', 'gradle-9.9.1', 'gradle',
               'd8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228'),
              ('maven.zip', 'apache-maven-3.10.0', 'mvn',
               '1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff')]
    for name, root, executable, checksum in inputs:
        archive = Path('/registry/tool-image') / name
        if hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
            raise ValueError('Build-tool archive changed before extraction')
        with zipfile.ZipFile(archive) as content:
            for entry in content.infolist():
                path = Path(entry.filename)
                if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] != root:
                    raise ValueError('Unsafe toolchain archive path')
            content.extractall(tools)
        (tools / root / 'bin' / executable).chmod(0o755)
        os.environ['PATH'] = str(tools / root / 'bin') + os.pathsep + os.environ['PATH']


def restore_seed(home):
    archive = Path('/seed/homes.tar')
    if not archive.is_file():
        return
    with tarfile.open(archive) as snapshot:
        members = snapshot.getmembers()
        if sum(entry.size for entry in members) > 512 * 1024 * 1024:
            raise ValueError('Excessive audit dependency seed')
        for entry in members:
            path = Path(entry.name)
            if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] not in ('.gradle', '.m2') or not (entry.isfile() or entry.isdir()):
                raise ValueError('Unsafe audit dependency seed')
        snapshot.extractall(home, filter='data')


def checkpoint_seed(home):
    temporary = Path('/seed/homes.partial')
    def included(entry):
        if entry.issym() or entry.islnk() or any(part in ('daemon', 'workers') for part in Path(entry.name).parts) or entry.name.endswith(('.lock', '.lck')):
            return None
        return entry if entry.isfile() or entry.isdir() else None
    with tarfile.open(temporary, 'w') as snapshot:
        for name in ('.gradle', '.m2'):
            if (home / name).is_dir():
                snapshot.add(home / name, arcname=name, filter=included)
    temporary.replace('/seed/homes.tar')


def runs(warm):
    Path('/evidence/summary.json').unlink(missing_ok=True)
    home = Path('/work/home')
    home.mkdir(parents=True, exist_ok=True)
    toolchain()
    restore_seed(home)
    if not warm and not Path('/seed/homes.tar').is_file():
        raise ValueError('Missing prepared dependency seed')
    control = None
    if not warm:
        # Fail before consumer runs if tracing is unavailable or cannot see a blocked attempt.
        probe = Path('/evidence/positive-control.log')
        execute(['java', '/runner/NetworkProbe.java'], Path('/work'), probe, trace=True)
        control = network_attempts(probe.with_suffix('.strace').read_text())
        if not any(item['address'] == '198.51.100.1' for item in control) or 'connection blocked' not in probe.read_text():
            raise ValueError('Network tracer positive control failed')
    cases = []
    reused = []
    for tool in ('gradle', 'maven'):
        if warm and tool == 'gradle':
            cached = list((home / '.gradle').rglob('shardpilot-' + VERSION + '.jar'))
            registry = json.loads(Path('/registry/registry-verification.json').read_text())
            expected = next(row['sha256'] for row in registry['artifacts'] if row['file'].endswith('/shardpilot-' + VERSION + '.jar'))
            if cached and all(hashlib.sha256(path.read_bytes()).hexdigest() == expected for path in cached):
                reused.append(tool)
                print('gradle warmup: reusing verified public-byte checkpoint', flush=True)
                continue
        cohorts = [('baseline', False, 1, 1), ('2-1', True, 2, 1)] if warm else [('baseline', False, 1, 1)] + [(f'{count}-{index}', True, count, index) for count in (2, 4) for index in range(1, count + 1)]
        for name, active, count, index in cohorts:
            directory = Path('/work/projects') / tool / name
            shutil.copytree(Path('/fixtures') / tool, directory)
            evidence = Path('/evidence') / tool / name / 'events.tsv'
            evidence.parent.mkdir(parents=True, exist_ok=True)
            evidence.unlink(missing_ok=True)
            execute(command(tool, evidence, active, count, index, not warm), directory, evidence.with_name('build.log'), trace=not warm)
            if not evidence.is_file():
                raise ValueError('Native execution listener did not run')
            attempts = [] if warm else network_attempts(evidence.with_name('build.strace').read_text())
            cases.append({'tool': tool, 'cohort': name, 'outboundAttempts': attempts})
            shutil.copytree(directory / ('build/test-results/test' if tool == 'gradle' else 'target/surefire-reports'), evidence.parent / 'unit-xml', dirs_exist_ok=True)
            if tool == 'maven':
                shutil.copytree(directory / 'target/failsafe-reports', evidence.parent / 'integration-xml', dirs_exist_ok=True)
            print(tool + ' ' + name + ': passed', flush=True)
        if warm:
            # A later fixture/reporting failure must not discard already resolved public bytes.
            checkpoint_seed(home)
    resolved = cached_artifacts()
    if warm:
        checkpoint_seed(home)
    else:
        for tool, expected in (('gradle', 8), ('maven', 16)):
            base = Path('/evidence') / tool
            rows = lambda path: Counter(path.read_text().splitlines())
            baseline = rows(base / 'baseline/events.tsv')
            if sum(baseline.values()) != expected:
                raise ValueError('Unexpected native baseline size')
            for count in (2, 4):
                union = Counter()
                for index in range(1, count + 1):
                    union.update(rows(base / f'{count}-{index}/events.tsv'))
                if union != baseline:
                    raise ValueError('Isolated native executions differ from baseline')
        if any(case['outboundAttempts'] for case in cases):
            raise ValueError('An isolated consumer attempted external networking; inspect raw traces')
    write(Path('/evidence/summary.json'), json.dumps({'version': VERSION, 'phase': 'warm' if warm else 'isolated', 'reusedWarmups': reused, 'resolvedArtifacts': resolved, 'cases': cases, 'positiveControl': None if warm else control, 'passed': True}, indent=2) + '\n')


def tag_binding(payload, commit, version=VERSION):
    if not payload.startswith(f'object {commit}\ntype commit\ntag v{version}\n'):
        raise ValueError('Signed tag payload does not bind the expected release commit')


def signer_cohort(results):
    if len(results) != 10 or results[0]['file'] != 'source-tag' or len({row['file'] for row in results}) != 10:
        raise ValueError('Incomplete or duplicated signature cohort')
    if len({row['primaryFingerprint'] for row in results}) != 1:
        raise ValueError('Artifact signatures differ from the release tag signing identity')


def signatures():
    Path('/evidence/signatures.json').unlink(missing_ok=True)
    directory = Path('/evidence/gpg')
    directory.mkdir(parents=True, exist_ok=True)
    keyring = Path('/work/public-keyring')
    keyring.mkdir(mode=0o700)
    gpg = ['gpg', '--no-options', '--homedir', str(keyring), '--batch', '--no-auto-key-retrieve', '--auto-key-locate', 'clear']
    execute([*gpg, '--import', '/registry/publisher-public-keys.asc'], Path('/work'), directory / 'import.log')
    source = json.loads(Path('/registry/source-tag.json').read_text())
    registry = json.loads(Path('/registry/registry-verification.json').read_text())
    tag_binding(source['verification']['payload'], registry['sourceCommit'])
    write(directory / 'tag.payload', source['verification']['payload'])
    write(directory / 'tag.asc', source['verification']['signature'])
    entries = [('source-tag', directory / 'tag.payload', directory / 'tag.asc')]
    for record in registry['artifacts']:
        artifact = Path('/registry') / record['file'].replace('\\', '/')
        if record['signatureAvailable']:
            entries.append((record['file'], artifact, artifact.with_name(artifact.name + '.asc')))
    results = []
    for index, (name, artifact, signature) in enumerate(entries):
        log = directory / f'verify-{index}.log'
        execute([*gpg, '--status-fd', '1', '--verify', str(signature), str(artifact)], Path('/work'), log)
        matches = re.findall(r'\[GNUPG:\] VALIDSIG ([A-F0-9]+) (.*)', log.read_text())
        if len(matches) != 1:
            raise ValueError('Missing valid OpenPGP signature')
        results.append({'file': name, 'signingFingerprint': matches[0][0], 'primaryFingerprint': matches[0][1].split()[-1]})
    signer_cohort(results)
    if results[0]['primaryFingerprint'] != os.environ['SHARDPILOT_AUDIT_FINGERPRINT']:
        raise ValueError('Release signing identity differs from the reviewed publisher key')
    write(Path('/evidence/signatures.json'), json.dumps({'publicKeySource': 'https://github.com/sjacek.gpg', 'verifiedSignatures': results, 'passed': True}, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('fixtures', 'warm', 'isolated', 'signatures'))
    parser.add_argument('--destination', type=Path)
    parser.add_argument('--version', default=VERSION)
    args = parser.parse_args()
    if args.phase == 'fixtures':
        fixtures(args.destination, args.version)
    else:
        tool_versions()
        if args.phase == 'signatures':
            signatures()
        else:
            runs(args.phase == 'warm')

"""Reviewed producer plugin scope and bootstrap of the native Maven lifecycle guard."""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parent.parent
POLICY = 'security/maven-tooling-scope.json'


def guard_path(root=ROOT):
    source = root / 'build-logic/maven-tooling-guard/MavenToolingGuard.java'
    identity = hashlib.sha256(source.read_bytes() + (root / POLICY).read_bytes()).hexdigest()[:24]
    return root / 'build/dependency-verification/tooling-guard' / ('shardpilot-maven-tooling-guard-' + identity + '.jar')


def policy(root=ROOT):
    value = json.loads((root / POLICY).read_text(encoding='utf-8'))
    if value.get('schemaVersion') != 1 or not re.fullmatch(r'3\.9\.\d+', value.get('guardApiVersion', '')):
        raise ValueError('Invalid Maven tooling policy')
    coordinates, prefixes = set(), set()
    for entry in value['plugins']:
        coordinate, prefix = entry['coordinate'], entry['prefix']
        if not re.fullmatch(r'[\w.-]+:[\w.-]+', coordinate) or not re.fullmatch(r'[\w-]+', prefix) \
                or coordinate in coordinates or prefix in prefixes or entry['scope'] not in ('blocking', 'inherited'):
            raise ValueError('Invalid or duplicate Maven plugin classification')
        if entry['scope'] == 'inherited' and (coordinate != 'org.apache.maven.plugins:maven-site-plugin' or not entry.get('reason')):
            raise ValueError('Only the explicitly guarded Site realm may be inherited-only')
        coordinates.add(coordinate)
        prefixes.add(prefix)
    site = next((entry for entry in value['plugins'] if entry['coordinate'] == 'org.apache.maven.plugins:maven-site-plugin'), None)
    if site is None or site['scope'] != 'inherited' or site['prefix'] != 'site':
        raise ValueError('Maven Site classification is required')
    return value


def review_model(model, value):
    entries = {entry['coordinate']: entry for entry in value['plugins']}
    for project in model['projects']:
        for plugin in project['plugins']:
            coordinate = plugin['groupId'] + ':' + plugin['artifactId']
            if coordinate not in entries:
                raise ValueError('Unclassified Maven plugin: ' + coordinate)
            if entries[coordinate]['scope'] == 'inherited':
                allowed = {('default-site', 'site', ('site',)), ('default-deploy', 'site-deploy', ('deploy',))}
                if any((entry['id'], entry['phase'], tuple(entry['goals'])) not in allowed for entry in plugin['executions']):
                    raise ValueError('Maven Site execution is outside inherited default bindings')


def select_blocking(document, value):
    """Keep a shared library if any owning realm is blocking; never filter by library name/CVE."""
    entries = {entry['coordinate']: entry for entry in value['plugins']}
    components = {item['purl']: item for item in document['components']}
    selected = set()
    for component in document['components']:
        owners = [item['value'] for item in component.get('properties', []) if item['name'] == 'shardpilot:maven-plugin-realm']
        if not owners:
            raise ValueError('Maven component has no owning plugin realm')
        for owner in owners:
            plugin = components[owner]
            coordinate = plugin['group'] + ':' + plugin['name']
            if coordinate not in entries:
                raise ValueError('Unclassified Maven plugin: ' + coordinate)
            if entries[coordinate]['scope'] == 'blocking':
                selected.add(component['purl'])
    if not selected:
        raise ValueError('Blocking Maven tooling scope is empty')
    result = copy.deepcopy(document)
    result['components'] = [item for item in result['components'] if item['purl'] in selected]
    root = result['metadata']['component']['bom-ref']
    result['dependencies'] = [{'ref': root, 'dependsOn': sorted(selected)},
                              *({'ref': ref, 'dependsOn': []} for ref in sorted(selected))]
    return result


def guard(root=ROOT):
    """Compile authored guard sources against checksum-verified Maven APIs, outside download caches."""
    # Import lazily: verifier.environment calls this bootstrap after checking its own external JAR.
    import maven_verification as verification
    value = policy(root)
    checksums = verification.load_checksums(root / '.mvn/checksums.sha256')
    output = root / 'build/dependency-verification/tooling-guard'
    output.mkdir(parents=True, exist_ok=True)
    apis = []
    for artifact in ('maven-core', 'maven-model', 'maven-artifact'):
        version = value['guardApiVersion']
        expected = checksums[f'org.apache.maven:{artifact}:jar:{version}']
        target = output / (artifact + '-' + version + '.jar')
        if target.is_symlink():
            raise ValueError('Maven guard API must not be a symlink')
        if not target.exists():
            # Reuse only exact known bytes. Otherwise fetch a fixed Central coordinate.
            cached = root / 'build/ci-dependencies/maven/org/apache/maven' / artifact / version / target.name
            if cached.is_file():
                payload = cached.read_bytes()
            else:
                url = f'https://repo.maven.apache.org/maven2/org/apache/maven/{artifact}/{version}/{target.name}'
                with urllib.request.urlopen(url, timeout=60) as response:
                    payload = response.read(3_000_001)
            if len(payload) > 3_000_000 or hashlib.sha256(payload).hexdigest() != expected:
                raise ValueError('Maven guard API checksum mismatch')
            target.write_bytes(payload)
        if verification.digest(target) != expected:
            raise ValueError('Cached Maven guard API checksum mismatch')
        apis.append(target)
    java_home = os.environ.get('JAVA_HOME')
    compiler = str(Path(java_home) / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')) if java_home else shutil.which('javac')
    if not compiler:
        raise ValueError('A JDK is required to compile the producer Maven guard')
    source = root / 'build-logic/maven-tooling-guard/MavenToolingGuard.java'
    target = guard_path(root)
    if target.is_symlink():
        raise ValueError('Maven guard must not be a symlink')
    # Recompile on bootstrap: a merely present generated JAR is never trusted as executable code.
    with tempfile.TemporaryDirectory(prefix='guard-', dir=output) as temporary:
        classes = Path(temporary)
        subprocess.run([compiler, '--release', '17', '-encoding', 'UTF-8', '-classpath', os.pathsep.join(map(str, apis)),
                        '-d', str(classes), str(source)], check=True)
        properties = ''.join('plugin.' + e['coordinate'].replace(':', '\\:') + '=' + e['scope'] + '\n'
                             + 'prefix.' + e['prefix'] + '=' + e['coordinate'] + '\n' for e in value['plugins'])
        descriptor = ('<component-set><components><component><role>org.apache.maven.AbstractMavenLifecycleParticipant</role>'
                      '<role-hint>shardpilot-tooling-guard</role-hint><implementation>io.github.sjacek.build.MavenToolingGuard</implementation>'
                      '</component></components></component-set>')
        with zipfile.ZipFile(classes / 'guard.jar', 'w') as jar:
            def write(name, content):
                jar.writestr(zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0)), content)

            for path in classes.rglob('*.class'):
                write(path.relative_to(classes).as_posix(), path.read_bytes())
            write('META-INF/plexus/components.xml', descriptor)
            write('maven-tooling.properties', properties)
            write('build-inputs.json', json.dumps({
                'sourceSha256': verification.digest(source), 'policySha256': verification.digest(root / POLICY)}))
        if not target.is_file() or target.read_bytes() != (classes / 'guard.jar').read_bytes():
            (classes / 'guard.jar').replace(target)
    return target.resolve()


def guard_inventory(candidate, root=ROOT):
    import security_reports as reports
    import maven_verification as verification
    value = policy(root)
    source = root / 'build-logic/maven-tooling-guard/MavenToolingGuard.java'
    jar = guard_path(root)
    with zipfile.ZipFile(jar) as archive:
        inputs = json.loads(archive.read('build-inputs.json'))
    if inputs != {'sourceSha256': reports.sha256(source), 'policySha256': reports.sha256(root / POLICY)}:
        raise ValueError('Maven tooling guard was compiled for different source or policy')
    proof = root / 'build/dependency-verification/tooling-guard-native.json'
    control = reports.read_json(proof)
    if control.get('passed') is not True or control.get('offline') is not True \
            or control.get('guardSha256') != reports.sha256(jar) \
            or control.get('probeSourceSha256') != reports.sha256(root / 'scripts/verify_maven_tooling.py'):
        raise ValueError('Missing native proof for this Maven tooling guard')
    purl = 'pkg:generic/shardpilot/maven-tooling-guard@' + inputs['sourceSha256']
    components = [{'type': 'library', 'group': 'shardpilot', 'name': 'maven-tooling-guard',
                   'version': inputs['sourceSha256'], 'purl': purl, 'bom-ref': purl,
                   'properties': [{'name': 'shardpilot:identity-kind', 'value': 'authored-maven-startup-guard'}],
                   'hashes': [{'alg': 'SHA-256', 'content': reports.sha256(jar)}]}]
    files = [source, root / POLICY, jar, proof, root / 'scripts/verify_maven_tooling.py']
    checksums = verification.load_checksums(root / '.mvn/checksums.sha256')
    for artifact in ('maven-core', 'maven-model', 'maven-artifact'):
        version = value['guardApiVersion']
        path = jar.parent / (artifact + '-' + version + '.jar')
        if reports.sha256(path) != checksums[f'org.apache.maven:{artifact}:jar:{version}']:
            raise ValueError('Maven guard API differs from its reviewed checksum')
        purl = f'pkg:maven/org.apache.maven/{artifact}@{version}'
        components.append({'type': 'library', 'group': 'org.apache.maven', 'name': artifact, 'version': version,
                           'purl': purl, 'bom-ref': purl, 'hashes': [{'alg': 'SHA-256', 'content': reports.sha256(path)}]})
        files.append(path)
    return ({'metadata': {'component': copy.deepcopy(candidate)}, 'components': components,
             'dependencies': [{'ref': candidate['bom-ref'], 'dependsOn': [item['purl'] for item in components]}]},
            [{'file': path.relative_to(root).as_posix(), 'sha256': reports.sha256(path)} for path in files])

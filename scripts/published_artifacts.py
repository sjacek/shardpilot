"""Read public release bytes without credentials and bind them to a source snapshot.

Registry checksums detect mismatched downloads; they are not independent publisher authentication.
OpenPGP validation is performed separately with an isolated public keyring in the audit container.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import urllib.error
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
CENTRAL = 'https://repo.maven.apache.org/maven2/'
PORTAL = 'https://plugins.gradle.org/m2/'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def release_version(version):
    if not isinstance(version, str) or not re.fullmatch(r'(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)', version):
        raise ValueError('An immutable stable release version is required')
    return version


def audit_policy():
    policy = json.loads((ROOT / 'security/published-artifact-audit.json').read_text(encoding='utf-8'))
    if policy.get('schemaVersion') != 1 or not re.fullmatch(r'[a-f0-9]{40}', policy.get('defaultSourceCommit', '')) \
            or not re.fullmatch(r'[A-F0-9]{40}', policy.get('publisherPrimaryFingerprint', '')):
        raise ValueError('Invalid published-artifact audit policy')
    release_version(policy.get('defaultPublishedVersion'))
    return policy


def digest(data, algorithm='sha256'):
    return hashlib.new(algorithm, data).hexdigest()


def checked_checksum(data, text, algorithm):
    fields = text.decode('ascii').split()
    if not fields:
        raise ValueError('Empty registry checksum')
    expected = fields[0].lower()
    if not re.fullmatch('[0-9a-f]{' + str(hashlib.new(algorithm).digest_size * 2) + '}', expected):
        raise ValueError('Malformed registry checksum')
    if digest(data, algorithm) != expected:
        raise ValueError('Registry checksum mismatch')
    return expected


def download(url, destination, optional=False):
    request = urllib.request.Request(url, headers={'User-Agent': 'ShardPilot-public-artifact-verification'})
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            if not response.geturl().startswith('https://'):
                raise ValueError('Insecure registry redirect')
            payload = response.read(64 * 1024 * 1024 + 1)
            if len(payload) > 64 * 1024 * 1024:
                raise ValueError('Artifact exceeds audit download limit')
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(payload)
            return {'url': url, 'resolvedUrl': response.geturl(), 'size': len(payload), 'sha256': digest(payload)}
    except urllib.error.HTTPError as failure:
        if optional and failure.code == 404:
            return None
        raise


def git(*arguments):
    return subprocess.check_output(['git', *arguments], cwd=ROOT)


def safe_archive(archive):
    infos = archive.infolist()
    names = [entry.filename for entry in infos]
    if len(set(names)) != len(names) or sum(entry.file_size for entry in infos) > 64 * 1024 * 1024:
        raise ValueError('Duplicate archive entries or excessive expanded size')
    for entry in infos:
        raw_name = entry.orig_filename
        path = PurePosixPath(raw_name)
        if path.is_absolute() or '..' in path.parts or '\\' in raw_name or (path.parts and ':' in path.parts[0]) or (entry.external_attr >> 16) & 0o170000 == 0o120000:
            raise ValueError('Unsafe archive entry')
    return names


def verify_sources(path, commit, modules):
    expected = {}
    for module in modules:
        prefix = module + '/src/main/java/'
        for name in git('ls-tree', '-r', '--name-only', commit, prefix).decode().splitlines():
            if name.endswith('.java'):
                expected[name[len(prefix):]] = name
    with zipfile.ZipFile(path) as archive:
        names = safe_archive(archive)
        actual = {name for name in names if name.endswith('.java')}
        if actual != set(expected):
            raise ValueError(f'Source inventory differs from tag: {path.name}')
        for name, source in expected.items():
            # Published sources may originate in a Windows checkout; normalize line endings only.
            if archive.read(name).replace(b'\r\n', b'\n') != git('show', commit + ':' + source).replace(b'\r\n', b'\n'):
                raise ValueError('Published Java source differs from tag: ' + name)
    return {'javaSources': len(expected), 'comparison': 'exact bytes after CRLF normalization'}


def fetch(output, version):
    release_version(version)
    output.mkdir(parents=True, exist_ok=True)
    report_path = output / 'registry-verification.json'
    report_path.unlink(missing_ok=True)
    commit = git('rev-parse', 'v' + version + '^{}').decode().strip()
    records = []
    specifications = []
    for artifact in ('shardpilot-parent', 'shardpilot-core', 'shardpilot-maven-plugin'):
        suffixes = ['.pom'] if artifact.endswith('parent') else ['.pom', '.jar', '-sources.jar', '-javadoc.jar']
        for suffix in suffixes:
            name = artifact + '-' + version + suffix
            specifications.append(('central', CENTRAL + 'io/github/sjacek/' + artifact + '/' + version + '/' + name, name))
    marker = 'io.github.sjacek.shardpilot.gradle.plugin'
    marker_name = marker + '-' + version + '.pom'
    specifications.append(('portal', PORTAL + 'io/github/sjacek/shardpilot/' + marker + '/' + version + '/' + marker_name, marker_name))
    for suffix in ('.pom', '.module', '.jar', '-sources.jar', '-javadoc.jar'):
        name = 'shardpilot-' + version + suffix
        specifications.append(('portal', PORTAL + 'io/github/sjacek/shardpilot/' + version + '/' + name, name))
    for registry, url, name in specifications:
        target = output / registry / name
        record = {'registry': registry, 'file': target.relative_to(output).as_posix(), **download(url, target)}
        record['checksums'] = {}
        for algorithm in ('sha256', 'sha512'):
            checksum = download(url + '.' + algorithm, target.with_name(name + '.' + algorithm), optional=registry == 'portal')
            if checksum:
                record['checksums'][algorithm] = checked_checksum(target.read_bytes(), target.with_name(name + '.' + algorithm).read_bytes(), algorithm)
        signature = download(url + '.asc', target.with_name(name + '.asc'), optional=registry == 'portal')
        record['signatureAvailable'] = signature is not None
        if name.endswith('-sources.jar'):
            modules = ['shardpilot-core', 'shardpilot-gradle-plugin'] if registry == 'portal' else [name.split('-' + version)[0]]
            record['sourceComparison'] = verify_sources(target, commit, modules)
        if name.endswith('.jar'):
            with zipfile.ZipFile(target) as archive:
                names = safe_archive(archive)
                classes = [name for name in names if name.endswith('.class')]
                majors = sorted({int.from_bytes(archive.read(name)[6:8], 'big') for name in classes})
                if any(major > 61 for major in majors):
                    raise ValueError('Published bytecode exceeds declared Java 17 target')
                record['classes'] = len(classes)
                record['classMajorVersions'] = majors
        records.append(record)
        print(registry + ': verified ' + name, flush=True)
    marker_pom = ET.parse(output / 'portal' / marker_name).getroot()
    dependency = marker_pom.find('m:dependencies/m:dependency', NS)
    if dependency is None or [dependency.findtext('m:' + field, namespaces=NS) for field in ('groupId', 'artifactId', 'version')] != ['io.github.sjacek', 'shardpilot', version]:
        raise ValueError('Portal marker points to unexpected implementation coordinates')
    module = json.loads((output / 'portal' / ('shardpilot-' + version + '.module')).read_text())
    for variant in module['variants']:
        for entry in variant.get('files', []):
            artifact = output / 'portal' / entry['name']
            if entry['sha256'] != digest(artifact.read_bytes()):
                raise ValueError('Gradle module metadata artifact digest mismatch')
    download('https://github.com/sjacek.gpg', output / 'publisher-public-keys.asc')
    report = {'schemaVersion': 1, 'version': version, 'sourceTag': 'v' + version, 'sourceCommit': commit,
              'checkedAt': datetime.now(timezone.utc).isoformat(), 'artifacts': records,
              'signatureVerification': 'pending isolated GnuPG check',
              'limits': ['Registry sidecars and module metadata are not independent authentication.',
                         'Source JAR matching does not prove a reproducible binary build.']}
    report_path.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', default='0.4.0')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/published-artifact-audit')
    arguments = parser.parse_args()
    fetch(arguments.output.resolve(), arguments.version)

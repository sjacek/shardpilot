"""Bootstrap a checksum-pinned Maven verifier before Maven can load external build code."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request

import maven_tooling


ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / '.mvn/checksums.sha256'
CONSUMERS = ROOT / 'build/dependency-verification/consumer-checksums.sha256'


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def load_checksums(path):
    """Reject malformed/duplicate identities before the extension's permissive text parser sees them."""
    entries = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if line.startswith('#'):
            continue
        parts = line.split(' ')
        if len(parts) != 2 or not re.fullmatch(r'[^\s:]+:[^\s:]+:[^\s:]+(?::[^\s:]+)?:[^\s:]+', parts[0]) \
                or not re.fullmatch(r'[a-f0-9]{64}', parts[1]) or parts[0] in entries:
            raise ValueError('Invalid or duplicate Maven verification checksum')
        entries[parts[0]] = parts[1]
    if not entries:
        raise ValueError('Maven verification baseline is empty')
    return entries


def extension():
    """Never load a merely present JAR: check its pinned digest on every bootstrap, including cache hits."""
    policy = json.loads((ROOT / 'security/dependency-verification.json').read_text(encoding='utf-8'))
    value = policy['mavenExtension']
    if policy['schemaVersion'] != 1 or not re.fullmatch(r'\d+\.\d+\.\d+', value['version']) \
            or not re.fullmatch(r'[a-f0-9]{64}', value['sha256']):
        raise ValueError('Invalid Maven verifier policy')
    expected_url = ('https://repo.maven.apache.org/maven2/codes/rafael/mavenchecksumextension/'
                    f'maven-checksum-extension/{value["version"]}/maven-checksum-extension-{value["version"]}.jar')
    if value['url'] != expected_url:
        raise ValueError('Maven verifier must come from the pinned Maven Central coordinate')
    target = ROOT / 'build/dependency-verification' / f'maven-checksum-extension-{value["version"]}.jar'
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_symlink():
        raise ValueError('Maven verifier must not be a symlink')
    if not target.exists():
        # A failed or partial download never acquires the executable JAR's final name.
        with urllib.request.urlopen(value['url'], timeout=60) as response:
            payload = response.read(2_000_001)
        if len(payload) > 2_000_000 or hashlib.sha256(payload).hexdigest() != value['sha256']:
            raise ValueError('Maven verifier download checksum mismatch')
        target.write_bytes(payload)
    if digest(target) != value['sha256']:
        raise ValueError('Cached Maven verifier checksum mismatch')
    return target.resolve()


def quoted(path):
    value = str(path)
    if any(character in value for character in ('"', '\r', '\n')):
        raise ValueError('Unsafe path in Maven verifier JVM options')
    # POSIX mvn expands MAVEN_OPTS without reparsing embedded quotes; Windows cmd parses them.
    if not any(character.isspace() for character in value):
        return value
    if os.name == 'nt':
        return '"' + value + '"'
    raise ValueError('POSIX Maven verification requires a checkout path without whitespace')


def environment(collect=False, original=None):
    """Return JVM startup options, not a late lifecycle plugin that would run after dependency code."""
    env = dict(os.environ if original is None else original)
    if collect and (env.get('CI') or env.get('GITHUB_ACTIONS')):
        raise ValueError('Checksum collection is a maintainer operation and is forbidden in CI')
    if not collect:
        load_checksums(BASELINE)
    verifier = extension()
    previous = env.get('MAVEN_OPTS', '')
    if any(value in previous for value in ('codes.rafael.mavenchecksumextension.', 'maven.ext.class.path')):
        raise ValueError('Conflicting Maven verifier JVM options')
    guard = maven_tooling.guard(ROOT)
    mode = 'collect' if collect else 'enforce'
    env['MAVEN_OPTS'] = (previous + ' -Dmaven.ext.class.path=' + quoted(os.pathsep.join(map(str, (verifier, guard))))
                         + ' -Dcodes.rafael.mavenchecksumextension.file=' + quoted(BASELINE.resolve())
                         + ' -Dcodes.rafael.mavenchecksumextension.mode=' + mode
                         + ' -Dcodes.rafael.mavenchecksumextension.algorithm=SHA-256'
                         + ' -Dcodes.rafael.mavenchecksumextension.snapshots=true'
                         + ' -Dcodes.rafael.mavenchecksumextension.locals=true'
                         + ' -Dcodes.rafael.mavenchecksumextension.reactor=false'
                         + (' -Dcodes.rafael.mavenchecksumextension.append=true' if collect else '')).strip()
    env['SHARDPILOT_MAVEN_VERIFICATION'] = mode
    env['SHARDPILOT_VERIFICATION_PYTHON'] = sys.executable
    env['SHARDPILOT_MAVEN_CONSUMER_OPTS'] = '-Dcodes.rafael.mavenchecksumextension.file=' + quoted(CONSUMERS.resolve())
    return env


def consumer_checksums(repository, version):
    """Trust only this invocation's locally installed ShardPilot candidates, never a whole group prefix."""
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-SNAPSHOT)?', version):
        raise ValueError('Invalid local candidate version')
    entries = load_checksums(BASELINE)
    if os.environ.get('SHARDPILOT_MAVEN_VERIFICATION') == 'collect' and CONSUMERS.exists():
        entries.update({key: value for key, value in load_checksums(CONSUMERS).items()
                        if not key.startswith('io.github.sjacek:shardpilot')})
    for artifact in ('shardpilot-parent', 'shardpilot-core', 'shardpilot-maven-plugin'):
        for kind in (('pom',) if artifact == 'shardpilot-parent' else ('pom', 'jar')):
            identity = f'io.github.sjacek:{artifact}:{kind}:{version}'
            if identity in entries:
                raise ValueError('A build candidate must not have a versioned external checksum')
            path = repository / 'io/github/sjacek' / artifact / version / f'{artifact}-{version}.{kind}'
            if path.is_symlink() or not path.is_file():
                raise ValueError('Invoker must install the current reactor before preparing consumer checksums')
            entries[identity] = digest(path)
    CONSUMERS.parent.mkdir(parents=True, exist_ok=True)
    content = ''.join(key + ' ' + entries[key] + '\n' for key in sorted(entries)).encode('utf-8')
    if CONSUMERS.exists() and CONSUMERS.read_bytes() == content:
        return CONSUMERS.resolve()
    # Repeated Invoker hooks produce identical bytes; replacement never exposes a partial baseline.
    with tempfile.NamedTemporaryFile(mode='wb', prefix='consumer-', suffix='.tmp',
                                     dir=CONSUMERS.parent, delete=False) as target:
        temporary = Path(target.name)
        target.write(content)
    try:
        try:
            temporary.replace(CONSUMERS)
        except PermissionError:
            # Windows readers can prevent replacement after a concurrent hook installed the same
            # complete policy. Accept only identical bytes; never hide a different policy or denial.
            if not CONSUMERS.is_file() or CONSUMERS.read_bytes() != content:
                raise
    finally:
        temporary.unlink(missing_ok=True)
    return CONSUMERS.resolve()


def consumer_environment(repository, version, original=None):
    env = dict(os.environ if original is None else original)
    if env.get('SHARDPILOT_MAVEN_VERIFICATION'):
        path = consumer_checksums(repository, version)
        env['MAVEN_OPTS'] = env.get('MAVEN_OPTS', '') + ' -Dcodes.rafael.mavenchecksumextension.file=' + quoted(path)
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'run', 'consumers', 'warm'))
    parser.add_argument('--collect', action='store_true')
    parser.add_argument('--repository', type=Path)
    parser.add_argument('--version')
    options, arguments = parser.parse_known_args()
    if options.command == 'consumers':
        if options.repository is None or options.version is None or options.collect or arguments:
            parser.error('consumers requires only --repository and --version')
        print(consumer_checksums(options.repository, options.version))
        return 0
    env = environment(options.collect)
    if options.command == 'prepare':
        if arguments or not env.get('GITHUB_ENV'):
            parser.error('prepare requires GITHUB_ENV; use run locally')
        with Path(env['GITHUB_ENV']).open('a', encoding='utf-8') as target:
            for name in ('MAVEN_OPTS', 'SHARDPILOT_MAVEN_VERIFICATION', 'SHARDPILOT_VERIFICATION_PYTHON',
                         'SHARDPILOT_MAVEN_CONSUMER_OPTS'):
                if '\n' in env[name] or '\r' in env[name]:
                    raise ValueError('Invalid multi-line GitHub environment value')
                target.write(name + '=' + env[name] + '\n')
        print('Prepared SHA-256-verified Maven extension in enforce mode')
        return 0
    if options.command == 'warm':
        if arguments:
            parser.error('warm does not accept Maven arguments')
        command = [sys.executable, '-B', str(ROOT / 'scripts/ci_dependency_cache.py'), 'warm-maven']
    else:
        wrapper = [str(ROOT / 'mvnw.cmd')] if os.name == 'nt' else ['bash', str(ROOT / 'mvnw')]
        command = [*wrapper, *(arguments[1:] if arguments[:1] == ['--'] else arguments)]
    result = subprocess.run(command, cwd=ROOT, env=env).returncode
    if result == 0 and options.collect and CONSUMERS.exists():
        entries = load_checksums(BASELINE)
        entries.update({key: value for key, value in load_checksums(CONSUMERS).items()
                        if not key.startswith('io.github.sjacek:shardpilot')})
        BASELINE.write_text(''.join(key + ' ' + entries[key] + '\n' for key in sorted(entries)), encoding='utf-8')
    return result


if __name__ == '__main__':
    raise SystemExit(main())

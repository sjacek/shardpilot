"""Prove early Maven rejection using harmless synthetic POMs in an isolated local repository."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

import maven_verification as verification


def verify():
    baseline = verification.load_checksums(verification.BASELINE)
    parent_identity = 'example.verification:checksum-parent:pom:1.0'
    parent = (b'<project><modelVersion>4.0.0</modelVersion><groupId>example.verification</groupId>'
              b'<artifactId>checksum-parent</artifactId><version>1.0</version><packaging>pom</packaging></project>')
    directory = verification.ROOT / 'build/dependency-verification'
    directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='native-probe-', dir=directory) as temporary:
        root = Path(temporary)
        cached = root / 'repository/example/verification/checksum-parent/1.0/checksum-parent-1.0.pom'
        cached.parent.mkdir(parents=True)
        cached.write_bytes(parent)
        project = root / 'pom.xml'
        project.write_text('<project><modelVersion>4.0.0</modelVersion><parent>'
                           '<groupId>example.verification</groupId><artifactId>checksum-parent</artifactId>'
                           '<version>1.0</version><relativePath/></parent>'
                           '<artifactId>checksum-child</artifactId></project>', encoding='utf-8')
        policy = root / 'checksums.sha256'
        env = dict(os.environ)
        env.pop('MAVEN_OPTS', None)
        env = verification.environment(original=env)
        env['MAVEN_OPTS'] += ' -Dcodes.rafael.mavenchecksumextension.file=' + verification.quoted(policy)
        wrapper = [str(verification.ROOT / 'mvnw.cmd')] if os.name == 'nt' else ['bash', str(verification.ROOT / 'mvnw')]
        command = [*wrapper, '-B', '-ntp', '--offline', '-f', str(project),
                   '-Dmaven.repo.local=' + str(root / 'repository'), 'validate']

        def run(name, entries):
            policy.write_text(''.join(key + ' ' + entries[key] + '\n' for key in sorted(entries)), encoding='utf-8')
            result = subprocess.run(command, cwd=verification.ROOT, env=env, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
            (directory / (name + '.log')).write_text(result.stdout, encoding='utf-8')
            return result

        valid = {**baseline, parent_identity: hashlib.sha256(parent).hexdigest()}
        positive = run('valid-native-checksum', valid)
        if positive.returncode != 0:
            raise ValueError('The native checksum probe did not pass its positive control')
        cached.write_bytes(parent + b'<!-- changed cached bytes -->')
        changed = run('changed-native-checksum', valid)
        cached.write_bytes(parent)
        missing = run('missing-native-checksum', baseline)
        for result in (changed, missing):
            if result.returncode == 0 or 'ArtifactChecksumError' not in result.stdout:
                raise ValueError('Maven did not reject changed or unreviewed cached POM bytes')
        summary = {'positiveControl': positive.returncode, 'changedCachedPom': changed.returncode,
                   'missingChecksum': missing.returncode, 'passed': True}
        (directory / 'native-rejection.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
        return summary


if __name__ == '__main__':
    print(json.dumps(verify(), sort_keys=True))

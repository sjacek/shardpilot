"""Fail CI when a workflow or reviewed dependency baseline becomes mutable or permissive."""
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

import maven_verification


ROOT = Path(__file__).resolve().parent.parent


def verify(root=ROOT):
    policy = json.loads((root / 'security/action-pins.json').read_text(encoding='utf-8'))
    pins = {entry['action']: entry['sha'] for entry in policy['pins']}
    if policy['schemaVersion'] != 1 or len(pins) != len(policy['pins']) \
            or any(not re.fullmatch(r'[a-f0-9]{40}', value) for value in pins.values()):
        raise ValueError('Invalid immutable Actions policy')
    references = 0
    for path in sorted((root / '.github/workflows').glob('*.y*ml')):
        text = path.read_text(encoding='utf-8')
        for value in re.findall(r'^\s*(?:-\s+)?uses:\s*([^\s#]+)', text, flags=re.MULTILINE):
            if value.startswith('./'):
                if not re.fullmatch(r'\./\.github/workflows/[\w-]+\.yml', value) or not (root / value).is_file():
                    raise ValueError('Reusable workflow must come from this checkout')
                continue
            action, separator, revision = value.partition('@')
            if not separator or pins.get(action) != revision:
                raise ValueError('Unreviewed or mutable GitHub Action: ' + value)
            references += 1
        if re.search(r'--write-verification-metadata|--collect\b|dependency.verification[= ](?:off|lenient)'
                     r'|mavenchecksumextension\.(?:mode=collect|relaxed=true|dryrun=true|snapshots=false|locals=false)', text):
            raise ValueError('CI must enforce reviewed checksums, not write or bypass them')
    metadata = ET.parse(root / 'gradle/verification-metadata.xml').getroot()
    ns = {'v': 'https://schema.gradle.org/dependency-verification'}
    configuration = metadata.find('v:configuration', ns)
    if configuration is None or configuration.findtext('v:verify-metadata', namespaces=ns) != 'true' \
            or configuration.find('v:trusted-artifacts', ns) is not None:
        raise ValueError('Gradle metadata verification must not skip artifact groups')
    artifacts = metadata.findall('v:components/v:component/v:artifact', ns)
    if not artifacts:
        raise ValueError('Gradle verification baseline is empty')
    for artifact in artifacts:
        checksums = artifact.findall('v:sha256', ns)
        if not checksums or any(not re.fullmatch(r'[a-f0-9]{64}', value.get('value', '')) for value in checksums):
            raise ValueError('Every Gradle artifact needs a reviewed SHA-256 checksum')
    checksums = maven_verification.load_checksums(root / '.mvn/checksums.sha256')
    if any(identity.startswith('io.github.sjacek:shardpilot') for identity in checksums):
        raise ValueError('Locally built candidates must not be part of the external checksum baseline')
    if (root / '.mvn/maven.config').read_text(encoding='utf-8').strip() != '--strict-checksums':
        raise ValueError('Maven transport checksums must fail on mismatches')
    return {'pinnedActionReferences': references, 'gradleArtifacts': len(artifacts), 'mavenArtifacts': len(checksums)}


if __name__ == '__main__':
    print(json.dumps(verify(), sort_keys=True))

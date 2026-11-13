"""Audit a stable public release in disposable, credential-free Linux containers.

Requires Docker and a local source tag. Registry downloads/build preparation use the network;
OpenPGP verification and measured consumer executions use Docker's network-none namespace.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET

import published_artifacts

ROOT = Path(__file__).resolve().parent.parent
RUNNER = ROOT / 'testing/published-artifacts'


def run(command, **options):
    return subprocess.run(command, check=True, **options)


def tool_context(output):
    context = output / 'tool-image'
    context.mkdir(parents=True, exist_ok=True)
    distributions = [
        ('gradle.zip', 'https://services.gradle.org/distributions/gradle-9.9.1-bin.zip',
         'd8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228'),
        ('maven.zip', 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/apache-maven-3.10.0-bin.zip',
         '1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff')]
    for name, url, checksum in distributions:
        destination = context / name
        if not destination.is_file() or hashlib.sha256(destination.read_bytes()).hexdigest() != checksum:
            temporary = destination.with_suffix('.partial')
            with urllib.request.urlopen(url, timeout=120) as response, temporary.open('wb') as stream:
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > 256 * 1024 * 1024:
                        raise ValueError('Oversized build-tool archive')
                    stream.write(chunk)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != checksum:
                raise ValueError('Build-tool distribution checksum mismatch')
            temporary.replace(destination)
    shutil.copyfile(RUNNER / 'Dockerfile', context / 'Dockerfile')
    return context


def container(phase, output, image, version, fingerprint):
    evidence = output / phase
    evidence.mkdir(parents=True, exist_ok=True)
    seed = output / 'seed'
    seed.mkdir(parents=True, exist_ok=True)
    # Linux bind mounts keep host ownership. Only these disposable output directories
    # are shared with UID 10001; registry inputs remain mounted read-only.
    for directory in (evidence, seed):
        directory.chmod(0o777)
    mounts = [(RUNNER, '/runner', True), (output / 'fixtures', '/fixtures', True),
              (output, '/registry', True), (seed, '/seed', phase != 'warm'), (evidence, '/evidence', False)]
    arguments = ['docker', 'create', '--read-only', '--user', '10001:10001', '--cap-drop', 'ALL',
                 '--security-opt', 'no-new-privileges', '--network', 'bridge' if phase == 'warm' else 'none',
                 '--cpus', '2', '--memory', '3g', '--pids-limit', '512',
                 '--tmpfs', '/work:rw,exec,mode=1777,size=3g', '--tmpfs', '/tmp:rw,exec,mode=1777,size=256m',
                 '--env', 'PYTHONDONTWRITEBYTECODE=1', '--env', 'JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2',
                 '--env', 'SHARDPILOT_AUDIT_VERSION=' + version,
                 '--env', 'SHARDPILOT_AUDIT_FINGERPRINT=' + fingerprint,
                 '--label', 'io.github.sjacek.shardpilot.audit=published-' + version]
    for source, target, readonly in mounts:
        arguments += ['--mount', 'type=bind,source=' + str(source) + ',target=' + target + (',readonly' if readonly else '')]
    arguments += [image, 'python3', '-B', '/runner/audit_runner.py', phase]
    identifier = subprocess.check_output(arguments, text=True).strip()
    if not re.fullmatch('[a-f0-9]{64}', identifier):
        raise ValueError('Unexpected container identifier')
    try:
        inspected = json.loads(subprocess.check_output(['docker', 'inspect', identifier], text=True))[0]
        host = inspected['HostConfig']
        config = {'imageId': inspected['Image'], 'user': inspected['Config']['User'],
                  'environmentNames': [value.partition('=')[0] for value in inspected['Config']['Env']],
                  'network': host['NetworkMode'], 'readOnlyRoot': host['ReadonlyRootfs'],
                  'capDrop': host['CapDrop'], 'securityOptions': host['SecurityOpt'],
                  'memoryBytes': host['Memory'], 'nanoCpus': host['NanoCpus'], 'pidsLimit': host['PidsLimit'],
                  'mounts': [{'destination': mount['Destination'], 'writable': mount['RW']} for mount in inspected['Mounts']]}
        if config['network'] != ('bridge' if phase == 'warm' else 'none') or not config['readOnlyRoot'] or config['user'] != '10001:10001':
            raise ValueError('Unexpected sandbox configuration')
        (evidence / 'container-policy.json').write_text(json.dumps(config, indent=2) + '\n', encoding='utf-8')
        with (evidence / 'container.log').open('w', encoding='utf-8') as log:
            run(['docker', 'start', '--attach', identifier], stdout=log, stderr=subprocess.STDOUT, timeout=1800)
        state = json.loads(subprocess.check_output(['docker', 'inspect', identifier], text=True))[0]['State']
        if state['ExitCode'] != 0 or state['OOMKilled']:
            raise RuntimeError(f'{phase} container failed; inspect {evidence / "container.log"}')
    finally:
        # The identifier is returned by this invocation's create, never discovered from other stacks.
        run(['docker', 'rm', '--force', identifier], stdout=subprocess.DEVNULL)


def runner_module():
    """Load the same authored scenario contract without making phase metadata authoritative."""
    spec = importlib.util.spec_from_file_location('published_audit_runner', RUNNER / 'audit_runner.py')
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    return runner


def validate_phase(summary, directory, runner, version, warm):
    phase = 'warm' if warm else 'isolated'
    expected = {(item['tool'], item['framework'], cohort)
                for item in runner.SCENARIOS for cohort, _, _, _ in runner.cohorts(warm)}
    cases = summary.get('cases', [])
    actual = [(item.get('tool'), item.get('framework'), item.get('cohort')) for item in cases]
    if summary.get('executionSchemaVersion') != runner.EXECUTION_SCHEMA_VERSION \
            or summary.get('phase') != phase or summary.get('version') != version \
            or not summary.get('passed') or len(actual) != len(expected) or set(actual) != expected \
            or any(item.get('outboundAttempts') != [] for item in cases):
        raise ValueError('Incomplete framework cohort or unsupported execution evidence schema: ' + phase)
    if {tool.name for tool in directory.iterdir() if tool.is_dir()} != {item['tool'] for item in runner.SCENARIOS}:
        raise ValueError('Unexpected tool evidence directories: ' + phase)
    directories = {(tool.name, framework.name) for tool in directory.iterdir() if tool.is_dir()
                   for framework in tool.iterdir() if framework.is_dir()}
    if directories != {(item['tool'], item['framework']) for item in runner.SCENARIOS}:
        raise ValueError('Unexpected framework evidence directories: ' + phase)
    expected_cohorts = {cohort for cohort, _, _, _ in runner.cohorts(warm)}
    for item in runner.SCENARIOS:
        root = directory / item['tool'] / item['framework']
        if {path.name for path in root.iterdir() if path.is_dir()} != expected_cohorts:
            raise ValueError('Incomplete native framework execution directories: ' + str(root))


def summarize(output):
    """Re-read original evidence independently of the in-container success flag."""
    (output / 'verified-evidence.json').unlink(missing_ok=True)
    runner = runner_module()
    registry = json.loads((output / 'registry-verification.json').read_text())
    version = published_artifacts.release_version(registry['version'])
    if len(registry['artifacts']) != 15 or len({row['file'] for row in registry['artifacts']}) != 15:
        raise ValueError('Incomplete published artifact cohort')
    signature = json.loads((output / 'signatures/signatures.json').read_text())
    source = json.loads((output / 'source-tag.json').read_text())
    runner.tag_binding(source['verification']['payload'], registry['sourceCommit'], version)
    runner.signer_cohort(signature['verifiedSignatures'])
    if signature['verifiedSignatures'][0]['primaryFingerprint'] != published_artifacts.audit_policy()['publisherPrimaryFingerprint']:
        raise ValueError('Release signing identity differs from the reviewed publisher key')
    for row in registry['artifacts']:
        artifact = output / row['file'].replace('\\', '/')
        if hashlib.sha256(artifact.read_bytes()).hexdigest() != row['sha256']:
            raise ValueError('Retained registry artifact changed')
    policies = [json.loads((output / phase / 'container-policy.json').read_text()) for phase in ('signatures', 'warm', 'isolated')]
    if len({policy['imageId'] for policy in policies}) != 1 or policies[-1]['network'] != 'none':
        raise ValueError('Measured phases do not share the expected image and isolation')
    warm = json.loads((output / 'warm/summary.json').read_text())
    isolated = json.loads((output / 'isolated/summary.json').read_text())
    validate_phase(warm, output / 'warm', runner, version, True)
    validate_phase(isolated, output / 'isolated', runner, version, False)
    if not signature['passed']:
        raise ValueError('A required phase did not pass')
    positive = runner.network_attempts((output / 'isolated/positive-control.strace').read_text())
    if not any(row['address'] == '198.51.100.1' for row in positive):
        raise ValueError('Missing retained tracer positive control')
    rows = []
    comparisons = []
    for scenario in runner.SCENARIOS:
        tool, framework, expected = scenario['tool'], scenario['framework'], scenario['nativeTests']
        for cohort, _, count, _ in runner.cohorts(True):
            runner.native_events(output / 'warm' / tool / framework / cohort / 'events.tsv', scenario, expected // count)
        snapshots = {}
        for cohort, _, count, _ in runner.cohorts():
            directory = output / 'isolated' / tool / framework / cohort
            events = runner.native_events(directory / 'events.tsv', scenario, expected // count)
            totals = Counter()
            for path in directory.rglob('TEST-*.xml'):
                xml = ET.parse(path).getroot()
                totals.update({name: int(xml.get(name, '0')) for name in ('tests', 'failures', 'errors', 'skipped')})
            if totals['tests'] != expected // count or any(totals[name] for name in ('failures', 'errors', 'skipped')):
                raise ValueError('Native XML disagrees with execution evidence')
            trace = directory / 'build.strace'
            attempts = runner.network_attempts(trace.read_text())
            if attempts:
                raise ValueError('Retained network trace contains an outbound attempt')
            rows.append({'tool': tool, 'framework': framework, 'cohort': cohort, 'nativeTests': totals['tests'], 'outboundAttempts': len(attempts),
                         'eventSha256': hashlib.sha256((directory / 'events.tsv').read_bytes()).hexdigest(),
                         'traceSha256': hashlib.sha256(trace.read_bytes()).hexdigest()})
            snapshots[cohort] = events
        for count in (2, 4):
            union = Counter()
            identities = set()
            for node in range(1, count + 1):
                snapshot = snapshots[f'{count}-{node}']
                current = {row[1:4] for row in snapshot}
                if identities.intersection(current):
                    raise ValueError('Native test identity appears on multiple nodes')
                identities.update(current)
                union.update(snapshot)
            if union != snapshots['baseline']:
                raise ValueError('Retained native identity/outcome multiset differs from the reference')
            comparisons.append({'tool': tool, 'framework': framework, 'shards': count, 'nativeTests': expected, 'passed': True})
    report = {'schemaVersion': 1, 'executionSchemaVersion': runner.EXECUTION_SCHEMA_VERSION,
              'version': registry['version'], 'sourceCommit': registry['sourceCommit'],
              'verifiedAt': datetime.now(timezone.utc).isoformat(), 'registryArtifacts': len(registry['artifacts']),
              'openPgpSignatures': len(signature['verifiedSignatures']), 'primaryFingerprint': signature['verifiedSignatures'][0]['primaryFingerprint'],
              'imageId': policies[-1]['imageId'], 'cases': rows, 'comparisons': comparisons,
              'positiveControlAttempts': len(positive), 'passed': True}
    (output / 'verified-evidence.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2), flush=True)
    return report


def audit(output, image, skip_build, reuse_downloads, phases, version=None):
    output.mkdir(parents=True, exist_ok=True)
    final = output / 'audit-summary.json'
    final.unlink(missing_ok=True)
    (output / 'verified-evidence.json').unlink(missing_ok=True)
    started = datetime.now(timezone.utc).isoformat()
    policy = published_artifacts.audit_policy()
    version = published_artifacts.release_version(version or policy['defaultPublishedVersion'])
    tag_name = 'v' + version
    (output / 'audit-request.json').write_text(json.dumps({
        'version': version, 'sourceTag': tag_name, 'startedAt': started,
        'executionSchemaVersion': runner_module().EXECUTION_SCHEMA_VERSION,
        'publisherPrimaryFingerprint': policy['publisherPrimaryFingerprint'],
        'defaultPublishedVersion': policy['defaultPublishedVersion'],
        'scope': 'Already published registry bytes; not unpublished release candidates.'
    }, indent=2) + '\n', encoding='utf-8')
    run([sys.executable, '-B', '-m', 'unittest', 'discover', '-s', str(RUNNER), '-p', 'test_*.py', '-v'])
    if not reuse_downloads:
        published_artifacts.fetch(output, version)
    registry = json.loads((output / 'registry-verification.json').read_text())
    if registry['version'] != version:
        raise ValueError('Requested release differs from retained downloads')
    for entry in registry['artifacts']:
        path = output / entry['file'].replace('\\', '/')
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError('Downloaded artifact changed before the isolated run')
    reference = published_artifacts.git('rev-parse', tag_name + '^{}').decode().strip()
    if reference != registry['sourceCommit']:
        raise ValueError('Local release tag changed')
    if version == policy['defaultPublishedVersion'] and reference != policy['defaultSourceCommit']:
        raise ValueError('Default published release differs from the reviewed source commit')
    if not reuse_downloads or not (output / 'source-tag.json').is_file():
        ref_path = output / 'public-tag-ref.json'
        published_artifacts.download('https://api.github.com/repos/sjacek/shardpilot/git/ref/tags/' + tag_name, ref_path)
        ref = json.loads(ref_path.read_text())
        if ref['object']['type'] != 'tag':
            raise ValueError('Expected an annotated release tag')
        published_artifacts.download(ref['object']['url'], output / 'source-tag.json')
    tag = json.loads((output / 'source-tag.json').read_text())
    if tag['object']['sha'] != registry['sourceCommit'] or tag['tag'] != tag_name:
        raise ValueError('Registry source snapshot differs from public tag')
    run([sys.executable, '-B', str(RUNNER / 'audit_runner.py'), 'fixtures', '--destination', str(output / 'fixtures'), '--version', version])
    if not skip_build:
        context = tool_context(output)
        with (output / 'image-build.log').open('w', encoding='utf-8') as log:
            run(['docker', 'build', '--tag', image, str(context)], stdout=log, stderr=subprocess.STDOUT)
    image_id = subprocess.check_output(['docker', 'image', 'inspect', image, '--format', '{{.Id}}'], text=True).strip()
    for phase in phases:
        print('Starting audit phase: ' + phase, flush=True)
        container(phase, output, image_id, version, policy['publisherPrimaryFingerprint'])
        print('Completed audit phase: ' + phase, flush=True)
    if phases == ['signatures', 'warm', 'isolated']:
        signature = json.loads((output / 'signatures/signatures.json').read_text())
        isolated = json.loads((output / 'isolated/summary.json').read_text())
        result = {'schemaVersion': 1, 'executionSchemaVersion': runner_module().EXECUTION_SCHEMA_VERSION,
                  'version': version, 'sourceCommit': registry['sourceCommit'],
                  'startedAt': started, 'completedAt': datetime.now(timezone.utc).isoformat(),
                  'registryArtifacts': len(registry['artifacts']), 'openPgp': signature, 'isolation': isolated,
                  'limits': ['GitHub public keys/tag and registry TLS are the observed identity anchors, not an out-of-band identity check.',
                             'Plugin Portal lacks detached signatures/sidecar SHA-256/SHA-512 for these files; module digests and source comparison are separate evidence.',
                             'No reproducible binary-build claim. No claim about every plugin path or arbitrary consumers.',
                             'Syscall traces cover these Linux JVM processes and descendants; baseline comparisons do not prove Java-level attribution.'],
                  'passed': signature['passed'] and isolated['passed']}
        summarize(output)
        final.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        print(str(final), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/published-artifact-audit')
    parser.add_argument('--version', help='Already published stable version; defaults to the reviewed policy baseline')
    parser.add_argument('--image', default='shardpilot-artifact-audit:20261112')
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--reuse-downloads', action='store_true')
    parser.add_argument('--phase', choices=('signatures', 'warm', 'isolated'), action='append')
    parser.add_argument('--summarize-only', action='store_true')
    args = parser.parse_args()
    if args.summarize_only:
        summarize(args.output.resolve())
    else:
        audit(args.output.resolve(), args.image, args.skip_build, args.reuse_downloads, args.phase or ['signatures', 'warm', 'isolated'], args.version)

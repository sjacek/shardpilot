"""Executable development example: plugin planning, two native workers and artifact-only C2/C3.

This CI harness records observed subprocess outcomes and copies original XML. It contains no
test-selection, impact or sharding algorithm. A real CI integration supplies its own trusted
project/run/job/revision values and downloads; these local synthetic job IDs prove no CI identity.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SUITE = 'profile-smoke-testdata'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def now():
    return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')


def observe(command, directory, log, environment=None):
    """Observe the real process, preserving failures independently of later evidence validation."""
    started = now()
    with log.open('wb') as stream:
        result = subprocess.run(command, cwd=directory, env=environment, stdout=stream, stderr=subprocess.STDOUT, timeout=600)
    return {'state': 'completed', 'exitCode': result.returncode, 'startedAt': started, 'finishedAt': now()}


def retain_node(evidence, index, plan, reports, process, cohort, selection):
    """Read native counters only; this simple fixture has unique ordinary test identities.

    Parameterized/repeated engines require their native invocation ledger as documented in C2.
    Neither XML row positions nor planned classes are used to invent execution identities/counts.
    """
    node = evidence / 'nodes' / ('node-' + str(index)); node.mkdir(parents=True)
    shutil.copyfile(plan, node / 'plan.yaml')
    retained, counts = [], dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    (node / 'reports').mkdir()
    for report in sorted(reports.glob('TEST-*.xml')):
        data = report.read_bytes()
        if len(data) > 8_388_608 or b'<!DOCTYPE' in data or b'<!ENTITY' in data:
            raise ValueError('Invalid native fixture report')
        xml = ET.fromstring(data)
        for name in counts:
            counts[name] += int(xml.get(name, '0'))
        target = node / 'reports' / report.name; shutil.copyfile(report, target)
        retained.append({'path': 'reports/' + report.name, 'sha256': sha(target), 'bytes': target.stat().st_size})
    write(node / 'evidence.json', {'schemaVersion': 1, 'cohort': cohort,
          'producer': {'jobId': 'local-node-' + str(index), 'toolVersion': 'development-testdata', 'adapterVersion': 'development'},
          'shard': {'index': index, 'count': 2}, 'selection': {**selection, 'planSha256': sha(plan)},
          'process': process, 'reports': retained, 'observed': counts})
    return {'index': index, 'jobId': 'local-node-' + str(index), 'planSha256': sha(plan),
            'jobStartedAt': process['startedAt'], 'jobFinishedAt': process['finishedAt']}


def run(tool, output, command, corrupt=None, repository=None):
    """Use a fresh disposable consumer; never clean or repair retained artifacts during verification."""
    output = output.resolve()
    if output.exists():
        raise ValueError('Example output must be fresh')
    example = ROOT / ('examples/gradle/junit-jupiter/selection-workflow' if tool == 'gradle' else 'examples/maven/selection-workflow')
    shutil.copytree(example, output)
    evidence = output / 'evidence'; evidence.mkdir()
    # This is a separately obtained trusted source revision, not read from downloaded C3/C5 metadata.
    revision = subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip()
    environment = None
    if tool == 'gradle':
        (output / 'gradle').mkdir(exist_ok=True)
        shutil.copyfile(ROOT / 'gradle/verification-metadata.xml', output / 'gradle/verification-metadata.xml')
        # Preserve the checked-in plugin repositories; a plugin-only Portal cache is not warmed.
        common = ['-p', str(output), '--offline', '--console=plain', '--max-workers=1',
                  '-Pexample.pluginSourceDirectory=' + str(ROOT), '-Pshardpilot.count=2', '-Pshardpilot.executionRevision=' + revision]
        snapshot = output / 'build/shardpilot/test/selections' / SUITE / 'selection'
        plans = output / 'build/shardpilot/test/selections' / SUITE / 'plan'
        compile_goals = ['testClasses', 'prepareTestShardManifests']
        seal_goals = ['sealTestCompiledArtifacts']
        frozen = ['-Pshardpilot.selectionSnapshotDirectory=' + str(snapshot)]
        bridge = ['prepareTestExecutionSelection', *frozen]
        workers = lambda index: ['test', '-Pprecompiled', *frozen, '-Pshardpilot.index=' + str(index)]
        aggregate = ['verifyTestExecutionEvidence']
        reports = lambda index: output / 'build/native-results' / str(index)
    else:
        import maven_verification
        # Extend the reviewed third-party policy with exactly the five current local reactor
        # candidate files, using the existing consumer verifier. No group-wide trust exception.
        verified = dict(os.environ) if os.environ.get('SHARDPILOT_MAVEN_VERIFICATION') == 'enforce' else maven_verification.environment(False)
        environment = maven_verification.consumer_environment(repository or Path.home() / '.m2/repository', '0.5.0', verified)
        common = ['-f', str(output / 'pom.xml'), '-Dshardpilot.count=2', '-Dshardpilot.executionRevision=' + revision]
        if repository:
            common.append('-Dmaven.repo.local=' + str(repository.resolve()))
        snapshot = output / 'target/pilot/selections' / SUITE / 'selection'
        plans = output / 'target/pilot/selections' / SUITE / 'plan'
        compile_goals = ['test-compile', 'shardpilot:prepare']
        seal_goals = ['shardpilot:seal-compiled-artifacts']
        frozen = ['-Dshardpilot.selectionSnapshotDirectory=' + str(snapshot)]
        bridge = ['shardpilot:prepare-execution-selection', *frozen, '-Dshardpilot.selectionPolicyFile=' + str(output / 'policy.yaml'),
                  '-Dshardpilot.selectionProfile=smoke-testdata', '-Dshardpilot.executionEligibilityFile=' + str(evidence / 'eligibility.yaml'),
                  '-Dshardpilot.executionHistoryFile=' + str(output / 'durations.tsv'), '-Dshardpilot.executionSelectionFile=' + str(evidence / 'selection.json')]
        workers = lambda index: ['-o', 'shardpilot:verify-compiled-artifacts', 'shardpilot:select', 'surefire:test', *frozen, '-Dshardpilot.index=' + str(index)]
        aggregate = ['-o', 'shardpilot:verify-executions', '-Dshardpilot.executionExpectedRequest=' + str(evidence / 'expected.json'),
                     '-Dshardpilot.executionEligibilityFile=' + str(evidence / 'eligibility.yaml'), '-Dshardpilot.executionSelectionFile=' + str(evidence / 'selection.json'),
                     '-Dshardpilot.executionNodesDirectory=' + str(evidence / 'nodes'), '-Dshardpilot.executionReportFile=' + str(evidence / 'report.json'),
                     '-Dshardpilot.executionHtmlReportFile=' + str(evidence / 'report.html')]
        reports = lambda index: output / 'target/pilot/selections' / SUITE / 'test-results' / str(index)
    def invoke(name, goals):
        return observe([*command, *common, *goals], ROOT, output / (name + '.log'), environment)
    for name, goals in [('planning', compile_goals), ('sealing', seal_goals)]:
        if invoke(name, goals)['exitCode']:
            raise RuntimeError('Example failed; inspect ' + str(output / (name + '.log')))
    # A trusted producer retains this pin separately; workers must not derive it from a download.
    common.append(('-P' if tool == 'gradle' else '-D') + 'workflow.compiledPin=' + sha(evidence / 'compiled.json'))
    shutil.copyfile(snapshot / 'decision/eligible.yaml', evidence / 'eligibility.yaml')
    retained_snapshot = evidence / 'frozen-selection'
    shutil.copytree(snapshot, retained_snapshot)
    # Runtime selection outputs must be disjoint from their immutable planning input.
    bridge = [arg.replace(str(snapshot), str(retained_snapshot)) for arg in bridge]
    frozen[:] = [arg.replace(str(snapshot), str(retained_snapshot)) for arg in frozen]
    snapshot = retained_snapshot
    if invoke('binding', bridge)['exitCode']:
        raise RuntimeError('Execution-selection binding failed; inspect binding.log')
    frozen_hashes = {p.relative_to(snapshot).as_posix(): sha(p) for p in snapshot.rglob('*') if p.is_file()}
    if corrupt == 'bytes':
        compiled = next((output / ('build/classes/java/test' if tool == 'gradle' else 'target/test-classes')).rglob('PaymentTest.class'))
        with compiled.open('ab') as stream:
            stream.write(b'corrupt-testdata')
    elif corrupt == 'revision':
        common = [arg.replace(revision, ('b' if revision[0] != 'b' else 'c') * 40) for arg in common]
    selection = {'mode': 'explicit', 'sha256': sha(evidence / 'selection.json'), 'eligibilitySha256': sha(evidence / 'eligibility.yaml')}
    cohort = {'revision': revision, 'runId': 'local-workflow-testdata', 'attempt': 1, 'project': 'workflow-example',
              'suite': SUITE, 'tool': tool, 'runner': 'gradle-test' if tool == 'gradle' else 'surefire'}
    expected_nodes, processes = [], []
    for index in (1, 2):
        process = invoke('worker-' + str(index), workers(index)); processes.append(process)
        expected_nodes.append(retain_node(evidence, index, plans / ('planned-' + str(index) + '.yaml'), reports(index), process, cohort, selection))
    write(evidence / 'expected.json', {'schemaVersion': 1, 'cohort': cohort, 'selection': selection,
          'shardCount': 2, 'expectedNodes': expected_nodes, 'skipPolicy': 'reject', 'allowEmptyShards': False,
          'minimumExecutedTests': 2, 'maximumSkippedTests': 0})
    aggregate_process = invoke('aggregate', aggregate)
    if frozen_hashes != {p.relative_to(snapshot).as_posix(): sha(p) for p in snapshot.rglob('*') if p.is_file()}:
        raise AssertionError('Worker/aggregation modified the frozen source snapshot')
    report = json.loads((evidence / 'report.json').read_text())
    rejected = bool(corrupt)
    if rejected:
        if any(p['exitCode'] == 0 for p in processes) or aggregate_process['exitCode'] == 0:
            raise AssertionError('Invalid artifacts were accepted')
        if any(list(reports(i).glob('TEST-*.xml')) for i in (1, 2)):
            raise AssertionError('A native worker started with invalid compiled inputs')
        expected_reason = 'FILE_CONTENT_MISMATCH' if corrupt == 'bytes' else 'REVISION_MISMATCH'
        if any(expected_reason not in (output / ('worker-' + str(i) + '.log')).read_text(errors='replace') for i in (1, 2)):
            raise AssertionError('Worker failed for an unrelated reason')
    elif any(p['exitCode'] for p in processes) or aggregate_process['exitCode'] or report['status'] != 'PASSED':
        raise AssertionError('Native execution/evidence verification failed; inspect retained logs/report')
    else:
        decision = json.loads((snapshot / 'decision/decision.json').read_text())
        if decision['eligibleClasses'] != 3 or decision['selectedClasses'] != 2 or report['totals']['executed'] != 2:
            raise AssertionError('Example no longer demonstrates a two-of-three native selection')
    write(output / 'workflow.json', {'status': 'EXPECTED_REJECTION' if rejected else 'PASSED', 'tool': tool,
          'revision': revision, 'nativeProcesses': processes, 'aggregateProcess': aggregate_process, 'corruption': corrupt,
          'frozenSnapshotUnchanged': True, 'localSyntheticJobMetadata': True, 'benchmark': False})
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tool', choices=['gradle', 'maven'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--command-json', required=True, help='JSON argument array for the reviewed build-tool launcher')
    parser.add_argument('--corrupt', choices=['bytes', 'revision'])
    parser.add_argument('--repository', type=Path, help='Verified local Maven candidate repository')
    args = parser.parse_args()
    run(args.tool, args.output, json.loads(args.command_json), args.corrupt, args.repository)
    print('Verified ' + args.tool + ' selection workflow')


if __name__ == '__main__':
    main()

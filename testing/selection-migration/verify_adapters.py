"""Rebind one accepted frozen comparison through both development adapters, without native tests."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import maven_verification
from run_selection_workflow import observe, sha, write


def verify(case, output, gradle, maven, repository):
    case, output = case.resolve(), output.resolve()
    if output.exists() or case == output or case in output.parents:
        raise ValueError('Adapter verification requires fresh, separate output')
    decision = json.loads((case / 'native/decision/decision.json').read_text())
    if decision['status'] != 'PASSED':
        raise ValueError('Only an accepted frozen planning snapshot can be rebound')
    revision = json.loads((case / 'git.json').read_text())['headRevision']
    fixture = ROOT / 'testing/selection-migration/adapters'
    for tool in ['gradle', 'maven']:
        shutil.copytree(fixture / tool, output / tool)
    (output / 'gradle/gradle').mkdir()
    shutil.copyfile(ROOT / 'gradle/verification-metadata.xml', output / 'gradle/gradle/verification-metadata.xml')
    properties = {'selectionProfile': 'daily-testdata', 'selectionSuite': 'comparison-testdata', 'count': '2',
                  'executionRevision': revision, 'selectionSnapshotDirectory': str(case / 'native/snapshot'),
                  'selectionPolicyFile': str(case / 'policy.yaml'), 'executionEligibilityFile': str(case / 'eligibility.yaml'),
                  'executionHistoryFile': str(case / 'history.tsv')}
    commands = {'gradle': [*gradle, '-p', str(output / 'gradle'), '--offline', '--console=plain',
                           '-Pexample.pluginSourceDirectory=' + str(ROOT), 'prepareSelection'],
                'maven': [*maven, '-f', str(output / 'maven/pom.xml'), '-o', 'shardpilot:prepare-execution-selection',
                          '-Dmaven.repo.local=' + str(repository.resolve())]}
    verified = dict(os.environ) if os.environ.get('SHARDPILOT_MAVEN_VERIFICATION') == 'enforce' else maven_verification.environment(False)
    environment = maven_verification.consumer_environment(repository, '0.6.0', verified)
    for tool in ['gradle', 'maven']:
        prefix = '-P' if tool == 'gradle' else '-D'
        arguments = [prefix + 'shardpilot.' + name + '=' + value for name, value in properties.items()]
        output_parameter = 'comparison.output' if tool == 'gradle' else 'shardpilot.executionSelectionFile'
        arguments.append(prefix + output_parameter + '=' + str(output / (tool + '-selection.json')))
        process = observe(commands[tool] + arguments, ROOT, output / (tool + '.log'), environment if tool == 'maven' else None)
        if process['exitCode']:
            raise AssertionError('Adapter failed; inspect ' + str(output / (tool + '.log')))
    if (output / 'gradle-selection.json').read_bytes() != (output / 'maven-selection.json').read_bytes():
        raise AssertionError('Adapters produced different canonical execution-selection bytes')
    result = {'status': 'PASSED', 'selectionSha256': sha(output / 'gradle-selection.json'),
              'decisionIdentity': decision['identity'], 'nativeTestsRun': False, 'benchmark': False}
    write(output / 'parity.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--gradle-command-json', required=True)
    parser.add_argument('--maven-command-json', required=True)
    parser.add_argument('--repository', type=Path, required=True)
    args = parser.parse_args()
    verify(args.case, args.output, json.loads(args.gradle_command_json), json.loads(args.maven_command_json), args.repository)
    print('Verified byte-identical adapter selection evidence')


if __name__ == '__main__':
    main()

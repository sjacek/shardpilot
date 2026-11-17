"""Exercise a consumer-maintained policy against its verified full CI baseline.

This is offline acceptance, not a Git-impact algorithm. The actual Java core
classifies every tracked path. All observations and class names remain private.
The historical revision is checked before using its source layout as evidence.
"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess

from collect_ci_baseline import classes, document, sha256


def runtime_inventory(classpath):
    """Bind acceptance to actual tool bytes, including unpublished development classes."""
    inventory = []
    for entry in classpath.split(os.pathsep):
        path = Path(entry)
        if path.is_symlink() or not path.exists():
            raise ValueError('Missing or linked acceptance runtime')
        if path.is_file():
            inventory.append({'name': path.name, 'sha256': sha256(path)})
        else:
            records = []
            for file in sorted(path.rglob('*')):
                if file.is_symlink():
                    raise ValueError('Linked acceptance runtime entry')
                if file.is_file():
                    records.append(file.relative_to(path).as_posix() + '\t' + sha256(file))
                if len(records) > 100_000:
                    raise ValueError('Acceptance runtime entry limit')
            if not records:
                raise ValueError('Empty acceptance runtime')
            inventory.append({'name': path.name, 'sha256': hashlib.sha256('\n'.join(records).encode()).hexdigest()})
    if not inventory:
        raise ValueError('Missing acceptance runtime')
    return inventory


def verify(checkout, capture, policy, classpath, java, output):
    checkout = checkout.resolve()
    report = document((capture / 'report.json').read_bytes())
    head = subprocess.check_output(['git', '-C', str(checkout), 'rev-parse', 'HEAD'], text=True).strip()
    if report['status'] != 'passed' or head != report['revision']:
        raise ValueError('Checkout differs from captured CI revision')
    if sha256(capture / 'baseline.yaml') != report['baselineSha256'] or sha256(capture / 'history.tsv') != report['historySha256']:
        raise ValueError('Verified baseline or history changed')
    if output.exists():
        raise ValueError('Use a fresh private policy output')
    policy_digest = sha256(policy)
    runtime = runtime_inventory(classpath)
    output.mkdir(parents=True)
    eligible = classes((capture / 'baseline.yaml').read_bytes())
    paths = subprocess.check_output(['git', '-C', str(checkout), 'ls-files', '-z']).decode('utf-8').split('\0')
    paths = sorted(p for p in paths if p)
    cases = []
    # A singleton sweep tests future edits to every currently tracked file; it is
    # explicitly not presented as a real MR diff or an observed test execution.
    direct = {}
    for index, path in enumerate(paths):
        case = f'path-{index:06d}'
        cases.append((case, 'daily-testdata', path))
        for root in ('src/integrationTest/java/', 'src/integrationTest/groovy/'):
            if path.startswith(root) and path.endswith(('.java', '.groovy')):
                name = path[len(root):].rsplit('.', 1)[0].replace('/', '.')
                if name in eligible:
                    direct[case] = name
    cases.extend([('full-testdata', 'full-testdata'), ('smoke-testdata', 'smoke-testdata'),
                  ('empty-diff-testdata', 'daily-testdata'),
                  ('future-domain-testdata', 'daily-testdata', 'src/main/java/newdomain/Future.java'),
                  ('future-module-testdata', 'daily-testdata', 'modules/future/src/main/java/New.java'),
                  ('policy-change-testdata', 'daily-testdata', 'gradle/shardpilot/selection-policy.yaml')])
    for format, candidates in [('json', [p for p in paths if p.startswith('src/main/resources/') and p.endswith('.json')]),
                               ('yaml', [p for p in paths if p.startswith('src/main/openapi/') and p.endswith(('.yaml', '.yml'))])]:
        if candidates:
            cases.append((f'contract-{format}-testdata', 'daily-testdata', candidates[0]))
    # All changed eligible test sources must remain protected even when they
    # exceed the short profile budget. Rejection is acceptable; partial success isn't.
    changed = [row[2] for row in cases if row[0] in direct]
    if not changed:
        raise ValueError('No changed-test source mapping in this cohort')
    cases.append(('all-tests-changed-testdata', 'daily-testdata', *changed))
    matrix = output / 'cases.tsv'
    matrix.write_text(''.join('\t'.join(row) + '\n' for row in cases), encoding='utf-8')
    command = [java, '-cp', classpath, 'io.github.sjacek.shardpilot.testing.ConsumerBaselineProbe', 'policy',
               str(capture / 'baseline.yaml'), str(policy), str(capture / 'history.tsv'), head, str(matrix), str(output / 'core')]
    # Bounded child lifetime, with diagnostics kept privately rather than embedding application data in errors.
    with (output / 'core.log').open('wb') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=600, check=False)
    if result.returncode:
        raise ValueError('Core policy acceptance failed; inspect the private core.log')
    if runtime_inventory(classpath) != runtime:
        raise ValueError('Acceptance runtime changed during evaluation')
    if (sha256(policy) != policy_digest or sha256(capture / 'baseline.yaml') != report['baselineSha256']
            or sha256(capture / 'history.tsv') != report['historySha256']):
        raise ValueError('Acceptance input changed during evaluation')
    with (output / 'core/decisions.tsv').open(encoding='utf-8', newline='') as stream:
        rows = {row['id']: row for row in csv.DictReader(stream, delimiter='\t')}
    if len(rows) != len(cases):
        raise ValueError('Incomplete policy case sweep')
    for case, row in rows.items():
        selected = set(row['classes'].split(',')) if row['classes'] else set()
        reasons = set(row['reasons'].split(','))
        if not selected <= eligible or 'INVALID_TARGET' in reasons or 'RESOURCE_LIMIT' in reasons:
            raise ValueError('Policy contains foreign targets or exceeds evaluation limits')
        if row['status'] == 'FAILED' and reasons != {'PROTECTED_BUDGET_EXCEEDED'}:
            raise ValueError('Unexpected policy failure')
        if row['status'] == 'FAILED' and selected:
            raise ValueError('Failed planning exposed an executable subset')
        if case in direct:
            changed_classes = set(row['changedClasses'].split(','))
            if direct[case] not in changed_classes or row['status'] == 'PASSED' and direct[case] not in selected:
                raise ValueError('Changed eligible test was not protected')
        if row['kind'] in ('FULL', 'FULL_FALLBACK') and selected != eligible:
            raise ValueError('Full selection omitted eligible tests')
    for case in ('future-domain-testdata', 'future-module-testdata', 'policy-change-testdata'):
        if rows[case]['kind'] != 'FULL_FALLBACK' or rows[case]['status'] != 'PASSED':
            raise ValueError('Unknown ownership must request full coverage')
    for case in rows:
        if case.startswith('contract-') and (rows[case]['status'] != 'PASSED' or rows[case]['kind'] != 'SUBSET'):
            raise ValueError('Known whole-document contract change did not use the configured probes')
    if any('UNMATCHED_CHANGE' in row['reasons'] for case, row in rows.items() if case.startswith('path-')):
        raise ValueError('A tracked path has no reviewed ownership rule')
    if rows['full-testdata']['kind'] != 'FULL' or rows['all-tests-changed-testdata']['status'] != 'FAILED':
        raise ValueError('Full profile or protected-overflow gate changed')
    summary = {'schemaVersion': 1, 'status': 'passed', 'revision': head, 'policySha256': policy_digest,
               'baselineSha256': report['baselineSha256'], 'historySha256': report['historySha256'],
               'classes': len(eligible), 'trackedPaths': len(paths), 'changedTestSources': len(direct), 'cases': len(rows),
               'subsetPaths': sum(row['kind'] == 'SUBSET' and row['status'] == 'PASSED' for key, row in rows.items() if key.startswith('path-')),
               'fullFallbackPaths': sum(row['kind'] == 'FULL_FALLBACK' for key, row in rows.items() if key.startswith('path-')),
               'protectedOverflowPaths': sum(row['status'] == 'FAILED' for key, row in rows.items() if key.startswith('path-')),
               'caseMatrixSha256': sha256(matrix), 'decisionsSha256': sha256(output / 'core/decisions.tsv'),
               'runtime': runtime,
               'limitations': ['Singleton planning sweep, not execution or a performance measurement.',
                               'Structured contract reads unavailable in a path-only case use the full fallback.']}
    (output / 'report.json').write_text(json.dumps(summary, indent=2) + '\n')
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('checkout', 'capture', 'policy', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    print(json.dumps(verify(args.checkout, args.capture, args.policy, args.classpath, args.java, args.output)))

"""Replay trusted consumer daily selectors and ShardPilot on identical, hash-pinned inputs.

The caller explicitly trusts the Python selector being snapshotted. This tool executes it;
hashing is integrity evidence, not a sandbox or a security review. No service tests run.
Application-specific inputs and copied source stay in the fresh private output directory.
"""
import argparse
import hashlib
import html
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
LIMIT = 32 * 1024 * 1024


def digest(path):
    """Bound reads of supplied evidence; do not follow links into another input tree."""
    if path.is_symlink() or not path.is_file() or path.stat().st_size > LIMIT:
        raise ValueError('Invalid or oversized comparison input')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n', encoding='utf-8')


def load_legacy(directory):
    """Import only the frozen, explicitly trusted script and its frozen timing helper."""
    name = '_comparison_legacy'
    helper = 'integration_test_durations'
    previous = sys.modules.pop(helper, None)
    sys.path.insert(0, str(directory))
    try:
        spec = importlib.util.spec_from_file_location(name, directory / 'integration_short.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        sys.path.pop(0)
        sys.modules.pop(helper, None)
        if previous is not None:
            sys.modules[helper] = previous


def names(path):
    return {line.strip() for line in path.read_text(encoding='utf-8').splitlines()
            if line.strip() and not line.startswith('#')}


def history(path):
    result = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        name, value = line.split('\t')
        seconds = float(value)
        if name in result or not math.isfinite(seconds) or seconds < 0:
            raise ValueError('Invalid timing input')
        result[name] = seconds
    return result


def freeze(consumer, policy, scenarios, output, base, head):
    """Capture bytes once, including the actual compiled name inventory, without building a service.

    Inventory is not engine discovery: abstract/disabled classes and stale local bytecode may occur.
    Keep that limitation explicit instead of asserting native full-suite acceptance.
    """
    consumer, output = consumer.resolve(), output.resolve()
    if output.exists() or consumer == output or consumer in output.parents:
        raise ValueError('Comparison requires a fresh output outside the consumer')
    def git(*arguments):
        return subprocess.check_output(['git', '-C', str(consumer), *arguments], stderr=subprocess.PIPE)
    revisions = [git('rev-parse', '--verify', '--end-of-options', ref + '^{commit}').decode().strip()
                 for ref in (base, head)]
    changed = [part.decode('utf-8') for part in git('diff', '--no-renames', '--name-only', '-z', *revisions, '--').split(b'\0') if part]
    eligible, compiled = set(), []
    for language in ('java', 'groovy'):
        root = consumer / f'build/classes/{language}/integrationTest'
        for file in sorted(root.rglob('*IT.class')):
            if '$' not in file.name:
                eligible.add('.'.join(file.relative_to(root).with_suffix('').parts))
                compiled.append({'path': file.relative_to(consumer).as_posix(), 'sha256': digest(file)})
    if not eligible:
        raise ValueError('No existing compiled classes; comparison never invokes compilation')
    output.mkdir(parents=True)
    legacy = output / 'legacy'; legacy.mkdir()
    for filename in ('integration_short.py', 'integration_test_durations.py', 'integration-smoke-classes.txt'):
        (legacy / filename).write_bytes((consumer / 'scripts/ci' / filename).read_bytes())
    module = load_legacy(legacy)
    eligible = {name for name in eligible if name.rsplit('.', 1)[-1] not in module.DEDICATED}
    (output / 'eligibility.yaml').write_text('schemaVersion: 1\nclasses:\n' + ''.join('  - ' + n + '\n' for n in sorted(eligible)), encoding='utf-8')
    (output / 'history.tsv').write_bytes((consumer / 'scripts/ci/integration-test-durations.tsv').read_bytes())
    (output / 'policy.yaml').write_bytes(policy.read_bytes())
    cases = json.loads(scenarios.read_text(encoding='utf-8'))
    # Retain the real complete two-commit diff alongside explicitly synthetic boundary scenarios.
    cases.insert(0, {'id': 'actual-diff-testdata', 'origin': 'committed-diff', 'paths': changed})
    write_json(output / 'scenarios.json', cases)
    write_json(output / 'compiled-inventory.json', compiled)
    source = {'schemaVersion': 1, 'consumerRevision': git('rev-parse', 'HEAD').decode().strip(),
              'baseRevision': revisions[0], 'headRevision': revisions[1],
              'eligibilityKind': 'compiled-name-inventory-not-native-discovery',
              'consumerTrackedChanges': bool(git('status', '--porcelain', '--untracked-files=no')),
              'benchmark': False}
    source['files'] = {file.relative_to(output).as_posix(): digest(file) for file in sorted(output.rglob('*')) if file.is_file()}
    write_json(output / 'inputs.json', source)
    return source


def verify_snapshot(snapshot):
    source = json.loads((snapshot / 'inputs.json').read_text(encoding='utf-8'))
    for name, expected in source['files'].items():
        relative = Path(name)
        if relative.is_absolute() or '..' in relative.parts or '\\' in name or ':' in name:
            raise ValueError('Unsafe comparison path')
        if any(part.is_symlink() for part in [snapshot / relative, *(snapshot / relative).parents]):
            raise ValueError('Aliased comparison input')
        if digest(snapshot / relative) != expected:
            raise ValueError('Frozen comparison input changed')
    return source


def explain(legacy, decision, expected):
    """A difference passes only after exact sets/statuses and a nonempty explanation are reviewed."""
    old = set(legacy.get('classes', {}))
    new = {member for family in decision['families'] if family['disposition'] == 'SELECTED' for member in family['members']}
    difference = {'legacyOnly': sorted(old - new), 'shardPilotOnly': sorted(new - old),
                  'legacyStatus': legacy['status'], 'shardPilotStatus': decision['status']}
    if old == new and legacy['status'] == decision['status']:
        return {**difference, 'status': 'MATCH', 'explanation': 'Same accepted class set and planning status.'}
    required = {'legacyOnly', 'shardPilotOnly', 'legacyStatus', 'shardPilotStatus', 'explanation'}
    if expected is not None and set(expected) == required and expected['explanation'].strip() and all(
            expected[key] == value for key, value in difference.items()):
        return {**difference, 'status': 'EXPLAINED', 'explanation': expected['explanation']}
    return {**difference, 'status': 'REVIEW_REQUIRED', 'explanation': 'No exact reviewed expectation for this difference.'}


def compare(snapshot, output, classpath, java='java', expectations=None):
    source = verify_snapshot(snapshot)
    if output.exists() or snapshot.resolve() in output.resolve().parents:
        raise ValueError('Replay requires a fresh output outside frozen inputs')
    output.mkdir(parents=True)
    module = load_legacy(snapshot / 'legacy')
    # YAML baseline contains only the simple binary names emitted by freeze(), not arbitrary YAML.
    eligible = {line[4:] for line in (snapshot / 'eligibility.yaml').read_text().splitlines() if line.startswith('  - ')}
    core = names(snapshot / 'legacy/integration-smoke-classes.txt')
    timings = history(snapshot / 'history.tsv')
    cases = json.loads((snapshot / 'scenarios.json').read_text())
    reviewed = json.loads(expectations.read_text()) if expectations else {}
    results = []
    for case in cases:
        identifier = case['id']
        if not re.fullmatch(r'[a-z0-9-]+-testdata', identifier):
            raise ValueError('Unsafe scenario identifier')
        directory = output / identifier; directory.mkdir()
        for name in ('eligibility.yaml', 'policy.yaml', 'history.tsv'):
            (directory / name).write_bytes((snapshot / name).read_bytes())
        paths = sorted(set(case['paths']))
        git = {'schemaVersion': 1, 'source': 'EXPLICIT', 'failure': 'NONE', 'availability': 'KNOWN',
               'baseRevision': source['baseRevision'], 'headRevision': source['headRevision'], 'paths': paths}
        write_json(directory / 'git.json', git)
        try:
            legacy = {'status': 'PASSED', **module.select(eligible, paths, core, timings)}
        except ValueError:
            # Arbitrary legacy exception text may contain private paths; retain only a stable outcome.
            legacy = {'status': 'FAILED', 'classes': {}, 'reason': 'LEGACY_REJECTED'}
        write_json(directory / 'legacy.json', legacy)
        log = directory / 'core.log'
        with log.open('wb') as stream:
            subprocess.run([java, '-cp', classpath, 'io.github.sjacek.shardpilot.testing.SelectionComparisonProbe',
                            str(directory.resolve()), str((directory / 'native').resolve())],
                           stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=60)
        decision = json.loads((directory / 'native/decision/decision.json').read_text())
        result = {'id': identifier, 'origin': case.get('origin', 'synthetic-boundary'),
                  'pathsSha256': hashlib.sha256(json.dumps(paths, ensure_ascii=False).encode()).hexdigest(),
                  **explain(legacy, decision, reviewed.get(identifier))}
        results.append(result)
    summary = {'schemaVersion': 1, 'status': 'REVIEW_REQUIRED' if any(r['status'] == 'REVIEW_REQUIRED' for r in results) else 'PASSED',
               'snapshotManifestSha256': digest(snapshot / 'inputs.json'),
               'source': source, 'classpathArtifacts': [{'name': Path(p).name, 'sha256': digest(Path(p))} for p in classpath.split(os.pathsep) if Path(p).is_file()],
               'scenarios': results, 'provesNativeExecution': False, 'benchmark': False}
    write_json(output / 'comparison.json', summary)
    rows = ''.join('<tr><td>' + html.escape(r['id']) + '</td><td>' + r['status'] + '</td><td>' + html.escape(r['explanation']) + '</td></tr>' for r in results)
    (output / 'comparison.html').write_text('<!doctype html><html lang="en"><meta charset="utf-8"><title>Selector comparison</title>'
        '<h1>Selector comparison</h1><p>Planning only; not native discovery, execution or a benchmark.</p><table><tr><th>Scenario</th><th>Result</th><th>Explanation</th></tr>' + rows + '</table></html>', encoding='utf-8')
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    capture = sub.add_parser('freeze')
    for name in ('consumer', 'policy', 'scenarios', 'output'):
        capture.add_argument('--' + name, type=Path, required=True)
    capture.add_argument('--base', required=True); capture.add_argument('--head', default='HEAD')
    replay = sub.add_parser('compare')
    for name in ('snapshot', 'output'):
        replay.add_argument('--' + name, type=Path, required=True)
    replay.add_argument('--classpath', required=True); replay.add_argument('--java', default='java')
    replay.add_argument('--expectations', type=Path)
    arguments = vars(parser.parse_args()); command = arguments.pop('command')
    result = freeze(**arguments) if command == 'freeze' else compare(**arguments)
    print('Captured frozen inputs' if command == 'freeze' else result['status'])
    if result.get('status') == 'REVIEW_REQUIRED':
        raise SystemExit(1)


if __name__ == '__main__':
    main()

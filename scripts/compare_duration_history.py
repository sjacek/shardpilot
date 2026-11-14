"""Compare an existing timing script with a checksum-pinned ShardPilot core on frozen inputs.

This is an optional offline migration tool, not a test runner or an adoption gate. It never
changes a consumer's maintained history. Raw inputs stay in the chosen private output directory.
"""
import argparse
import base64
from collections import Counter
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PROBE = ROOT / 'testing/java/io/github/sjacek/shardpilot/testing/HistoryRefreshProbe.java'
SOURCE_SUFFIXES = {'.java', '.groovy'}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def core_version(path):
    """The supplied checksum covers the embedded Maven identity as well as class bytes."""
    with zipfile.ZipFile(path) as archive:
        text = archive.read('META-INF/maven/io.github.sjacek/shardpilot-core/pom.properties').decode('utf-8')
    identity = dict(line.split('=', 1) for line in text.splitlines() if line and not line.startswith('#'))
    if identity.get('groupId') != 'io.github.sjacek' or identity.get('artifactId') != 'shardpilot-core' \
            or not re.fullmatch(r'(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)', identity.get('version', '')):
        raise ValueError('A stable published ShardPilot core JAR is required')
    return identity['version']


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + '\n', encoding='utf-8')


def records(path):
    """Read the produced TSV, independently of either implementation's summary counts."""
    result = {}
    if not path.is_file():
        return result
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        name, value = line.split('\t')
        seconds = Decimal(value)
        if name in result or not seconds.is_finite() or seconds < 0:
            raise ValueError('Invalid comparison TSV')
        result[name] = seconds
    return result


def source_inventory(sources):
    """Freeze availability and provenance, not private source contents or inferred test eligibility."""
    if not sources.is_dir() or sources.is_symlink():
        raise ValueError('A regular source root is required')
    entries = []
    for language in sorted(sources.iterdir()):
        if language.is_symlink():
            raise ValueError('Source symlinks are not supported')
        if not language.is_dir():
            continue
        for path in sorted(language.rglob('*')):
            if path.suffix not in SOURCE_SUFFIXES or not path.is_file():
                continue
            if path.is_symlink() or any(parent.is_symlink() for parent in path.parents if parent != sources.parent):
                raise ValueError('Source symlinks are not supported')
            if path.stem in ('package-info', 'module-info'):
                continue
            entries.append({'path': path.relative_to(sources).as_posix(), 'sha256': digest(path),
                            'class': '.'.join(path.relative_to(language).with_suffix('').parts)})
    if not entries:
        raise ValueError('Source inventory is empty')
    return entries


def snapshot_inputs(output, script, sources, previous, report_roots):
    snapshot = output / 'inputs'
    snapshot.mkdir()
    inventory = source_inventory(sources)
    for entry in inventory:
        target = snapshot / 'sources' / entry['path']
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b'')  # The legacy script checks existence only; no private source is copied.
    (snapshot / 'eligible.txt').write_text(''.join(name + '\n' for name in sorted({row['class'] for row in inventory})), encoding='utf-8')
    (snapshot / 'previous.tsv').write_bytes(previous.read_bytes() if previous else b'')
    # Copy the script so a concurrent checkout change cannot alter the reference mid-comparison.
    shutil.copyfile(script, snapshot / 'legacy.py')
    mapping = {}
    reports = []
    report_bytes = 0
    root_arguments = []
    for root in report_roots:
        if not root.is_dir() or root.is_symlink():
            raise ValueError('Every report root must be a regular directory')
        if root not in mapping:
            relative = f'reports/input-{len(mapping) + 1}'
            mapping[root] = relative
            destination = snapshot / relative
            destination.mkdir(parents=True)
            for path in sorted(root.glob('TEST-*.xml')):
                if path.is_symlink() or not path.is_file() or path.stat().st_size > 64 * 1024 * 1024:
                    raise ValueError('Invalid or oversized XML report')
                report_bytes += path.stat().st_size
                if report_bytes > 256 * 1024 * 1024 or len(reports) >= 20000:
                    raise ValueError('Report snapshot exceeds its size budget')
                target = destination / path.name
                shutil.copyfile(path, target)
                reports.append({'path': target.relative_to(snapshot).as_posix(), 'sha256': digest(target), 'bytes': target.stat().st_size})
        root_arguments.append(mapping[root])  # Preserve repeated roots; the core deduplicates them, Python does not.
    (snapshot / 'report-roots.txt').write_text(''.join(path + '\n' for path in root_arguments), encoding='utf-8')
    manifest = {'schemaVersion': 1, 'discovery': 'top-level TEST-*.xml per explicit input root',
                'reportRoots': root_arguments, 'reports': reports, 'sourceInventory': inventory,
                'sourceInventoryIsNativeEligibility': False, 'legacyScriptSha256': digest(snapshot / 'legacy.py'),
                'previousSha256': digest(snapshot / 'previous.tsv'), 'previousWasSupplied': previous is not None,
                'reportBytes': report_bytes}
    write_json(snapshot / 'manifest.json', manifest)
    return snapshot, manifest


def execute(command, output):
    """No shell; retain diagnostics privately, while public summaries contain only exit status."""
    with output.open('w', encoding='utf-8') as stream:
        completed = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=180,
                                   env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
    return completed.returncode


def observations(path):
    rows = []
    if path.is_file():
        for line in path.read_text(encoding='utf-8').splitlines():
            name, seconds, executed, failed, retried, ambiguous = line.split('\t')
            rows.append({'name': base64.urlsafe_b64decode(name + '=' * (-len(name) % 4)).decode(),
                         'seconds': Decimal(seconds), 'executed': executed == 'true', 'failed': failed == 'true',
                         'retried': retried == 'true', 'ambiguous': ambiguous == 'true'})
    return rows


def differences(legacy, candidate, suites, eligible, repeated_roots=False):
    duplicate_names = {name for name, count in Counter(row['name'] for row in suites).items() if count > 1}
    result = []
    for name in sorted(legacy.keys() | candidate.keys()):
        if legacy.get(name) == candidate.get(name):
            continue
        family = name.split('$', 1)[0]
        related = [row for row in suites if row['name'].split('$', 1)[0] == family]
        reasons = set()
        if repeated_roots: reasons.add('INPUT_ROOT_REPEATED')
        for row in related:
            if row['name'] in duplicate_names: reasons.add('DUPLICATE_SUITE')
            if not row['executed']: reasons.add('ALL_SKIPPED_OR_EMPTY')
            if row['failed']: reasons.add('FAILED_SUITE')
            if row['retried']: reasons.add('RETRIED_SUITE')
            if row['ambiguous']: reasons.add('AMBIGUOUS_IDENTITY')
            if row['seconds'] < Decimal('0.1'): reasons.add('MINIMUM_FLOOR')
        if '$' in name or any('$' in row['name'] for row in related): reasons.add('FAMILY_NORMALIZATION')
        if family not in eligible: reasons.add('ELIGIBILITY_POLICY')
        if not reasons: reasons.add('ROUNDING_OR_RETENTION')
        result.append({'class': name, 'legacySeconds': str(legacy[name]) if name in legacy else None,
                       'shardPilotSeconds': str(candidate[name]) if name in candidate else None, 'reasons': sorted(reasons)})
    return result


def compare(script, sources, previous, reports, core, core_sha256, output, java='java', javac='javac', shards=2, diagnose_sum=False):
    if any(path.is_symlink() for path in [script, sources, core, *reports, *([previous] if previous else [])]):
        raise ValueError('Input root symlinks are not supported')
    script, sources, core = script.resolve(), sources.resolve(), core.resolve()
    previous = previous.resolve() if previous else None
    reports = [path.resolve() for path in reports]
    output = output.resolve()
    if not re.fullmatch('[a-f0-9]{64}', core_sha256) or digest(core) != core_sha256:
        raise ValueError('Core JAR differs from the independently reviewed checksum')
    version = core_version(core)
    if shards < 1:
        raise ValueError('Shard count must be positive')
    if output.exists():
        raise ValueError('Use a new comparison output directory; previous evidence must remain immutable')
    if output == sources or sources in output.parents or any(root == output or root in output.parents for root in reports):
        raise ValueError('Comparison output must not overlap source or report inputs')
    output.mkdir(parents=True)
    snapshot, manifest = snapshot_inputs(output, script, sources, previous, reports)
    tooling = snapshot / 'tooling'
    tooling.mkdir()
    shutil.copyfile(PROBE, tooling / PROBE.name)
    shutil.copyfile(Path(__file__), tooling / Path(__file__).name)
    shutil.copyfile(core, tooling / 'core.jar')
    core = tooling / 'core.jar'
    if digest(core) != core_sha256:
        raise ValueError('Core JAR changed while freezing inputs')
    manifest['tooling'] = {name: digest(tooling / name) for name in (PROBE.name, Path(__file__).name, 'core.jar')}
    write_json(snapshot / 'manifest.json', manifest)
    classes = output / 'probe-classes'
    classes.mkdir()
    code = execute([javac, '--release', '17', '-cp', str(core), '-d', str(classes), str(tooling / PROBE.name)], output / 'javac.log')
    if code:
        raise RuntimeError('Could not compile the real-core comparison probe')
    legacy_output = output / 'legacy'
    legacy_output.mkdir()
    legacy_code = execute([sys.executable, '-B', str(snapshot / 'legacy.py'), '--previous', str(snapshot / 'previous.tsv'),
                           '--sources', str(snapshot / 'sources'), '--output', str(legacy_output / 'durations.tsv'),
                           *[str(snapshot / root) for root in manifest['reportRoots']]], legacy_output / 'run.log')
    profiles = [('safe', 'FAIL')]
    if diagnose_sum:
        profiles.append(('sum-diagnostic', 'SUM'))
    outcomes = {}
    legacy_error = None
    try:
        legacy = records(legacy_output / 'durations.tsv') if legacy_code == 0 else {}
        if legacy_code == 0 and not (legacy_output / 'durations.tsv').is_file():
            legacy_error = 'MISSING_TSV'
    except (ValueError, ArithmeticError):
        legacy, legacy_error = {}, 'INVALID_TSV'
    eligible = {row['class'].split('$', 1)[0] for row in manifest['sourceInventory']}
    for name, duplicates in profiles:
        destination = output / name
        destination.mkdir()
        code = execute([java, '-Xmx256m', '-cp', str(classes) + os.pathsep + str(core),
                        'io.github.sjacek.shardpilot.testing.HistoryRefreshProbe', str(snapshot), str(destination),
                        duplicates, 'source-inventory', str(shards)], destination / 'run.log')
        diagnostic = destination / 'durations.json'
        if not diagnostic.is_file():
            raise RuntimeError('Core probe did not produce diagnostics')
        native = json.loads(diagnostic.read_text(encoding='utf-8'))
        candidate = records(destination / 'durations.tsv') if code == 0 else {}
        if code not in (0, 1) or (code == 0) != (native['status'] == 'PASSED'):
            raise RuntimeError('Core status disagrees with process exit')
        rows = observations(destination / 'suites.tsv')
        delta = differences(legacy, candidate, rows, eligible, len(manifest['reportRoots']) != len(set(manifest['reportRoots'])))
        outcomes[name] = {'duplicates': duplicates, 'exitCode': code, 'native': native,
                          'candidate': f'{name}/durations.tsv' if code == 0 else None,
                          'candidateSha256': digest(destination / 'durations.tsv') if code == 0 else None,
                          'records': len(candidate), 'differences': delta,
                          'comparison': 'MATCH' if legacy_code == 0 and not legacy_error and code == 0 and not delta else 'REVIEW_REQUIRED'}
    safe = outcomes['safe']
    if safe['candidate']:
        shutil.copyfile(output / safe['candidate'], output / 'candidate.tsv')
    # Neither filenames nor matching counters establish that local XML belongs to a complete CI run.
    result = {'schemaVersion': 1, 'comparison': safe['comparison'], 'coreSha256': core_sha256,
              'coreVersion': version, 'comparatorSha256': manifest['tooling'][Path(__file__).name],
              'probeSourceSha256': manifest['tooling'][PROBE.name], 'inputManifestSha256': digest(snapshot / 'manifest.json'),
              'legacy': {'exitCode': legacy_code, 'outputError': legacy_error, 'scriptSha256': manifest['legacyScriptSha256'], 'records': len(legacy)},
              'profiles': outcomes, 'candidate': 'candidate.tsv' if safe['candidate'] else None,
              'adoption': {'allowed': False, 'reasons': ['PRODUCER_IDENTITY_NOT_VERIFIED', 'COHORT_COMPLETENESS_NOT_VERIFIED',
                                                        'SOURCE_INVENTORY_IS_NOT_NATIVE_ELIGIBILITY']}}
    write_json(output / 'comparison.json', result)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--legacy-script', type=Path, required=True)
    parser.add_argument('--sources', type=Path, required=True)
    parser.add_argument('--previous', type=Path)
    parser.add_argument('--reports', type=Path, action='append', required=True)
    parser.add_argument('--core-jar', type=Path, required=True)
    parser.add_argument('--core-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--shards', type=int, default=2)
    parser.add_argument('--diagnose-sum', action='store_true')
    args = parser.parse_args(argv)
    result = compare(args.legacy_script, args.sources, args.previous, args.reports, args.core_jar, args.core_sha256,
                     args.output, args.java, args.javac, args.shards, args.diagnose_sum)
    print(json.dumps({'comparison': result['comparison'], 'candidate': result['candidate'], 'adoptionAllowed': False}))
    return 0 if result['comparison'] == 'MATCH' else 2


if __name__ == '__main__':
    sys.exit(main())

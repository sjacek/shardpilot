"""Validate a pinned GitLab artifact cohort offline, without executing consumer code.

The reviewed contract and authenticated downloads are trust inputs. Checksums prove
retention integrity, not producer authenticity. Keep archives, XML and class names
private; only an explicitly redacted summary belongs in a public repository.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import stat
import xml.etree.ElementTree as ET
import zipfile

MAX_ARCHIVE = 512 * 1024 * 1024
MAX_FILE = 256 * 1024 * 1024
MAX_TOTAL = 2 * 1024 * 1024 * 1024
MAX_ENTRIES = 100_000


def sha256(path):
    """Hash streamed bytes; never accept a restored-cache checksum as review."""
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def relative(name):
    """Use portable literal paths: reject Windows aliases even on a Linux reader."""
    if (not isinstance(name, str) or not name or any(ord(c) < 32 or ord(c) == 127 for c in name)
            or any(c in name for c in '\\:<>"|?*')):
        raise ValueError('Unsafe artifact path')
    path = PurePosixPath(name)
    if path.is_absolute() or any(p in ('', '.', '..') or p.endswith((' ', '.')) for p in name.split('/')):
        raise ValueError('Unsafe artifact path')
    if any(p.split('.')[0].upper() in {'CON', 'PRN', 'AUX', 'NUL', *(f'COM{i}' for i in range(1, 10)),
                                      *(f'LPT{i}' for i in range(1, 10))} for p in path.parts):
        raise ValueError('Unsafe artifact path')
    return path


def read_file(directory, name, expected, limit=MAX_FILE):
    path = directory.joinpath(*relative(name).parts)
    if any(p.is_symlink() for p in (path, *path.parents)) or not path.is_file() or path.stat().st_size > limit:
        raise ValueError('Invalid retained input')
    if sha256(path) != expected:
        raise ValueError('Retained input checksum mismatch')
    return path


def document(raw):
    """Reject duplicate JSON keys rather than silently changing the trust contract."""
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate metadata key')
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=pairs)


class Archive:
    """Bound and validate the entire ZIP directory before reading any member."""
    def __init__(self, path):
        self.zip = zipfile.ZipFile(path)
        try:
            self.entries = {}
            aliases = set()
            total = 0
            infos = self.zip.infolist()
            if len(infos) > MAX_ENTRIES:
                raise ValueError('Artifact entry limit')
            for info in infos:
                name = info.filename.removesuffix('/')
                relative(name)
                key = name.casefold()
                mode = info.external_attr >> 16
                if key in aliases or stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR) or info.flag_bits & 1:
                    raise ValueError('Duplicate, linked or encrypted artifact')
                aliases.add(key)
                if info.is_dir():
                    if info.file_size:
                        raise ValueError('Invalid directory payload')
                    continue
                if info.file_size > MAX_FILE:
                    raise ValueError('Artifact member limit')
                total += info.file_size
                if total > MAX_TOTAL:
                    raise ValueError('Artifact expanded size limit')
                self.entries[name] = info
            # File/directory collisions must also fail on case-sensitive hosts.
            files = {n.casefold() for n in self.entries}
            if any(str(p).casefold() in files for n in self.entries for p in PurePosixPath(n).parents if str(p) != '.'):
                raise ValueError('Artifact file/directory collision')
        except BaseException:
            self.zip.close()
            raise

    def close(self):
        self.zip.close()

    def read(self, name, limit=32 * 1024 * 1024):
        relative(name)
        if name not in self.entries or self.entries[name].file_size > limit:
            raise ValueError('Missing or oversized artifact member')
        with self.zip.open(self.entries[name]) as stream:
            data = stream.read(limit + 1)
        if len(data) > limit or len(data) != self.entries[name].file_size:
            raise ValueError('Invalid artifact member bytes')
        return data

    def extract(self, destination):
        """Extract only into a newly owned directory; downloaded code is never run."""
        destination.mkdir()
        for name, info in sorted(self.entries.items()):
            target = destination.joinpath(*relative(name).parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with self.zip.open(info) as source, target.open('xb') as output:
                count = 0
                while chunk := source.read(1024 * 1024):
                    count += len(chunk)
                    if count > info.file_size or count > MAX_FILE:
                        raise ValueError('Artifact member size changed')
                    output.write(chunk)
            if count != info.file_size:
                raise ValueError('Truncated artifact member')


def classes(raw):
    """Read the canonical v1 class manifest produced by the native Gradle task."""
    lines = raw.decode('utf-8').splitlines()
    if lines[:2] != ['schemaVersion: 1', 'classes:']:
        raise ValueError('Invalid full baseline manifest')
    names = []
    for line in lines[2:]:
        if not re.fullmatch(r'  - [A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*', line, flags=re.ASCII):
            raise ValueError('Invalid baseline class')
        names.append(line[4:])
    if not names or len(names) != len(set(names)):
        raise ValueError('Empty or duplicate baseline')
    return set(names)


def xml_summary(archive, prefix, expected=None):
    """Retain native class/name/result observations, without inventing C2 invocation IDs.

    XML is a baseline observation, not a v2 execution producer. Duplicate display
    names are retained as multiplicities; row offsets are never called identities.
    """
    relative(prefix)
    totals = dict(tests=0, skipped=0, failures=0, errors=0, reports=0)
    observed = set()
    invocations = []
    for name in sorted(archive.entries):
        if not name.startswith(prefix + '/') or not name.endswith('.xml'):
            continue
        raw = archive.read(name).decode('utf-8')
        if '\x00' in raw or '<!DOCTYPE' in raw.upper() or '<!ENTITY' in raw.upper():
            raise ValueError('XML entities are not accepted')
        root = ET.fromstring(raw)
        if root.tag != 'testsuite' or not root.attrib.get('name') or root.attrib['name'] in observed:
            raise ValueError('Invalid or duplicate native XML suite')
        observed.add(root.attrib['name'])
        counts = dict(tests=0, skipped=0, failures=0, errors=0)
        for test in root.findall('testcase'):
            if not test.attrib.get('name') or not test.attrib.get('classname'):
                raise ValueError('Missing native test identity')
            result = [tag for tag in ('skipped', 'failure', 'error') if test.find(tag) is not None]
            if len(result) > 1:
                raise ValueError('Ambiguous native test result')
            counts['tests'] += 1
            if result:
                counts[{'skipped': 'skipped', 'failure': 'failures', 'error': 'errors'}[result[0]]] += 1
            invocations.append({'class': test.attrib['classname'], 'name': test.attrib['name'],
                                'result': result[0] if result else 'passed'})
        if any(int(root.attrib.get(key, '-1')) != value for key, value in counts.items()):
            raise ValueError('Native XML aggregate mismatch')
        for key, value in counts.items():
            totals[key] += value
        totals['reports'] += 1
    if not observed or expected is not None and observed != expected:
        raise ValueError('Native XML class coverage mismatch')
    if totals['failures'] or totals['errors']:
        raise ValueError('Baseline contains failed native tests')
    return totals, invocations


def verify_identity(contract, pipeline, metadata, pin):
    """Every selected job must belong to the same successful, full scheduled cohort."""
    expected = (contract['project'], contract['pipeline'], contract['revision'])
    actual = (pipeline['project_id'], pipeline['id'], pipeline['sha'])
    job = (metadata['pipeline']['project_id'], metadata['pipeline']['id'], metadata['commit']['id'])
    if (expected != actual or expected != job or metadata['pipeline']['sha'] != contract['revision']
            or pipeline['status'] != 'success' or pipeline['source'] != 'schedule'
            or metadata['status'] != 'success' or metadata['allow_failure']
            or metadata['id'] != pin['id'] or metadata['name'] != pin['name']
            or 'bytes' in pin and metadata['artifacts_file']['size'] != pin['bytes']):
        raise ValueError('Mixed, partial or unsuccessful CI cohort')


def pipeline_reports(contract, downloads, pipeline, native):
    """Keep API display-name deduplication separate from actual XML multiplicities."""
    full = document(read_file(downloads, 'native-test-report.json', contract['testReportSha256'], 32 * 1024 * 1024).read_bytes())
    summary = document(read_file(downloads, 'native-test-summary.json', contract['testSummarySha256'], 1024 * 1024).read_bytes())
    jobs = {}
    for pin in contract['reportJobs']:
        metadata = document(read_file(downloads, f"job-{pin['id']}.json", pin['metadataSha256'], 1024 * 1024).read_bytes())
        verify_identity(contract, pipeline, metadata, pin)
        if pin['id'] in jobs:
            raise ValueError('Duplicate report job')
        jobs[pin['id']] = pin['suite']
    suites = {}
    totals = Counter()
    for suite in full['test_suites']:
        if suite['name'] in suites or suite['suite_error']:
            raise ValueError('Duplicate or incomplete pipeline suite')
        counts = Counter(case['status'] for case in suite['test_cases'])
        expected = {key: counts[value] for key, value in [('total_count', None), ('success_count', 'success'),
                    ('failed_count', 'failed'), ('skipped_count', 'skipped'), ('error_count', 'error')]}
        expected['total_count'] = len(suite['test_cases'])
        if any(suite[key] != value for key, value in expected.items()) or set(counts) - {'success', 'failed', 'skipped', 'error'}:
            raise ValueError('Incomplete pipeline native report')
        if expected['failed_count'] or expected['error_count']:
            raise ValueError('Failed pipeline native report')
        suites[suite['name']] = suite
        totals.update(expected)
    if any(full[key] != value for key, value in totals.items()):
        raise ValueError('Pipeline report aggregate mismatch')
    if len(summary['test_suites']) != len(suites):
        raise ValueError('Incomplete pipeline suite summary')
    observed = set()
    result = []
    summary_names = set()
    for item in summary['test_suites']:
        if item['name'] in summary_names:
            raise ValueError('Duplicate pipeline suite summary')
        summary_names.add(item['name'])
        suite = suites[item['name']]
        if item['suite_error'] or any(item[key] != suite[key] for key in totals):
            raise ValueError('Pipeline summary differs from full report')
        ids = item['build_ids']
        if not ids or len(ids) != len(set(ids)) or any(jobs.get(job) != item['name'] for job in ids):
            raise ValueError('Pipeline suite belongs to another job')
        observed.update(ids)
        result.append({'name': item['name'], 'jobs': ids, **{key: suite[key] for key in totals}})
    if observed != jobs.keys():
        raise ValueError('Incomplete native report job coverage')
    root = suites[contract['nativeSuite']]
    shard_ids = {pin['id'] for pin in contract['jobs'] if pin['role'].startswith('shard-')}
    if set(next(s['build_ids'] for s in summary['test_suites'] if s['name'] == contract['nativeSuite'])) != shard_ids:
        raise ValueError('Native suite uses another shard cohort')
    status = {'passed': 'success', 'failure': 'failed', 'skipped': 'skipped', 'error': 'error'}
    xml_cases = Counter((case['class'], case['name'], status[case['result']]) for case in native)
    api_cases = Counter((case['classname'], case['name'], case['status']) for case in root['test_cases'])
    if set(xml_cases) != set(api_cases) or any(count != 1 for count in api_cases.values()):
        raise ValueError('Native XML and pipeline display identities differ')
    return {'suites': result, 'rootXmlOccurrences': len(native), 'rootApiDisplayIdentities': len(api_cases),
            'rootDuplicateDisplayOccurrences': len(native) - len(xml_cases)}


def collect(contract_path, downloads, output):
    """Validate first; publish a success report only after every declared gate passes."""
    contract = document(contract_path.read_bytes())
    if (contract['schemaVersion'] != 1 or not re.fullmatch('[a-f0-9]{40}|[a-f0-9]{64}', contract['revision'])
            or type(contract['shards']) is not int or not 1 <= contract['shards'] <= 256):
        raise ValueError('Invalid CI capture contract')
    if output.exists() or output.is_symlink():
        raise ValueError('Use a fresh private output directory')
    pipeline = document(read_file(downloads, 'pipeline.json', contract['pipelineSha256'], 1024 * 1024).read_bytes())
    opened = []
    try:
        roles = {}
        ids = set()
        for pin in contract['jobs']:
            if pin['id'] in ids or pin['role'] in roles:
                raise ValueError('Duplicate CI job identity or role')
            ids.add(pin['id'])
            metadata = document(read_file(downloads, f"job-{pin['id']}.json", pin['metadataSha256'], 1024 * 1024).read_bytes())
            verify_identity(contract, pipeline, metadata, pin)
            path = read_file(downloads, f"job-{pin['id']}.zip", pin['sha256'], MAX_ARCHIVE)
            if path.stat().st_size != pin['bytes']:
                raise ValueError('GitLab artifact byte count mismatch')
            archive = Archive(path)
            opened.append(archive)
            roles[pin['role']] = (archive, pin)
        if {role for role in roles if role.startswith('shard-')} != {f'shard-{i}' for i in range(1, contract['shards'] + 1)}:
            raise ValueError('Incomplete or excessive shard cohort')
        compile_archive = roles['compile'][0]
        baseline_raw = compile_archive.read(contract['baseline'])
        baseline = classes(baseline_raw)
        binaries = set()
        for name in compile_archive.entries:
            root = next((root for root in contract['eligibilityRoots'] if name.startswith(root + '/')), None)
            if root is None or not name.endswith('IT.class') or '$' in PurePosixPath(name).name:
                continue
            if PurePosixPath(name).stem in contract['dedicatedClasses']:
                continue
            binary = name[len(root) + 1:-6].replace('/', '.')
            if binary in binaries or compile_archive.entries[name].file_size == 0:
                raise ValueError('Duplicate or empty eligible bytecode')
            binaries.add(binary)
        if binaries != baseline:
            raise ValueError('Compiled candidate ownership differs from native full baseline')
        for directory in contract['requiredDirectories']:
            relative(directory)
            if not any(n.startswith(directory + '/') and i.file_size for n, i in compile_archive.entries.items()):
                raise ValueError('Missing required compilation tree')
        planned = set()
        summaries = []
        native = []
        for index in range(1, contract['shards'] + 1):
            plan_path = contract['planned'].format(index=index)
            raw = compile_archive.read(plan_path)
            plan = classes(raw)
            if plan & planned or not plan <= baseline:
                raise ValueError('Overlapping or foreign planned shard')
            planned |= plan
            archive, pin = roles[f'shard-{index}']
            actual = classes(archive.read(contract['actual'].format(index=index)))
            if actual != plan:
                raise ValueError('Actual shard differs from compile-time plan')
            # Some consumers retain their plan in shard archives too; it must be byte-identical.
            for path in (plan_path, contract['baseline']):
                if path in archive.entries and archive.read(path) != compile_archive.read(path):
                    raise ValueError('Shard retained a different compilation plan')
            summary, observations = xml_summary(archive, contract['xml'], actual)
            summaries.append({'index': index, 'classes': len(plan), 'job': pin['id'], **summary})
            native.extend(observations)
        if planned != baseline:
            raise ValueError('Incomplete full baseline coverage')
        coverage = document(roles['coverage'][0].read(contract['coverage']))
        if coverage['status'] != 'passed' or coverage['actualVerified'] is not True or coverage['classes'] != len(baseline):
            raise ValueError('Native cohort verification did not pass')
        expected_coverage = []
        for index in range(1, contract['shards'] + 1):
            raw = compile_archive.read(contract['planned'].format(index=index))
            # ShardCoverage hashes sorted binary names joined by LF, without a trailing LF.
            names = classes(raw)
            expected_coverage.append({'index': index, 'classes': len(names),
                                      'sha256': hashlib.sha256('\n'.join(sorted(names)).encode()).hexdigest()})
        if coverage['shards'] != expected_coverage:
            raise ValueError('Native coverage report belongs to another plan')
        legacy = contract.get('legacyManifest')
        if legacy:
            manifest = document(compile_archive.read(legacy))
            if manifest['version'] != 1 or manifest['commit'] != contract['revision'] or manifest['integrationSuite'] != 'full':
                raise ValueError('Legacy compilation seal identity mismatch')
            expected = {name: hashlib.sha256(compile_archive.read(name, MAX_FILE)).hexdigest()
                        for name in compile_archive.entries if any(name.startswith(root + '/') for root in contract['legacyRoots'])}
            if manifest['files'] != expected:
                raise ValueError('Legacy compilation seal file set or checksum mismatch')
        auxiliary = []
        for role, (archive, pin) in sorted(roles.items()):
            if 'xml' in pin:
                totals, _ = xml_summary(archive, pin['xml'])
                auxiliary.append({'role': role, 'job': pin['id'], **totals})
        history = roles['history'][0].read(contract['history'])
        native_report = pipeline_reports(contract, downloads, pipeline, native)
        # No success marker is written until extraction and all checks complete.
        output.mkdir(parents=True)
        compile_archive.extract(output / 'compiled')
        (output / 'baseline.yaml').write_bytes(baseline_raw)
        (output / 'history.tsv').write_bytes(history)
        (output / 'native-observations.json').write_text(json.dumps(native, ensure_ascii=True, indent=2) + '\n')
        report = {'schemaVersion': 1, 'status': 'passed', 'project': contract['project'], 'pipeline': contract['pipeline'],
                  'revision': contract['revision'], 'classes': len(baseline), 'shards': summaries, 'auxiliary': auxiliary,
                  'compiledFiles': len(compile_archive.entries), 'compiledBytes': sum(i.file_size for i in compile_archive.entries.values()),
                  'legacySealVerified': bool(legacy), 'baselineSha256': hashlib.sha256(baseline_raw).hexdigest(),
                  'nativeReport': native_report,
                  'historySha256': hashlib.sha256(history).hexdigest(), 'contractSha256': sha256(contract_path),
                  'jobs': [{k: pin[k] for k in ('id', 'name', 'role', 'sha256', 'bytes', 'metadataSha256')} for pin in contract['jobs']],
                  'limitations': ['Native XML observations are not C2 v2 execution evidence.',
                                  'The C3 seal is created after download, not by the historical CI producer.',
                                  'Dedicated and module suites keep separate native ownership.']}
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        return report
    finally:
        for archive in opened:
            archive.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--contract', type=Path, required=True)
    parser.add_argument('--downloads', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = collect(args.contract, args.downloads, args.output)
    print(json.dumps({k: result[k] for k in ('status', 'pipeline', 'revision', 'classes', 'compiledFiles')}))

"""Retain bounded audit evidence after success or failure without archiving dependency caches."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys
import zipfile

from run_published_artifact_audit import ROOT, summarize
from published_artifacts import release_version


EVIDENCE_DIRECTORIES = {'central', 'portal', 'fixtures', 'signatures', 'warm', 'isolated', 'attempts'}
EVIDENCE_FILES = {
    'audit-request.json', 'audit-summary.json', 'registry-verification.json', 'source-tag.json',
    'public-tag-ref.json', 'publisher-public-keys.asc', 'verified-evidence.json', 'negative-controls.json',
    'image-build.log', 'launcher.log', 'evidence-check.log', 'ci-report.json', 'ci-summary.md'
}
TOOLING = [
    'scripts/published_artifacts.py', 'scripts/run_published_artifact_audit.py',
    'scripts/published_audit_report.py', 'testing/published-artifacts/audit_runner.py',
    'testing/published-artifacts/check_evidence.py', 'testing/published-artifacts/NetworkProbe.java',
    'testing/published-artifacts/Dockerfile', 'testing/published-artifacts/test_audit.py',
    'security/published-artifact-audit.json', '.github/workflows/published-artifact-audit.yml'
]


def package(output, archive):
    """Allowlist public evidence; exclude tool downloads, tool homes and unrelated files."""
    archive.unlink(missing_ok=True)
    candidates = [output / name for name in sorted(EVIDENCE_FILES) if (output / name).exists()]
    for name in sorted(EVIDENCE_DIRECTORIES):
        directory = output / name
        if directory.is_symlink():
            raise ValueError('Audit evidence must not contain symlinks')
        if directory.is_dir():
            candidates.extend(directory.rglob('*'))
    files = []
    total = 0
    for path in sorted(candidates):
        if path.is_symlink():
            raise ValueError('Audit evidence must not contain symlinks')
        if path.is_dir():
            continue
        total += path.stat().st_size
        if total > 512 * 1024 * 1024:
            raise ValueError('Audit evidence exceeds the archive size budget')
        files.append(path)
    temporary = archive.with_name(archive.name + '.partial')
    try:
        with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED) as target:
            for path in files:
                target.write(path, 'evidence/' + path.relative_to(output).as_posix())
            for name in TOOLING:
                target.write(ROOT / name, 'tooling/' + name)
        temporary.replace(archive)
    finally:
        temporary.unlink(missing_ok=True)
    checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_name(archive.name + '.sha256').write_text(checksum + '  ' + archive.name + '\n', encoding='utf-8')


def finalize(output, archive, outcome):
    output.mkdir(parents=True, exist_ok=True)
    for path in (output / 'ci-report.json', output / 'ci-summary.md', archive, archive.with_name(archive.name + '.sha256')):
        path.unlink(missing_ok=True)
    report = {'schemaVersion': 1, 'outcome': outcome, 'passed': False,
              'completedAt': datetime.now(timezone.utc).isoformat(),
              'toolingCommit': os.environ.get('GITHUB_SHA'),
              'runUrl': None, 'scope': 'Published registry bytes; not unpublished release candidates.'}
    if os.environ.get('GITHUB_RUN_ID'):
        report['runUrl'] = (os.environ.get('GITHUB_SERVER_URL', 'https://github.com') + '/' +
                            os.environ.get('GITHUB_REPOSITORY', 'sjacek/shardpilot') + '/actions/runs/' + os.environ['GITHUB_RUN_ID'])
    try:
        request_path = output / 'audit-request.json'
        if request_path.is_file():
            request = json.loads(request_path.read_text(encoding='utf-8'))
            # Only the stable version enters Markdown; validate partial requests too.
            report['version'] = release_version(request['version'])
        if outcome == 'success':
            verification = summarize(output)
            native = json.loads((output / 'audit-summary.json').read_text(encoding='utf-8'))
            controls = json.loads((output / 'negative-controls.json').read_text(encoding='utf-8'))
            if not (native['passed'] and verification['passed'] and controls['passed']) \
                    or native['version'] != report.get('version') or verification['version'] != report.get('version') \
                    or native['sourceCommit'] != verification['sourceCommit'] \
                    or len(controls['rejectedMutations']) != 9 or len(set(controls['rejectedMutations'])) != 9:
                raise ValueError('Required audit phases or negative evidence controls are incomplete')
            report.update({'passed': True, 'sourceCommit': verification['sourceCommit'],
                           'registryArtifacts': verification['registryArtifacts'],
                           'openPgpSignatures': verification['openPgpSignatures'],
                           'measuredRuns': len(verification['cases']), 'comparisons': len(verification['comparisons']),
                           'outboundAttempts': sum(case['outboundAttempts'] for case in verification['cases']),
                           'rejectedMutations': len(controls['rejectedMutations'])})
    except (ValueError, KeyError, OSError, TypeError) as error:
        report['verificationError'] = type(error).__name__
    # A failed job can leave earlier phase success flags; only this current outcome is the CI verdict.
    (output / 'ci-report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    status = 'PASSED' if report['passed'] else 'FAILED / INCOMPLETE'
    summary = ('## Published artifact audit: ' + status + '\n\n' +
               '- Published version: `' + report.get('version', 'not resolved') + '`\n' +
               '- Scope: already published registry artifacts; unpublished candidate bytes are outside this result.\n' +
               '- Dependency warmup has network access; measured JVMs run offline in network-none containers.\n' +
               '- Retained archive contains raw syscall traces, native XML/events, signatures and phase logs.\n' +
               '- Tool downloads, dependency homes and publication credentials are excluded.\n')
    if report['passed']:
        summary += ('- Measured runs: ' + str(report['measuredRuns']) + '; execution comparisons: ' +
                    str(report['comparisons']) + '; outbound/DNS attempts: ' + str(report['outboundAttempts']) + '.\n')
    (output / 'ci-summary.md').write_text(summary, encoding='utf-8')
    try:
        package(output, archive)
    except (ValueError, OSError, RuntimeError) as error:
        report.update({'passed': False, 'packagingError': type(error).__name__})
        summary = summary.replace('audit: PASSED', 'audit: FAILED / INCOMPLETE')
        summary += '- Evidence packaging failed; inspect the workflow log.\n'
        (output / 'ci-report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        (output / 'ci-summary.md').write_text(summary, encoding='utf-8')
        raise
    finally:
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as stream:
                stream.write(summary)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--outcome', choices=('success', 'failure', 'cancelled', 'skipped'), required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/published-artifact-audit')
    parser.add_argument('--archive', type=Path, default=ROOT / 'build/published-artifact-audit-report.zip')
    args = parser.parse_args()
    result = finalize(args.output.resolve(), args.archive.resolve(), args.outcome)
    if args.outcome == 'success' and not result['passed']:
        sys.exit('Audit evidence is incomplete; inspect retained diagnostics')

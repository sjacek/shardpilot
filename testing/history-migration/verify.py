"""Bounded policy comparison using the caller's actual legacy script and reviewed released JAR.

No application suites run. Synthetic XML deliberately covers rejected and ambiguous inputs;
all artifacts remain in the caller's fresh, private output directory.
"""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from compare_duration_history import compare, write_json


def suite(name='AlphaIT', time='1.0', body=None, tests=1, skipped=0, failures=0):
    body = body if body is not None else '<testcase classname="example.' + name + '" name="private-method-sentinel"/>'
    return (f'<testsuite name="example.{name}" time="{time}" tests="{tests}" skipped="{skipped}" '
            f'failures="{failures}" errors="0">{body}<system-out>private-output-sentinel</system-out></testsuite>')


def scenarios():
    good = suite('BetaIT')
    return [
        ('clean-testdata', [suite()], '', 'MATCH', None),
        ('rounding-testdata', [suite(time='1.25'), suite('BetaIT', '.04')], '', 'REVIEW_REQUIRED', None),
        ('duplicate-testdata', [suite(time='1.5'), suite(time='2.5')], '', 'REVIEW_REQUIRED', 'DUPLICATE_SUITE'),
        ('repeated-input-testdata', [suite(time='1.5')], '', 'REVIEW_REQUIRED', None),
        ('nested-testdata', [suite(time='1.2'), suite('AlphaIT$Nested', '2.3')], '', 'REVIEW_REQUIRED', None),
        ('skipped-testdata', [suite(body='<testcase classname="example.AlphaIT" name="skip"><skipped/></testcase>', skipped=1), good],
         'example.AlphaIT\t8\n', 'REVIEW_REQUIRED', None),
        ('failed-testdata', [suite(time='2.3', failures=1, body='<testcase classname="example.AlphaIT" name="failed"><failure/></testcase>'), good],
         'example.AlphaIT\t8\n', 'REVIEW_REQUIRED', None),
        ('retry-testdata', [suite(time='2.3', body='<testcase classname="example.AlphaIT" name="retried"><flakyFailure/></testcase>'), good],
         'example.AlphaIT\t8\n', 'REVIEW_REQUIRED', None),
        ('foreign-testdata', [suite(), suite('ForeignIT', '3')], '', 'REVIEW_REQUIRED', None),
        ('ambiguous-testdata', [suite(time='2.3', tests=2, body='<testcase classname="example.AlphaIT" name="same"/><testcase classname="example.AlphaIT" name="same"/>'), good],
         'example.AlphaIT\t8\n', 'REVIEW_REQUIRED', None),
        ('overlap-testdata', [suite(tests=2, body='<testcase classname="example.AlphaIT" name="outer"/><testcase classname="example.AlphaIT$Nested" name="inner"/>'), suite('AlphaIT$Nested')],
         '', 'REVIEW_REQUIRED', 'OVERLAPPING_FAMILY'),
        ('broken-xml-testdata', ['<testsuite'], '', 'REVIEW_REQUIRED', 'INVALID_XML'),
        ('dtd-testdata', ['<!DOCTYPE testsuite [<!ENTITY elapsed "1.0">]>' + suite(time='&elapsed;')], '', 'REVIEW_REQUIRED', 'INVALID_XML'),
        ('nonfinite-testdata', [suite(time='NaN')], '', 'REVIEW_REQUIRED', 'INVALID_XML'),
        ('invalid-history-testdata', [suite()], 'example.AlphaIT\tNaN\n', 'REVIEW_REQUIRED', 'INVALID_HISTORY'),
        ('missing-reports-testdata', [], '', 'REVIEW_REQUIRED', 'NO_MEASUREMENTS'),
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--legacy-script', type=Path, required=True)
    parser.add_argument('--core-jar', type=Path, required=True)
    parser.add_argument('--core-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True)  # Refuse reuse: never merge evidence from separate comparisons.
    results = []
    for name, xmls, previous, expected, reason in scenarios():
        root = args.output / name
        sources = root / 'source/java/example'
        sources.mkdir(parents=True)
        for owner in ('AlphaIT', 'BetaIT', 'KeptIT'):
            (sources / (owner + '.java')).write_text('// availability only\n', encoding='utf-8')
        previous_path = root / 'previous.tsv'
        previous_path.write_text(previous, encoding='utf-8')
        roots = []
        for index, xml in enumerate(xmls):
            directory = root / f'reports-{index + 1}'
            directory.mkdir()
            (directory / f'TEST-{index + 1}.xml').write_text(xml, encoding='utf-8')
            roots.append(directory)
        if not roots:
            directory = root / 'empty'
            directory.mkdir()
            roots.append(directory)
        if name == 'repeated-input-testdata':
            roots.append(roots[0])
        result = compare(args.legacy_script, root / 'source', previous_path, roots, args.core_jar,
                         args.core_sha256, root / 'comparison', diagnose_sum=True)
        safe = result['profiles']['safe']
        if result['comparison'] != expected or reason and safe['native'].get('reason') != reason:
            raise AssertionError(f'Unexpected migration behavior for {name}: {safe}')
        if reason and result['candidate'] is not None:
            raise AssertionError('A rejected input produced a candidate')
        if name == 'duplicate-testdata' and result['profiles']['sum-diagnostic']['comparison'] != 'MATCH':
            raise AssertionError('Explicit duplicate SUM diagnostic did not match the legacy sum')
        summary = json.dumps(result)
        if 'private-method-sentinel' in summary or 'private-output-sentinel' in summary:
            raise AssertionError('Private XML payload escaped into the comparison summary')
        results.append({'scenario': name, 'comparison': expected, 'reason': safe['native'].get('reason'),
                        'differences': safe['differences'], 'candidate': result['candidate']})
    write_json(args.output / 'summary.json', {'status': 'PASSED', 'scenarios': results})
    print(f'Verified {len(results)} real-core comparison scenarios')


if __name__ == '__main__':
    main()

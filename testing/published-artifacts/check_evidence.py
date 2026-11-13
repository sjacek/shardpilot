"""Exercise fail-closed gates on private copies of a completed real evidence cohort."""
import argparse
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from run_published_artifact_audit import runner_module, summarize


def check(source):
    (source / 'negative-controls.json').unlink(missing_ok=True)
    with redirect_stdout(io.StringIO()):
        result = summarize(source)
    rejected = []
    with tempfile.TemporaryDirectory(prefix='negative-audit-', dir=ROOT / 'build') as temporary:
        target = Path(temporary).resolve()
        if not target.is_relative_to((ROOT / 'build').resolve()):
            raise ValueError('Temporary evidence must remain within the build directory')
        for name in ('registry-verification.json', 'source-tag.json'):
            shutil.copyfile(source / name, target / name)
        registry = json.loads((source / 'registry-verification.json').read_text())
        for row in registry['artifacts']:
            relative = row['file'].replace('\\', '/')
            destination = target / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / relative, destination)
        for phase in ('signatures', 'warm', 'isolated'):
            shutil.copytree(source / phase, target / phase)
        with redirect_stdout(io.StringIO()):
            summarize(target)

        def reject(name, relative, replacement):
            path = target / relative
            original = path.read_bytes()
            path.write_bytes(replacement(original))
            # An earlier green report must disappear when the current evidence is rejected.
            (target / 'verified-evidence.json').write_text('{"passed":true}')
            try:
                with redirect_stdout(io.StringIO()):
                    summarize(target)
            except (ValueError, OSError):
                if (target / 'verified-evidence.json').exists():
                    raise AssertionError('A failed evidence check retained a green report')
                rejected.append(name)
            else:
                raise AssertionError('Invalid evidence was accepted: ' + name)
            finally:
                path.write_bytes(original)

        def changed_xml(data):
            suite = ET.fromstring(data)
            suite.set('tests', str(int(suite.get('tests')) + 1))
            return ET.tostring(suite)

        runner = runner_module()
        for scenario in runner.SCENARIOS:
            name = scenario['tool'] + '/' + scenario['framework']
            base = 'isolated/' + name
            event = base + '/2-1/events.tsv'
            reject(name + ': lost invocation', event, lambda data: b'\n'.join(data.splitlines()[1:]) + b'\n')
            reject(name + ': changed outcome', event, lambda data: data.replace(b'PASSED', b'SKIPPED', 1))
            first = (target / event).read_bytes().splitlines()[0]
            reject(name + ': cross-node duplicate with unchanged totals', base + '/2-2/events.tsv',
                   lambda data: b'\n'.join([first, *data.splitlines()[1:]]) + b'\n')
            reject(name + ': blocked outbound attempt', base + '/2-1/build.strace',
                   lambda data: data + b'\n999 connect(5, {sa_family=AF_INET, sin_port=htons(443), sin_addr=inet_addr("198.51.100.2")}, 16) = -1 ENETUNREACH\n')
            xml = next((target / base / '2-1/unit-xml').glob('TEST-*.xml')).relative_to(target)
            reject(name + ': native XML disagrees', xml, changed_xml)
        reject('changed registry JAR', 'central/shardpilot-core-' + registry['version'] + '.jar', lambda data: data + b'changed')
        reject('missing signature', 'signatures/signatures.json',
               lambda data: json.dumps({**json.loads(data), 'verifiedSignatures': json.loads(data)['verifiedSignatures'][:-1]}).encode())
        def different_key(data):
            model = json.loads(data)
            model['verifiedSignatures'][-1]['primaryFingerprint'] = 'OTHER'
            return json.dumps(model).encode()
        reject('different signing identity', 'signatures/signatures.json', different_key)
        def different_tag(data):
            model = json.loads(data)
            model['verification']['payload'] = model['verification']['payload'].replace('object ', 'object wrong', 1)
            return json.dumps(model).encode()
        reject('signed tag payload differs', 'source-tag.json', different_tag)
        with redirect_stdout(io.StringIO()):
            summarize(target)
    report = {'executionSchemaVersion': runner.EXECUTION_SCHEMA_VERSION,
              'validCohortPassed': result['passed'], 'rejectedMutations': rejected,
              'passed': rejected == runner.negative_control_names()}
    (source / 'negative-controls.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=Path)
    check(parser.parse_args().evidence.resolve())

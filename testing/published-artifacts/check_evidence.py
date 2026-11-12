"""Exercise fail-closed gates on private copies of a completed real evidence cohort."""
import argparse
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from run_published_artifact_audit import summarize


def check(source):
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

        event = 'isolated/gradle/2-1/events.tsv'
        reject('lost invocation', event, lambda data: b'\n'.join(data.splitlines()[1:]) + b'\n')
        reject('changed outcome', event, lambda data: data.replace(b'PASSED', b'SKIPPED', 1))
        first = (target / event).read_bytes().splitlines()[0]
        reject('cross-node duplicate with unchanged totals', 'isolated/gradle/2-2/events.tsv',
               lambda data: b'\n'.join([first, *data.splitlines()[1:]]) + b'\n')
        reject('blocked outbound attempt', 'isolated/gradle/2-1/build.strace',
               lambda data: data + b'\n999 connect(5, {sa_family=AF_INET, sin_port=htons(443), sin_addr=inet_addr("198.51.100.2")}, 16) = -1 ENETUNREACH\n')
        xml = next((target / 'isolated/gradle/2-1/unit-xml').glob('TEST-*.xml')).relative_to(target)
        reject('native XML disagrees', xml, lambda data: data.replace(b'tests="2"', b'tests="0"', 1))
        reject('changed registry JAR', 'central/shardpilot-core-0.4.0.jar', lambda data: data + b'changed')
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
    report = {'validCohortPassed': result['passed'], 'rejectedMutations': rejected, 'passed': len(rejected) == 9}
    (source / 'negative-controls.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=Path)
    check(parser.parse_args().evidence.resolve())

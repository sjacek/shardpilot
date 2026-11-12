"""Bind native CycloneDX runtime inventories to candidate JARs and summarize local Grype scans."""
import argparse
from collections import Counter
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parent.parent
SEVERITIES = ('negligible', 'low', 'medium', 'high', 'critical')


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def sha256(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def policy():
    value = read_json(ROOT / 'security/scan-policy.json')
    if value.get('schemaVersion') != 1 or not re.fullmatch(r'\d+\.\d+\.\d+', value.get('scannerVersion', '')):
        raise ValueError('Invalid scanner policy version')
    if value.get('severityCutoff') not in SEVERITIES or value.get('includeUnfixed') is not True:
        raise ValueError('A severity threshold and inclusion of unfixed findings are mandatory')
    age = value.get('databaseMaxAgeHours')
    if type(age) is not int or not 1 <= age <= 720:
        raise ValueError('Invalid vulnerability database age policy')
    return value


def identity(component):
    result = tuple(component.get(key) for key in ('group', 'name', 'version'))
    if not all(isinstance(value, str) and value for value in result):
        raise ValueError('A runtime component is missing its Maven identity')
    return result


def validate_bom(bom):
    """Check distribution invariants in addition to the native generator's CycloneDX validation."""
    if bom.get('bomFormat') != 'CycloneDX' or bom.get('specVersion') != '1.6':
        raise ValueError('Expected a CycloneDX 1.6 document')
    root = bom['metadata']['component']
    components = [root, *bom.get('components', [])]
    refs = {}
    for component in components:
        identity(component)
        reference = component.get('bom-ref')
        if not reference or reference in refs or not component.get('purl', '').startswith('pkg:maven/'):
            raise ValueError('Missing or duplicate component reference/PURL')
        if component.get('scope') == 'excluded' or any(
                item.get('name') == 'cdx:maven:package:test' and item.get('value') == 'true'
                for item in component.get('properties', [])):
            raise ValueError('A non-runtime component entered the runtime inventory')
        refs[reference] = component
    graph = {}
    for dependency in bom.get('dependencies', []):
        reference = dependency['ref']
        children = dependency.get('dependsOn', [])
        if reference in graph or reference not in refs or any(child not in refs for child in children):
            raise ValueError('Invalid or duplicate dependency graph reference')
        graph[reference] = children
    reachable, pending = set(), [root['bom-ref']]
    while pending:
        reference = pending.pop()
        if reference not in reachable:
            reachable.add(reference)
            pending.extend(graph.get(reference, []))
    if reachable != set(refs):
        raise ValueError('The runtime inventory contains unreachable components')
    return root, refs, graph


def bind_hash(component, artifact):
    digest = sha256(artifact)
    recorded = [item['content'] for item in component.get('hashes', []) if item.get('alg') == 'SHA-256']
    if recorded and recorded != [digest]:
        raise ValueError('SBOM hash does not match candidate artifact: ' + artifact.name)
    component['hashes'] = [item for item in component.get('hashes', []) if item.get('alg') != 'SHA-256']
    component['hashes'].append({'alg': 'SHA-256', 'content': digest})
    return {'file': artifact.relative_to(ROOT).as_posix(), 'sha256': digest, 'bytes': artifact.stat().st_size}


def embedded_core(artifact, core_bom, bom):
    """A file dependency is not a Maven runtime artifact: prove the embedded core class bytes."""
    core, _, graph = validate_bom(core_bom)
    libraries = {identity(component): component for component in bom.get('components', [])}
    core_libraries = core_bom.get('components', [])
    if any(identity(component) not in libraries for component in core_libraries):
        raise ValueError('The Gradle plugin runtime does not provide every resolved core dependency')
    classes = ROOT / 'shardpilot-core/build/classes/java/main'
    entries = sorted(classes.rglob('*.class'))
    if not entries:
        raise ValueError('No compiled core classes are available for assembly verification')
    tree = hashlib.sha256()
    with zipfile.ZipFile(artifact) as jar:
        for path in entries:
            name = path.relative_to(classes).as_posix()
            content = path.read_bytes()
            if jar.read(name) != content:
                raise ValueError('Embedded core bytecode differs: ' + name)
            tree.update(name.encode() + b'\0' + hashlib.sha256(content).digest())
    component = copy.deepcopy(core)
    component.setdefault('properties', []).extend([
        {'name': 'shardpilot:delivery', 'value': 'embedded-classes'},
        {'name': 'shardpilot:embedded-class-count', 'value': str(len(entries))},
        {'name': 'shardpilot:embedded-class-tree-sha256', 'value': tree.hexdigest()}
    ])
    remap = {entry['bom-ref']: libraries[identity(entry)]['bom-ref'] for entry in core_libraries}
    bom.setdefault('components', []).append(component)
    bom['dependencies'].append({'ref': component['bom-ref'],
                                'dependsOn': [remap[ref] for ref in graph.get(core['bom-ref'], [])]})
    root_ref = bom['metadata']['component']['bom-ref']
    next(item for item in bom['dependencies'] if item['ref'] == root_ref)['dependsOn'].append(component['bom-ref'])
    return {'delivery': 'embedded-classes', 'classCount': len(entries), 'classTreeSha256': tree.hexdigest()}


def prepare(distribution, commit):
    if not re.fullmatch(r'[a-f0-9]{40,64}', commit):
        raise ValueError('Expected an immutable source commit SHA')
    output = ROOT / 'build/security' / distribution
    output.mkdir(parents=True, exist_ok=True)
    # An unsuccessful rerun must never leave a previous green report looking current.
    for name in ('bom.cdx.json', 'inventory.json', 'findings.json', 'scanner.json', 'database.json',
                 'scan-status.json', 'summary.json', 'summary.md'):
        (output / name).unlink(missing_ok=True)
    module = 'shardpilot-gradle-plugin' if distribution == 'gradle' else 'shardpilot-maven-plugin'
    build = 'build' if distribution == 'gradle' else 'target'
    raw = ROOT / module / build / 'reports/security' / distribution / 'bom.raw.json'
    bom = read_json(raw)
    root, _, _ = validate_bom(bom)
    expected = 'shardpilot' if distribution == 'gradle' else module
    if root['group'] != 'io.github.sjacek' or root['name'] != expected \
            or not re.fullmatch(r'[0-9][\w.+-]*', root['version']):
        raise ValueError('SBOM describes another distribution')
    version = root['version']
    artifact = ROOT / module / build / ('libs' if distribution == 'gradle' else '') / (expected + '-' + version + '.jar')
    artifacts = [bind_hash(root, artifact)]
    if distribution == 'gradle':
        core_bom = read_json(ROOT / 'shardpilot-core/build/reports/security/gradle/bom.raw.json')
        if identity(core_bom['metadata']['component']) != ('io.github.sjacek', 'shardpilot-core', version):
            raise ValueError('Embedded core has a different source version')
        core = embedded_core(artifact, core_bom, bom)
        excluded = ['Gradle-provided APIs', 'test configurations', 'buildscript/generator/scanner dependencies']
    else:
        core_component = next(item for item in bom['components']
                              if identity(item) == ('io.github.sjacek', 'shardpilot-core', version))
        artifacts.append(bind_hash(core_component, ROOT / 'shardpilot-core/target' / ('shardpilot-core-' + version + '.jar')))
        with zipfile.ZipFile(artifact) as jar:
            descriptor = ET.fromstring(jar.read('META-INF/maven/plugin.xml'))
        actual = {tuple(item.findtext(key) for key in ('groupId', 'artifactId', 'version'))
                  for item in descriptor.findall('dependencies/dependency')}
        if actual != {identity(item) for item in bom['components']}:
            raise ValueError('Maven plugin descriptor and runtime SBOM dependencies differ')
        core = {'delivery': 'external-artifact'}
        excluded = ['provided/system/test scopes', 'build/report plugins', 'generator/scanner dependencies']
    validate_bom(bom)
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip())
    bom['metadata'].setdefault('properties', []).extend([
        {'name': 'shardpilot:distribution', 'value': distribution},
        {'name': 'shardpilot:source-commit', 'value': commit},
        {'name': 'shardpilot:working-tree-dirty', 'value': str(dirty).lower()}
    ])
    final = output / 'bom.cdx.json'
    write_json(final, bom)
    write_json(output / 'inventory.json', {
        'schemaVersion': 1, 'distribution': distribution, 'inventoryKind': 'candidate-runtime',
        'sourceCommit': commit, 'workingTreeDirty': dirty, 'publishedArtifactsVerified': False,
        'sbomSha256': sha256(final), 'artifacts': artifacts, 'core': core,
        'generators': bom['metadata'].get('tools'), 'excludedScopes': excluded,
        'components': [identity(item) for item in bom['components']]
    })


def summarize(distribution, now=None):
    """Scanner/DB errors fail closed; severity failures still retain machine and human reports."""
    output = ROOT / 'build/security' / distribution
    for name in ('summary.json', 'summary.md'):
        (output / name).unlink(missing_ok=True)
    config = policy()
    inventory = read_json(output / 'inventory.json')
    if inventory['sbomSha256'] != sha256(output / 'bom.cdx.json'):
        raise ValueError('The scanned SBOM was modified after inventory verification')
    scanner = read_json(output / 'scanner.json')
    report = read_json(output / 'findings.json')
    database = read_json(output / 'database.json')
    scan_status = read_json(output / 'scan-status.json')
    if type(scan_status.get('exitCode')) is not int or scan_status['exitCode'] not in (0, 2):
        raise ValueError('Scanner process did not complete normally or with a severity failure')
    descriptor = report.get('descriptor', {})
    if scanner.get('version') != config['scannerVersion'] or report.get('descriptor', {}).get('version') != config['scannerVersion']:
        raise ValueError('Unexpected scanner version')
    if descriptor.get('name') != 'grype' or database.get('valid') is not True:
        raise ValueError('Scanner or vulnerability database validation failed')
    recorded_database = descriptor.get('db', {}).get('status', {})
    if recorded_database.get('valid') is not True or any(
            not database.get(key) or recorded_database.get(key) != database[key]
            for key in ('built', 'schemaVersion', 'from')):
        raise ValueError('Scan used a different or invalid vulnerability database')
    source = report.get('source', {})
    if source.get('type') != 'sbom-file' or not isinstance(source.get('target'), str):
        raise ValueError('Expected a scan of the prepared SBOM file')
    target = Path(source['target'])
    if not target.is_absolute():
        target = ROOT / target
    if target.resolve() != (output / 'bom.cdx.json').resolve():
        raise ValueError('Scan describes another SBOM file')
    options = descriptor.get('configuration', {})
    if options.get('only-fixed') is not False or options.get('only-notfixed') is not False \
            or options.get('fail-on-severity') != config['severityCutoff'] \
            or options.get('show-suppressed') is not True or options.get('exclude') != [] \
            or options.get('vex-documents') != [] or report.get('ignoredMatches'):
        raise ValueError('Scan policy was weakened or findings were suppressed')
    if not isinstance(report.get('matches'), list):
        raise ValueError('Scanner did not return a complete matches array')
    built = datetime.fromisoformat(database['built'].replace('Z', '+00:00'))
    now = now or datetime.now(timezone.utc)
    age = (now - built).total_seconds() / 3600
    if age < -1 or age > config['databaseMaxAgeHours']:
        raise ValueError('Vulnerability database is stale or from the future')
    counts = Counter()
    findings = []
    for match in report['matches']:
        vulnerability, artifact = match['vulnerability'], match['artifact']
        severity = vulnerability['severity'].lower()
        if severity not in (*SEVERITIES, 'unknown'):
            raise ValueError('Unrecognized vulnerability severity')
        counts[severity] += 1
        findings.append({'id': vulnerability['id'], 'severity': severity,
                         'package': artifact.get('purl') or artifact['name'],
                         'fix': vulnerability.get('fix', {})})
    blocked = sum(counts[level] for level in SEVERITIES[SEVERITIES.index(config['severityCutoff']):])
    if bool(blocked) != (scan_status['exitCode'] == 2):
        raise ValueError('Scanner exit status and reported severity result disagree')
    summary = {'schemaVersion': 1, 'distribution': distribution, 'sourceCommit': inventory['sourceCommit'],
               'sbomSha256': inventory['sbomSha256'], 'scanner': scanner, 'database': database,
               'policy': config, 'scanExitCode': scan_status['exitCode'],
               'severityCounts': dict(counts), 'blockingMatches': blocked,
               'findings': findings, 'passed': blocked == 0}
    write_json(output / 'summary.json', summary)
    markdown = (f'### ShardPilot {distribution}: runtime dependency scan\n\n'
                f'Grype `{config["scannerVersion"]}`, database built `{database["built"]}`. '
                f'Blocking threshold: **{config["severityCutoff"]}**, including unfixed findings.\n\n'
                f'Blocking matches: **{blocked}**. Severity counts: `{json.dumps(dict(counts))}`.\n\n'
                'This checks candidate runtime dependencies, not test/build tooling or an independent security audit.\n')
    (output / 'summary.md').write_text(markdown, encoding='utf-8')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as target:
            target.write(markdown)
    return 2 if blocked else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('policy', 'prepare', 'summarize'))
    parser.add_argument('--distribution', choices=('gradle', 'maven'))
    parser.add_argument('--commit')
    args = parser.parse_args()
    if args.command == 'policy':
        value = policy()
        if os.environ.get('GITHUB_OUTPUT'):
            with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as target:
                target.write(f'scanner-version={value["scannerVersion"]}\nseverity-cutoff={value["severityCutoff"]}\n')
                target.write(f'db-max-age={value["databaseMaxAgeHours"]}h\n')
        else:
            print(json.dumps(value))
        return 0
    if args.distribution is None:
        parser.error('--distribution is required')
    if args.command == 'prepare':
        commit = args.commit or subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        prepare(args.distribution, commit)
        return 0
    return summarize(args.distribution)


if __name__ == '__main__':
    raise SystemExit(main())

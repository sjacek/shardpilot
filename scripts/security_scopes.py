"""Keep producer dependency scopes separate and gate every locally scanned inventory."""
import argparse
import copy
import json
import os
from pathlib import Path
import re
import subprocess
import shutil
from urllib.parse import quote

import security_reports as reports
import maven_tooling


SCOPES = ('runtime', 'build', 'test', 'provided')
MODULES = {'gradle': ('', 'shardpilot-core', 'shardpilot-gradle-plugin'),
           'maven': ('', 'shardpilot-core', 'shardpilot-maven-plugin')}


def scan_scopes(distribution):
    return SCOPES + (('build-full',) if distribution == 'maven' else ())


def directory(distribution, scope):
    base = reports.ROOT / 'build/security' / distribution
    return base if scope == 'runtime' else base / scope


def native_directory(distribution, module):
    return reports.ROOT / module / ('build' if distribution == 'gradle' else 'target') / 'reports/security' / distribution


def property_value(component, name, value):
    entry = {'name': name, 'value': str(value)}
    values = component.setdefault('properties', [])
    if entry not in values:
        values.append(entry)


def sdk_component(sdk, registry):
    """Use Maven coordinates only after exact SDK bytes match a reviewed Central artifact."""
    entries = [entry for entry in registry.get('artifacts', []) if entry.get('file') == sdk['name']]
    if registry.get('schemaVersion') != 1 or len(entries) > 1:
        raise ValueError('Invalid or ambiguous Gradle SDK registry')
    if entries:
        entry = entries[0]
        expected = ('https://repo.maven.apache.org/maven2/' + entry['group'].replace('.', '/') + '/'
                    + entry['name'] + '/' + entry['version'] + '/' + entry['file'])
        if entry['file'] != entry['name'] + '-' + entry['version'] + '.jar' \
                or entry.get('source') != expected or entry['sha256'] != sdk['sha256'] \
                or not re.fullmatch(r'[a-f0-9]{64}', entry['sha256']):
            raise ValueError('Gradle SDK bytes or identity differ from the reviewed Central artifact')
        group, name, version = (entry[key] for key in ('group', 'name', 'version'))
        purl = f'pkg:maven/{group}/{name}@{version}?type=jar'
        kind = 'reviewed-maven-artifact-by-sha256'
    else:
        # These generated/internal Gradle files have no equivalent Central artifact. A newly
        # introduced third-party SDK library requires review, rather than silently losing CVE coverage.
        if sdk['name'] not in (f'gradle-api-{sdk["version"]}.jar', f'gradle-test-kit-{sdk["version"]}.jar',
                               f'gradle-installation-beacon-{sdk["version"]}.jar'):
            raise ValueError('Unreviewed third-party Gradle SDK file: ' + sdk['name'])
        group, name, version = 'gradle', sdk['name'], sdk['version']
        purl = f'pkg:generic/gradle/{quote(name, safe=".-")}@{quote(version, safe=".-")}'
        kind = 'gradle-distribution-file'
    return {'type': 'library', 'group': group, 'name': name, 'version': version, 'bom-ref': purl, 'purl': purl,
            'hashes': [{'alg': 'SHA-256', 'content': sdk['sha256']}],
            'properties': [{'name': 'shardpilot:identity-kind', 'value': kind},
                           {'name': 'shardpilot:sdk-file', 'value': sdk['name']},
                           {'name': 'shardpilot:provided-by-gradle-version', 'value': sdk['version']},
                           {'name': 'shardpilot:configuration', 'value': sdk['configuration']}]}


def maven_build_inventory(path, root):
    """Read resolve-plugins' native realm membership, not an unrelated local-repository crawl.

    Its indented dependencies are a flat transitive closure per plugin. Root membership edges
    deliberately make no claim about the exact transitive parent inside the plugin realm.
    """
    text = path.read_text(encoding='utf-8-sig')
    if not text.lstrip().startswith('The following plugins have been resolved:'):
        raise ValueError('Missing Maven plugin resolution report: ' + str(path))
    components, plugin = {}, None
    for line in text.splitlines():
        if not line.strip() or line.startswith('The following'):
            continue
        match = re.fullmatch(r'( +)([\w.-]+):([\w.-]+):([\w.-]+):([^:\s]+)(?::([^:\s]+))?:(.+)', line)
        if not match:
            raise ValueError('Unrecognized Maven resolved plugin entry: ' + line)
        indent, group, name, packaging, version, extra, filename = match.groups()
        if packaging not in ('jar', 'pom', 'maven-plugin', 'bundle', 'test-jar'):
            raise ValueError('Unrecognized resolved Maven plugin artifact type')
        # Artifact.toString puts an optional classifier before version. File paths can contain ':' on Windows.
        if extra is not None and not re.fullmatch(r'[A-Za-z]', extra):
            classifier, version = version, extra
        else:
            classifier = None
            if extra is not None:
                filename = extra + ':' + filename
        artifact = Path(filename)
        if not artifact.is_file() or artifact.suffix not in ('.jar', '.pom'):
            raise ValueError('Resolved Maven plugin file is missing: ' + filename)
        suffix = '?classifier=' + quote(classifier, safe='') if classifier else ''
        purl = f'pkg:maven/{quote(group, safe=".")}/{quote(name, safe=".-")}@{quote(version, safe=".-")}{suffix}'
        digest = reports.sha256(artifact)
        if purl in components and components[purl]['hashes'][0]['content'] != digest:
            raise ValueError('Conflicting Maven plugin artifact bytes: ' + purl)
        component = components.setdefault(purl, {'type': 'library', 'group': group, 'name': name,
                                               'version': version, 'purl': purl, 'bom-ref': purl,
                                               'hashes': [{'alg': 'SHA-256', 'content': digest}]})
        if len(indent) == 3:
            plugin = purl
        elif len(indent) != 6 or plugin is None:
            raise ValueError('Maven plugin realm has no owning plugin')
        property_value(component, 'shardpilot:maven-plugin-realm', plugin)
    if not components:
        raise ValueError('Expected resolved producer plugins, not an empty build inventory')
    return {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
            'metadata': {'component': root}, 'components': list(components.values()),
            'dependencies': [{'ref': root['bom-ref'], 'dependsOn': sorted(components)},
                             *({'ref': ref, 'dependsOn': []} for ref in sorted(components))]}


def merge_documents(root, sources):
    """Merge native graphs with explicit per-component origin and preserved original scope."""
    components, edges = {}, {root['bom-ref']: set()}
    for document, origin in sources:
        native_root = document['metadata']['component']['bom-ref']
        remap = {native_root: root['bom-ref']}
        for item in document.get('components', []):
            if origin.get('scope') == 'build' and 'configurations' in origin \
                    and reports.identity(item) == ('unspecified', 'unspecified', 'unspecified') \
                    and item.get('purl') == 'pkg:maven/unspecified/unspecified@unspecified' and not item.get('hashes'):
                # Gradle's detached buildscript resolver has no project coordinates. CycloneDX
                # represents that configuration root as this synthetic component, not a library.
                remap[item['bom-ref']] = root['bom-ref']
                continue
            key = item.get('purl')
            if not key:
                raise ValueError('Native component has no package identity')
            remap[item['bom-ref']] = key
            component = copy.deepcopy(item)
            component['bom-ref'] = key
            property_value(component, 'shardpilot:native-scope', component.get('scope', 'unspecified'))
            # Excluded test packages must still be analyzed. Preserve native scope as provenance;
            # this document's required scope means required for this dedicated scan, never shipped.
            component['scope'] = 'required'
            property_value(component, 'shardpilot:origin', json.dumps(origin, sort_keys=True))
            if key in components:
                previous = components[key]
                hashes = {entry['alg']: entry['content'] for entry in previous.get('hashes', [])}
                for entry in component.get('hashes', []):
                    if entry['alg'] in hashes and hashes[entry['alg']] != entry['content']:
                        raise ValueError('Conflicting native artifact hashes: ' + key)
                for entry in component.get('properties', []):
                    property_value(previous, entry['name'], entry['value'])
            else:
                components[key] = component
        for dependency in document.get('dependencies', []):
            if dependency['ref'] not in remap or any(child not in remap for child in dependency.get('dependsOn', [])):
                raise ValueError('Dangling native dependency reference')
            owner = remap[dependency['ref']]
            edges.setdefault(owner, set()).update(remap[child] for child in dependency.get('dependsOn', [])
                                                 if remap[child] != owner)
        # validate_bom below still rejects disconnected components; never repair a partial graph.
    final = {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
             'metadata': {'component': root}, 'components': [components[key] for key in sorted(components)],
             'dependencies': [{'ref': key, 'dependsOn': sorted(children)} for key, children in sorted(edges.items())]}
    reports.validate_bom(final, tooling=True)
    return final


def select_provided(document, tree):
    """Use Maven's effective direct scopes and native CycloneDX transitive edges together.

    Filtering compile artifacts in the generator itself loses transitive dependencies of a
    provided API. Resolve them first, then retain the closure of the actual provided roots.
    """
    if not isinstance(tree.get('children', []), list):
        raise ValueError('Missing effective Maven dependency tree')
    selected = {(item['groupId'], item['artifactId'], item['version'])
                for item in tree.get('children', []) if item.get('scope') == 'provided'}
    components = {item['bom-ref']: item for item in document.get('components', [])}
    roots = {ref for ref, item in components.items() if reports.identity(item) in selected}
    if {reports.identity(components[ref]) for ref in roots} != selected:
        raise ValueError('Provided Maven roots are missing from the resolved inventory')
    graph = {item['ref']: item.get('dependsOn', []) for item in document.get('dependencies', [])}
    reachable, pending = set(), list(roots)
    while pending:
        ref = pending.pop()
        if ref not in reachable:
            if ref not in components:
                raise ValueError('Provided dependency graph is incomplete')
            reachable.add(ref)
            pending.extend(graph.get(ref, []))
    document['components'] = [components[ref] for ref in sorted(reachable)]
    document['dependencies'] = [{'ref': document['metadata']['component']['bom-ref'], 'dependsOn': sorted(roots)},
                                *({'ref': ref, 'dependsOn': graph.get(ref, [])} for ref in sorted(reachable))]
    return document


def maven_fixture_inventory(tree, repository):
    """Convert dependency:tree's resolved graph, hashing only its explicitly selected artifacts."""
    components, edges = {}, {}

    def visit(node, root=False):
        group, name, version = (node[key] for key in ('groupId', 'artifactId', 'version'))
        classifier = node.get('classifier', '')
        packaging = node.get('type', 'jar')
        if any(not re.fullmatch(r'[\w.+-]+', value) for value in (group, name, version)) \
                or (classifier and not re.fullmatch(r'[\w.+-]+', classifier)):
            raise ValueError('Invalid native Maven fixture coordinate')
        # Match the native Maven CycloneDX type qualifier so fixture/reactor copies of the
        # same selected JAR merge, rather than creating duplicate findings within one scope.
        artifact_type = 'pom' if packaging == 'pom' else 'jar'
        qualifiers = ('classifier=' + classifier + '&' if classifier else '') + 'type=' + artifact_type
        purl = f'pkg:maven/{group}/{name}@{version}?' + qualifiers
        component = {'type': 'library', 'group': group, 'name': name, 'version': version,
                     'purl': purl, 'bom-ref': purl}
        if not root:
            if packaging not in ('jar', 'pom', 'test-jar', 'bundle', 'maven-plugin'):
                raise ValueError('Unrecognized native Maven fixture artifact type')
            extension = 'pom' if packaging == 'pom' else 'jar'
            filename = name + '-' + version + ('-' + classifier if classifier else '') + '.' + extension
            artifact = repository / group.replace('.', '/') / name / version / filename
            component['hashes'] = [{'alg': 'SHA-256', 'content': reports.sha256(artifact)}]
            property_value(component, 'shardpilot:maven-effective-scope', node.get('scope', 'unspecified'))
            if purl in components:
                property_value(components[purl], 'shardpilot:maven-effective-scope', node.get('scope', 'unspecified'))
            else:
                components[purl] = component
        children = [visit(child) for child in node.get('children', [])]
        edges.setdefault(purl, set()).update(children)
        return purl

    root_ref = visit(tree, root=True)
    root = {'type': 'library', 'group': tree['groupId'], 'name': tree['artifactId'], 'version': tree['version'],
            'purl': root_ref, 'bom-ref': root_ref}
    return {'metadata': {'component': root}, 'components': list(components.values()),
            'dependencies': [{'ref': ref, 'dependsOn': sorted(children)} for ref, children in edges.items()]}


def prepare(distribution, commit):
    base = directory(distribution, 'runtime')
    # Invalidate every old scope and the aggregate before any resolution/assembly can fail.
    for scope in scan_scopes(distribution):
        for name in ('bom.cdx.json', 'inventory.json', 'findings.json', 'scan-status.json', 'scan-error.json',
                     'scanner.json', 'database.json', 'summary.json', 'summary.md'):
            (directory(distribution, scope) / name).unlink(missing_ok=True)
    for name in ('scopes-summary.json', 'scopes-summary.md'):
        (base / name).unlink(missing_ok=True)
    reports.prepare(distribution, commit)
    runtime = reports.read_json(base / 'inventory.json')
    candidate = reports.read_json(base / 'bom.cdx.json')['metadata']['component']
    for scope in scan_scopes(distribution)[1:]:
        sources, inputs = [], []
        for module in MODULES[distribution]:
            native = native_directory(distribution, module)
            if distribution == 'maven' and scope not in ('build', 'build-full') and not module:
                # Parent packaging has no compile/test classpath. Child makeBom reports are mandatory.
                continue
            origin = {'project': module or ':', 'scope': scope}
            if distribution == 'maven' and scope in ('build', 'build-full'):
                path = native / 'build/plugins.raw.txt'
                origin['profiles'] = ['security-sbom', 'central-release']
                document = maven_build_inventory(path, copy.deepcopy(candidate))
                if scope == 'build':
                    document = maven_tooling.select_blocking(document, maven_tooling.policy(reports.ROOT))
                    origin['selection'] = 'reviewed blocking plugin realms, including shared transitive libraries'
            else:
                path = native / scope / 'bom.raw.json'
                document = reports.read_json(path)
                if distribution == 'maven' and scope == 'provided':
                    tree_path = native / 'project-dependencies.raw.json'
                    document = select_provided(document, reports.read_json(tree_path))
                    inputs.append({'file': tree_path.relative_to(reports.ROOT).as_posix(),
                                   'sha256': reports.sha256(tree_path)})
                    origin['selection'] = 'transitive closure of effective provided roots'
                elif distribution == 'maven':
                    origin['selection'] = 'effective test classpath: compile, runtime, provided and test'
                if distribution == 'gradle':
                    evidence = reports.read_json(native / scope / 'inputs.json')
                    if evidence.get('scope') != scope or evidence.get('schemaVersion') != 1:
                        raise ValueError('Wrong Gradle scope provenance')
                    origin['configurations'] = evidence['configurations']
                    origin['gradleVersion'] = evidence['gradleVersion']
                    registry_path = reports.ROOT / 'security/gradle-sdk-artifacts.json'
                    registry = reports.read_json(registry_path) if evidence['localArtifacts'] else None
                    if registry is not None:
                        inputs.append({'file': registry_path.relative_to(reports.ROOT).as_posix(),
                                       'sha256': reports.sha256(registry_path)})
                    for sdk in evidence['localArtifacts']:
                        component = sdk_component(sdk, registry)
                        purl = component['purl']
                        document.setdefault('components', []).append(component)
                        native_root = document['metadata']['component']['bom-ref']
                        edge = next((item for item in document.setdefault('dependencies', []) if item['ref'] == native_root), None)
                        if edge is None:
                            edge = {'ref': native_root, 'dependsOn': []}
                            document['dependencies'].append(edge)
                        edge.setdefault('dependsOn', []).append(purl)
                    inputs.append({'file': (native / scope / 'inputs.json').relative_to(reports.ROOT).as_posix(),
                                   'sha256': reports.sha256(native / scope / 'inputs.json')})
            origin['file'] = path.relative_to(reports.ROOT).as_posix()
            inputs.append({'file': origin['file'], 'sha256': reports.sha256(path)})
            sources.append((document, origin))
        if distribution == 'maven' and scope == 'test':
            manifest = reports.ROOT / 'build/reports/security/maven/test/fixtures.json'
            fixtures = reports.read_json(manifest)
            if fixtures.get('schemaVersion') != 1 or not fixtures.get('fixtures'):
                raise ValueError('Missing native Maven fixture/provider inventories')
            inputs.append({'file': manifest.relative_to(reports.ROOT).as_posix(), 'sha256': reports.sha256(manifest)})
            repository = reports.ROOT / 'build/ci-dependencies/maven'
            for fixture in fixtures['fixtures']:
                for path, expected in [(fixture['file'], fixture['sha256']), (fixture['pom'], fixture['pomSha256']),
                                       *((entry['file'], entry['sha256']) for entry in fixture['sources'])]:
                    resolved = (reports.ROOT / path).resolve()
                    if not resolved.is_relative_to(reports.ROOT.resolve()) or reports.sha256(resolved) != expected:
                        raise ValueError('Maven fixture inventory or source model changed after resolution')
                    inputs.append({'file': path, 'sha256': expected})
                document = maven_fixture_inventory(reports.read_json(reports.ROOT / fixture['file']), repository)
                sources.append((document, {'scope': scope, 'project': fixture['pom'],
                                           'selection': 'native Invoker/example/provider dependency tree'}))
        if distribution == 'maven' and scope in ('build', 'build-full'):
            # Explain inherited/managed tooling without filtering any plugin realm from scanning.
            model_path = reports.ROOT / 'build/reports/security/maven/tooling-model.json'
            model = reports.read_json(model_path)
            if model.get('schemaVersion') != 1 or model.get('interpretation') != 'effective-bindings-not-execution-proof' \
                    or not model.get('projects'):
                raise ValueError('Missing sanitized effective Maven tooling model')
            maven_tooling.review_model(model, maven_tooling.policy(reports.ROOT))
            inputs.append({'file': model_path.relative_to(reports.ROOT).as_posix(), 'sha256': reports.sha256(model_path)})
            guard_document, guard_inputs = maven_tooling.guard_inventory(candidate, reports.ROOT)
            sources.append((guard_document, {'scope': scope, 'selection': 'authored startup guard and verified compile APIs'}))
            inputs.extend(guard_inputs)
            # The checksum verifier is a JVM startup extension rather than a POM plugin realm.
            # Its compact JAR is independently hash-pinned before Maven starts.
            config_path = reports.ROOT / 'security/dependency-verification.json'
            verifier = reports.read_json(config_path)['mavenExtension']
            artifact = reports.ROOT / 'build/dependency-verification' / ('maven-checksum-extension-' + verifier['version'] + '.jar')
            if reports.sha256(artifact) != verifier['sha256']:
                raise ValueError('Maven startup verifier differs from its pinned hash')
            purl = 'pkg:maven/codes.rafael.mavenchecksumextension/maven-checksum-extension@' + verifier['version']
            component = {'type': 'library', 'group': 'codes.rafael.mavenchecksumextension',
                         'name': 'maven-checksum-extension', 'version': verifier['version'], 'purl': purl,
                         'bom-ref': purl, 'hashes': [{'alg': 'SHA-256', 'content': verifier['sha256']}]}
            document = {'metadata': {'component': copy.deepcopy(candidate)}, 'components': [component],
                        'dependencies': [{'ref': candidate['bom-ref'], 'dependsOn': [purl]}]}
            sources.append((document, {'scope': scope, 'selection': 'pinned Maven startup checksum extension'}))
            inputs.append({'file': config_path.relative_to(reports.ROOT).as_posix(), 'sha256': reports.sha256(config_path)})
        document = merge_documents(copy.deepcopy(candidate), sources)
        if not document['components']:
            raise ValueError('Expected dependencies in combined ' + scope + ' inventory')
        document['metadata']['properties'] = [
            {'name': 'shardpilot:scope', 'value': scope},
            {'name': 'shardpilot:source-commit', 'value': commit},
            {'name': 'shardpilot:distribution', 'value': distribution}]
        output = directory(distribution, scope)
        # Raw native evidence is part of the uploaded artifact, not merely a checkout-only path.
        for index, source in enumerate(inputs):
            target = output / 'native' / str(index) / Path(source['file']).name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(reports.ROOT / source['file'], target)
            source['retainedFile'] = target.relative_to(base).as_posix()
        reports.write_json(output / 'bom.cdx.json', document)
        reports.write_json(output / 'inventory.json', {
            'schemaVersion': 1, 'distribution': distribution, 'scope': scope,
            'inventoryKind': 'candidate-' + scope, 'sourceCommit': commit,
            'workingTreeDirty': runtime['workingTreeDirty'], 'publishedArtifactsVerified': False,
            'candidateArtifacts': runtime['artifacts'], 'sbomSha256': reports.sha256(output / 'bom.cdx.json'),
            'inputs': inputs, 'origins': [origin for _, origin in sources],
            'components': [reports.identity(item) for item in document['components']],
            'limitations': ['Scopes overlap; counts must not be added as unique vulnerabilities.',
                            'Gradle SDK file identities do not decompose vendored code or guarantee advisory matches.',
                            'Inventories exclude the CI operating system, JDK and scanner executable.']})


def scan(distribution, executable):
    """Complete every scope even after a severity failure; preserve each native exit status."""
    failed = False
    for scope in scan_scopes(distribution):
        output = directory(distribution, scope)
        for name in ('findings.json', 'scan-status.json', 'scan-error.json', 'summary.json', 'summary.md'):
            (output / name).unlink(missing_ok=True)
        try:
            reports.read_json(output / 'inventory.json')
            command = [executable, 'sbom:' + str(output / 'bom.cdx.json'), '--config', 'security/grype.yaml',
                       '--output', 'json', '--file', str(output / 'findings.json'),
                       '--fail-on', reports.policy()['severityCutoff'], '--only-fixed=false', '--show-suppressed']
            status = subprocess.run(command, cwd=reports.ROOT, check=False).returncode
        except (OSError, ValueError) as failure:
            reports.write_json(output / 'scan-error.json', {'error': str(failure)})
            status = 1
        reports.write_json(output / 'scan-status.json', {'exitCode': status})
        # Diagnostic findings are retained, but missing/failed scans still fail the gate.
        failed |= status != 0 and not (scope == 'build-full' and status == 2)
    return 1 if failed else 0


def summarize(distribution, now=None):
    """Validate every scope independently; one missing/invalid report blocks the whole distribution."""
    base = directory(distribution, 'runtime')
    for name in ('scopes-summary.json', 'scopes-summary.md'):
        (base / name).unlink(missing_ok=True)
    results = {}
    for scope in scan_scopes(distribution):
        output = directory(distribution, scope)
        for name in ('summary.json', 'summary.md'):
            (output / name).unlink(missing_ok=True)
        try:
            if scope != 'runtime':
                inventory = reports.read_json(output / 'inventory.json')
                runtime = reports.read_json(base / 'inventory.json')
                if inventory['sourceCommit'] != runtime['sourceCommit'] or inventory['candidateArtifacts'] != runtime['artifacts']:
                    raise ValueError('Dependency scopes describe different candidate artifacts or commits')
                if not isinstance(inventory.get('inputs'), list) or not inventory['inputs']:
                    raise ValueError('Missing native inventory evidence')
                for source in inventory['inputs']:
                    retained = (base / source['retainedFile']).resolve()
                    if not retained.is_relative_to(base.resolve()) or reports.sha256(retained) != source['sha256']:
                        raise ValueError('Native inventory evidence was changed or is outside the report')
                for name in ('scanner.json', 'database.json'):
                    reports.write_json(output / name, reports.read_json(base / name))
            reports.summarize(distribution, now=now, scope=scope)
            results[scope] = reports.read_json(output / 'summary.json')
        except (OSError, ValueError, KeyError, TypeError) as failure:
            results[scope] = {'passed': False, 'error': str(failure)}
    passed = all(value['passed'] for scope, value in results.items() if scope != 'build-full') \
        and ('build-full' not in results or 'error' not in results['build-full'])
    reports.write_json(base / 'scopes-summary.json', {'schemaVersion': 1, 'distribution': distribution,
                                                    'passed': passed, 'blockingScopes': list(SCOPES),
                                                    'diagnosticScopes': ['build-full'] if distribution == 'maven' else [],
                                                    'scopes': results})
    markdown = f'### ShardPilot {distribution}: all dependency scopes\n\n'
    markdown += '| Scope | Gate | Result |\n| --- | --- | --- |\n'
    markdown += ''.join(f'| {scope} | {"Diagnostic findings; evidence required" if scope == "build-full" else "Required"} | '
                        f'{"ERROR" if "error" in value else "PASS" if value["passed"] else "FINDINGS" if scope == "build-full" else "FAIL"} |\n'
                        for scope, value in results.items())
    markdown += '\nScopes can overlap. Consult each findings.json; do not sum them as unique vulnerabilities.\n'
    (base / 'scopes-summary.md').write_text(markdown, encoding='utf-8')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as target:
            target.write(markdown)
    return 0 if passed else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'scan', 'summarize'))
    parser.add_argument('--distribution', choices=tuple(MODULES), required=True)
    parser.add_argument('--commit')
    parser.add_argument('--grype')
    args = parser.parse_args()
    if args.command == 'prepare':
        commit = args.commit or subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=reports.ROOT, text=True).strip()
        prepare(args.distribution, commit)
        return 0
    if args.command == 'scan':
        if not args.grype:
            parser.error('--grype is required for scan')
        return scan(args.distribution, args.grype)
    return summarize(args.distribution)


if __name__ == '__main__':
    raise SystemExit(main())

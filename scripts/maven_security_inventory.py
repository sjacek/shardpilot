"""Generate reactor SBOMs and resolve producer plugin realms without executing release goals."""
import xml.etree.ElementTree as ET
from pathlib import Path
from tempfile import TemporaryDirectory

from ci_dependency_cache import DEPENDENCY_PLUGIN, ROOT, NS, maven
from security_reports import sha256, write_json


def tooling_model(path):
    """Retain effective plugin bindings, never arbitrary POM properties or plugin configuration.

    Binding presence is model evidence, not proof that a goal ran. Every resolved realm stays
    in the build SBOM and severity gate, including managed-only and site-lifecycle tooling.
    """
    root = ET.parse(path).getroot()
    projects = [root] if root.tag == '{' + NS['m'] + '}project' else list(root)
    result = []
    for project in projects:
        artifact = project.findtext('m:artifactId', namespaces=NS)
        if not artifact:
            raise ValueError('Effective Maven model has no project identity')
        selected = {}
        for location, role in (('m:build/m:pluginManagement/m:plugins/m:plugin', 'managed-only'),
                               ('m:build/m:plugins/m:plugin', 'effective-build-plugin')):
            for plugin in project.findall(location, NS):
                group = plugin.findtext('m:groupId', 'org.apache.maven.plugins', NS)
                name = plugin.findtext('m:artifactId', namespaces=NS)
                version = plugin.findtext('m:version', namespaces=NS)
                if not name or not version:
                    raise ValueError('Effective Maven plugin has no resolved identity')
                executions = [{'id': entry.findtext('m:id', namespaces=NS),
                               'phase': entry.findtext('m:phase', namespaces=NS),
                               'goals': [goal.text for goal in entry.findall('m:goals/m:goal', NS)]}
                              for entry in plugin.findall('m:executions/m:execution', NS)]
                site_only = bool(executions) and all(entry['phase'] in ('pre-site', 'site', 'post-site', 'site-deploy')
                                                     for entry in executions)
                selected[(group, name)] = {'groupId': group, 'artifactId': name, 'version': version,
                                           'modelRole': role, 'executions': executions,
                                           'binding': 'site-lifecycle-only' if site_only else 'other-or-unspecified'}
        if not selected:
            raise ValueError('Effective Maven model has no producer tooling')
        result.append({'artifactId': artifact, 'plugins': sorted(selected.values(),
                       key=lambda entry: (entry['groupId'], entry['artifactId']))})
    if not result:
        raise ValueError('Empty effective Maven model')
    return {'schemaVersion': 1, 'interpretation': 'effective-bindings-not-execution-proof', 'projects': result}


def effective_tooling_model():
    output = ROOT / 'build/reports/security/maven/tooling-model.json'
    output.unlink(missing_ok=True)
    # An effective POM may interpolate local settings. Retain only the explicit identity/binding
    # whitelist above, and remove the complete temporary model even if Maven or parsing fails.
    with TemporaryDirectory(prefix='shardpilot-effective-pom-') as temporary:
        path = Path(temporary) / 'effective-pom.xml'
        maven('-Psecurity-sbom,central-release', '-Dcentral.skipUpload=true',
              'org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom', '-Doutput=' + str(path))
        write_json(output, tooling_model(path))


def resolve_plugins():
    # This direct dependency-plugin goal reads the effective release model, including extension
    # and plugin dependencies. It runs no lifecycle phases, signing goals or upload operations.
    maven('-Psecurity-sbom,central-release', '-Dcentral.skipUpload=true', DEPENDENCY_PLUGIN + ':resolve-plugins')


def fixture_inventories():
    """Resolve the actual Invoker/example POMs and the lazily selected Surefire provider.

    Copies contain POMs only. dependency:tree runs no test, lifecycle or Invoker hook and
    preserves native mediation, inherited scopes and reactor structure in each JSON tree.
    """
    manifest = ROOT / 'build/reports/security/maven/test/fixtures.json'
    manifest.unlink(missing_ok=True)
    model = ET.parse(ROOT / 'pom.xml')
    version = model.findtext('m:version', namespaces=NS)
    projects, entries = [], []
    for source in (ROOT / 'shardpilot-maven-plugin/src/it', ROOT / 'examples/maven'):
        for project in sorted(source.iterdir()):
            if not (project / 'pom.xml').is_file():
                continue
            target = ROOT / 'build/security-fixtures/maven' / source.name / project.name
            poms, copied_models = [], []
            for pom in sorted(project.rglob('pom.xml')):
                if 'target' in pom.relative_to(project).parts:
                    continue
                destination = target / pom.relative_to(project)
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_text(pom.read_text(encoding='utf-8').replace('@project.version@', version), encoding='utf-8')
                copied_models.append(destination)
                poms.append({'file': pom.relative_to(ROOT).as_posix(), 'sha256': sha256(pom)})
            projects.append((target, poms, copied_models))
    # Surefire resolves its JUnit provider dynamically, outside ordinary project/plugin graphs.
    # Use the reactor's own managed JUnit versions rather than the provider's old default engine.
    provider = ET.Element('project', {'xmlns': NS['m']})
    for key, value in (('modelVersion', '4.0.0'), ('groupId', 'io.github.sjacek.security'),
                       ('artifactId', 'native-test-provider'), ('version', version)):
        ET.SubElement(provider, key).text = value
    management = ET.SubElement(provider, 'dependencyManagement')
    managed = ET.SubElement(management, 'dependencies')
    bom = ET.SubElement(managed, 'dependency')
    junit = model.findtext('m:properties/m:junit.version', namespaces=NS)
    for key, value in (('groupId', 'org.junit'), ('artifactId', 'junit-bom'), ('version', junit),
                       ('type', 'pom'), ('scope', 'import')):
        ET.SubElement(bom, key).text = value
    dependencies = ET.SubElement(provider, 'dependencies')
    dependency = ET.SubElement(dependencies, 'dependency')
    for key, value in (('groupId', 'org.apache.maven.surefire'), ('artifactId', 'surefire-junit-platform'),
                       ('version', model.findtext('m:properties/m:surefire.version', namespaces=NS)), ('scope', 'test')):
        ET.SubElement(dependency, key).text = value
    target = ROOT / 'build/security-fixtures/maven/native-provider'
    target.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(provider).write(target / 'pom.xml', encoding='utf-8', xml_declaration=True)
    projects.append((target, [{'file': 'pom.xml', 'sha256': sha256(ROOT / 'pom.xml')}], [target / 'pom.xml']))
    for target, poms, copied_models in projects:
        for pom in copied_models:
            (pom.parent / 'security-dependencies.raw.json').unlink(missing_ok=True)
        maven('-f', str(target / 'pom.xml'), DEPENDENCY_PLUGIN + ':tree', '-DoutputType=json',
              '-DoutputFile=security-dependencies.raw.json')
        for pom in copied_models:
            tree = pom.parent / 'security-dependencies.raw.json'
            if not tree.is_file():
                raise ValueError('Missing native Maven fixture dependency tree: ' + str(tree))
            entries.append({'file': tree.relative_to(ROOT).as_posix(), 'sha256': sha256(tree),
                            'pom': pom.relative_to(ROOT).as_posix(), 'pomSha256': sha256(pom), 'sources': poms})
    write_json(manifest, {'schemaVersion': 1, 'fixtures': entries})


def main():
    # Install only into the isolated, non-exported candidate namespace so fixture parent/plugin
    # metadata can be verified with the same consumer checksums as native Invoker tests.
    maven('-Psecurity-sbom', '-DskipTests', '-DskipITs', 'install')
    resolve_plugins()
    effective_tooling_model()
    fixture_inventories()


if __name__ == '__main__':
    main()

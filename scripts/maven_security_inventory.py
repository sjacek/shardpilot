"""Generate reactor SBOMs and resolve producer plugin realms without executing release goals."""
import xml.etree.ElementTree as ET

from ci_dependency_cache import DEPENDENCY_PLUGIN, ROOT, NS, maven
from security_reports import sha256, write_json


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
    fixture_inventories()


if __name__ == '__main__':
    main()

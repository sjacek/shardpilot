"""Accept packaged release bytes in fresh Gradle/Maven consumers, without publication.

Candidate mode builds real file publications. Registry mode imports a previously
completed registry/signature/isolation audit. Neither mode uses a composite build
or trusts a ShardPilot binary restored from a dependency cache. Native dependency
verification stays strict; exact candidate hashes extend only disposable policies.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

import maven_verification
from collect_ci_baseline import document

ROOT = Path(__file__).resolve().parents[1]
NS = '{http://maven.apache.org/POM/4.0.0}'
GRADLE_NS = 'https://schema.gradle.org/dependency-verification'
GROUP = 'io/github/sjacek'
MARKER = 'io.github.sjacek.shardpilot.gradle.plugin'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def version():
    value = ET.parse(ROOT / 'pom.xml').findtext(NS + 'version')
    if not value or not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', value):
        raise ValueError('A stable release version is required')
    return value


def artifact_paths(release):
    """The same fifteen binary, metadata and IDE source/doc artifacts used by registry audit."""
    paths = []
    for artifact in ('shardpilot-parent', 'shardpilot-core', 'shardpilot-maven-plugin', 'shardpilot'):
        suffixes = ['.pom'] if artifact.endswith('parent') else ['.pom', '.jar', '-sources.jar', '-javadoc.jar']
        if artifact == 'shardpilot':
            suffixes.append('.module')
        paths.extend(f'{GROUP}/{artifact}/{release}/{artifact}-{release}{suffix}' for suffix in suffixes)
    paths.append(f'{GROUP}/shardpilot/{MARKER}/{release}/{MARKER}-{release}.pom')
    return sorted(paths)


def verify_receipt(manifest):
    """Recheck every exact artifact before each stage; changed or extra versioned bytes fail closed."""
    if manifest.is_symlink() or not manifest.is_file() or manifest.stat().st_size > 1024 * 1024:
        raise ValueError('Invalid publication receipt')
    data = document(manifest.read_text(encoding='utf-8'))
    release = data.get('version', '')
    if type(data.get('schemaVersion')) is not int or data['schemaVersion'] != 1 or not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', release):
        raise ValueError('Invalid release receipt identity')
    if data.get('source') not in ('candidate-publication', 'audited-registries'):
        raise ValueError('Unknown publication origin')
    expected = set(artifact_paths(release))
    if set(data.get('files', {})) != expected:
        raise ValueError('Incomplete publication cohort')
    repository = manifest.parent / 'repository'
    actual = {p.relative_to(repository).as_posix() for p in repository.rglob('*') if p.is_file()}
    if actual != expected:
        raise ValueError('Unexpected publication repository file set')
    for relative, pin in data['files'].items():
        path = repository / relative
        if any(p.is_symlink() for p in (path, *path.parents)) or not path.is_file():
            raise ValueError('Linked or missing publication artifact')
        if (type(pin['bytes']) is not int or not 0 < pin['bytes'] <= 256 * 1024 * 1024
                or not re.fullmatch('[a-f0-9]{64}', pin['sha256'])
                or path.stat().st_size != pin['bytes'] or digest(path) != pin['sha256']):
            raise ValueError('Publication artifact changed')
    marker = ET.parse(repository / f'{GROUP}/shardpilot/{MARKER}/{release}/{MARKER}-{release}.pom').getroot()
    dependency = marker.find(NS + 'dependencies/' + NS + 'dependency')
    if dependency is None or [dependency.findtext(NS + field) for field in ('groupId', 'artifactId', 'version')] != ['io.github.sjacek', 'shardpilot', release]:
        raise ValueError('Plugin marker points to another implementation')
    module_path = f'{GROUP}/shardpilot/{release}/shardpilot-{release}.module'
    module = document((repository / module_path).read_text(encoding='utf-8'))
    component = module.get('component', {})
    if [component.get(field) for field in ('group', 'module', 'version')] != ['io.github.sjacek', 'shardpilot', release]:
        raise ValueError('Gradle module identifies another implementation')
    for variant in module.get('variants', []):
        for artifact in variant.get('files', []):
            relative = f"{GROUP}/shardpilot/{release}/{artifact['name']}"
            if relative not in data['files'] or artifact['sha256'] != data['files'][relative]['sha256']:
                raise ValueError('Gradle module artifact checksum differs')
    return data


def gradle_checksums(manifest, destination):
    """Extend a copied native policy with exact reviewed publication bytes, never trusted groups."""
    receipt = verify_receipt(manifest)
    ET.register_namespace('', GRADLE_NS)
    ET.register_namespace('xsi', 'http://www.w3.org/2001/XMLSchema-instance')
    tree = ET.parse(destination)
    components = tree.getroot().find('{' + GRADLE_NS + '}components')
    for relative, pin in receipt['files'].items():
        parts = relative.split('/')
        group, name, release, filename = '.'.join(parts[:-3]), parts[-3], parts[-2], parts[-1]
        if name not in ('shardpilot', MARKER):
            continue
        if any(c.attrib == {'group': group, 'name': name, 'version': release} for c in components):
            component = next(c for c in components if c.attrib == {'group': group, 'name': name, 'version': release})
        else:
            component = ET.SubElement(components, '{' + GRADLE_NS + '}component', group=group, name=name, version=release)
        if any(a.get('name') == filename for a in component):
            raise ValueError('Candidate overlaps a committed external checksum')
        artifact = ET.SubElement(component, '{' + GRADLE_NS + '}artifact', name=filename)
        ET.SubElement(artifact, '{' + GRADLE_NS + '}sha256', value=pin['sha256'], origin='Verified release-consumer publication receipt')
    tree.write(destination, encoding='utf-8', xml_declaration=True)


def verify_maven_candidates(manifest, repository):
    """Do not bless altered cached candidates when generating a disposable Maven checksum policy."""
    receipt = verify_receipt(manifest)
    for relative, pin in receipt['files'].items():
        if relative.split('/')[-3] not in ('shardpilot-parent', 'shardpilot-core', 'shardpilot-maven-plugin'):
            continue
        path = repository / relative
        if path.is_symlink() or not path.is_file() or digest(path) != pin['sha256']:
            raise ValueError('Maven consumer candidate differs from publication receipt')


def seed_downloads(source, target, kind):
    """Share downloads only. Source builds, user settings, credentials and our cached binaries stay out."""
    prefixes = ('caches/modules-2', 'wrapper/dists') if kind == 'gradle' else ('wrapper/dists',) if kind == 'maven-wrapper' else ('',)
    distribution = None
    gradle_components = set()
    maven_directories = set()
    if kind == 'gradle':
        distribution = re.search(r'gradle-([0-9.]+)-(?:bin|all)',
                                 (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text()).group(1)
        model = ET.parse(ROOT / 'gradle/verification-metadata.xml')
        gradle_components = {(c.get('group'), c.get('name'), c.get('version'))
                             for c in model.findall('.//{' + GRADLE_NS + '}component')}
    if kind == 'maven':
        for identity in maven_verification.load_checksums(maven_verification.BASELINE):
            fields = identity.split(':')
            maven_directories.add(fields[0].replace('.', '/') + '/' + fields[1] + '/' + fields[-1])
    for prefix in prefixes:
        base = source / prefix
        if not base.is_dir():
            continue
        for path in base.rglob('*'):
            if not path.is_file() or path.is_symlink():
                continue
            relative = path.relative_to(base)
            if kind == 'gradle' and prefix == 'caches/modules-2' and relative.parts[0] == 'files-2.1':
                if len(relative.parts) < 5 or tuple(relative.parts[1:4]) not in gradle_components:
                    continue
            if kind == 'maven' and relative.parent.as_posix() not in maven_directories:
                continue
            if kind == 'gradle' and prefix == 'wrapper/dists':
                if not relative.parts[0].startswith('gradle-' + distribution + '-'):
                    continue
            if path.name.endswith(('.lock', '.lck', '.part', '.tmp', '.lastUpdated')):
                continue
            if path.name in ('settings.xml', 'settings-security.xml', 'gradle.properties', 'init.gradle', 'init.gradle.kts'):
                continue
            if any(part in ('io.github.sjacek', 'io.github.sjacek.shardpilot') for part in relative.parts):
                continue
            if kind == 'maven' and relative.parts[:3] == ('io', 'github', 'sjacek'):
                continue
            destination = target / prefix / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, destination)


def launch(command, log, environment=None):
    with log.open('wb') as stream:
        result = subprocess.run(command, cwd=ROOT, env=environment, stdout=stream, stderr=subprocess.STDOUT, timeout=1200)
    if result.returncode:
        raise RuntimeError('Release-consumer preparation failed; inspect ' + str(log))


def prepare(output, gradle, maven, maven_repository, registry=None):
    """Stage producer publications or import authenticated registry evidence, retaining their provenance."""
    output.mkdir(parents=True)
    repository = output / 'repository'
    repository.mkdir()
    release = version()
    if registry is None:
        published = output / 'gradle-publication'
        launch([*gradle, ':shardpilot-gradle-plugin:publishAllPublicationsToReleaseAcceptanceRepository',
                '-PreleaseAcceptanceRepository=' + str(published), '--console=plain'], output / 'gradle-publication.log')
        launch([*maven, '-B', '-ntp', '-Dmaven.repo.local=' + str(maven_repository), '-DskipTests', '-DskipITs',
                'package', 'org.apache.maven.plugins:maven-source-plugin:3.4.0:jar-no-fork',
                'org.apache.maven.plugins:maven-javadoc-plugin:3.12.0:jar', 'install'], output / 'maven-publication.log',
               maven_verification.environment(False))
        source = 'candidate-publication'
        provenance = {'baseCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                      'workingTreeChanged': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT))}
        for relative in artifact_paths(release):
            artifact = relative.split('/')[-3]
            origin = published if artifact in ('shardpilot', MARKER) else maven_repository
            destination = repository / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(origin / relative, destination)
    else:
        # Re-read signatures, source tag, all registry bytes and isolated native observations.
        # Merely relabeling a local cache or an unsigned receipt as "registry" is insufficient.
        import run_published_artifact_audit
        run_published_artifact_audit.summarize(registry)
        audited = json.loads((registry / 'registry-verification.json').read_text(encoding='utf-8'))
        if audited['version'] != release:
            raise ValueError('Registry audit version differs from release candidate')
        source = 'audited-registries'
        provenance = {'sourceTag': audited['sourceTag'], 'sourceCommit': audited['sourceCommit'],
                      'auditSha256': digest(registry / 'verified-evidence.json')}
        by_name = {Path(record['file']).name: record for record in audited['artifacts']}
        for relative in artifact_paths(release):
            destination = repository / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            record = by_name[Path(relative).name]
            shutil.copyfile(registry / record['file'], destination)
            if digest(destination) != record['sha256']:
                raise ValueError('Registry artifact changed during import')
    data = {'schemaVersion': 1, 'version': release, 'source': source, 'provenance': provenance,
            'files': {relative: {'sha256': digest(repository / relative), 'bytes': (repository / relative).stat().st_size}
                      for relative in artifact_paths(release)}}
    manifest = output / 'publication.json'
    manifest.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
    verify_receipt(manifest)
    return manifest


def accept(output, gradle, maven, maven_repository, gradle_home, registry=None, online_prepare=False):
    """Execute actual profile/C2/C3 flows using only staged packages and fresh consumer projects."""
    import run_selection_workflow
    output = output.resolve()
    if output.exists():
        raise ValueError('Release acceptance output must be fresh')
    manifest = prepare(output, gradle, maven, maven_repository, registry)
    homes = output / 'homes'
    homes.mkdir(exist_ok=True)
    for name in ('settings.xml', 'global-settings.xml'):
        (homes / name).write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>\n', encoding='utf-8')
    seed_downloads(gradle_home, homes / 'gradle', 'gradle')
    seed_downloads(maven_repository, homes / 'maven-repository', 'maven')
    seed_downloads(Path(os.environ.get('MAVEN_USER_HOME', Path.home() / '.m2')), homes / 'maven-user-home', 'maven-wrapper')
    receipt = verify_receipt(manifest)
    for relative in receipt['files']:
        destination = homes / 'maven-repository' / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(manifest.parent / 'repository' / relative, destination)
    # Committed Git input is a separate clean clone, never a source substitution in a consumer build.
    git_data = output / 'git-input'
    launch(['git', '-c', 'core.autocrlf=false', 'clone', '--local', '--shared', str(ROOT), str(git_data)], output / 'git-input.log')
    base = subprocess.check_output(['git', '-C', str(git_data), 'rev-parse', 'HEAD^'], text=True).strip()
    rows = []
    for tool, command in [('gradle', gradle), ('maven', maven)]:
        if tool == 'maven':
            command = [*command, '--settings', str(homes / 'settings.xml'),
                       '--global-settings', str(homes / 'global-settings.xml')]
        environment = dict(os.environ)
        environment['GRADLE_USER_HOME'] = str(homes / 'gradle')
        environment['MAVEN_USER_HOME'] = str(homes / 'maven-user-home')
        for name in list(environment):
            if name.startswith('ORG_GRADLE_PROJECT_') or name in ('GRADLE_OPTS', 'MAVEN_ARGS'):
                environment.pop(name)
        for profile in run_selection_workflow.PROFILES:
            directory = output / (tool + '-' + profile)
            run_selection_workflow.run(tool, directory, command, repository=homes / 'maven-repository', profile=profile,
                    publication=manifest, consumer_environment=environment, git_directory=git_data, git_base=base,
                    online_prepare=online_prepare)
            rows.append(json.loads((directory / 'workflow.json').read_text()))
        for corruption in ('bytes', 'revision'):
            directory = output / (tool + '-invalid-' + corruption)
            run_selection_workflow.run(tool, directory, command, corrupt=corruption, repository=homes / 'maven-repository',
                    publication=manifest, consumer_environment=environment, online_prepare=online_prepare)
            rows.append(json.loads((directory / 'workflow.json').read_text()))
    verify_receipt(manifest)
    parity = []
    for profile in run_selection_workflow.PROFILES:
        decisions = [row for row in rows if row['profile'] == profile and row['corruption'] is None]
        if (len(decisions) != 2 or decisions[0]['decisionIdentity'] != decisions[1]['decisionIdentity']
                or decisions[0]['selectedClasses'] != decisions[1]['selectedClasses']):
            raise ValueError('Fresh Gradle/Maven profile decisions differ')
        parity.append({'profile': profile, 'decisionIdentity': decisions[0]['decisionIdentity'],
                       'selectedClasses': decisions[0]['selectedClasses'], 'status': 'PASSED'})
    report = {'schemaVersion': 1, 'version': receipt['version'], 'status': 'PASSED', 'source': receipt['source'],
              'publicationSha256': digest(manifest), 'cases': rows, 'profilesParity': parity, 'benchmark': False,
              'scope': 'Packaged fresh consumers; candidate mode does not prove public registry availability.'}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--gradle-command-json', required=True)
    parser.add_argument('--maven-command-json', required=True)
    parser.add_argument('--maven-repository', type=Path, required=True)
    parser.add_argument('--gradle-home', type=Path, default=Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')))
    parser.add_argument('--registry-directory', type=Path, help='Previously completed registry/signature/isolation audit')
    parser.add_argument('--online-prepare', action='store_true', help='Allow initial dependency resolution; workers stay offline')
    args = parser.parse_args()
    result = accept(args.output, json.loads(args.gradle_command_json), json.loads(args.maven_command_json),
                    args.maven_repository.resolve(), args.gradle_home.resolve(),
                    args.registry_directory.resolve() if args.registry_directory else None, args.online_prepare)
    print('Accepted ' + result['version'] + ' from ' + result['source'])

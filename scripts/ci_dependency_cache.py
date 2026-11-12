"""Prepare download-only CI snapshots; never share compiled ShardPilot artifacts or credentials."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / 'build/ci-dependencies'
ARCHIVE = ROOT / 'build/ci-dependencies.tar'
DEPENDENCY_PLUGIN = 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
# Only immutable downloads and resolver metadata are shared, never task outputs or settings files.
DOWNLOADS = ('gradle/caches/modules-2', 'gradle/wrapper/dists',
             'testkit/caches/modules-2', 'testkit/wrapper/dists',
             'maven', 'maven-user-home/wrapper/dists')
SNAPSHOTS = {'gradle': DOWNLOADS[:4], 'maven': DOWNLOADS[4:]}


def allowed(name):
    """Use the same allowlist on export and import, rejecting links, traversal and local candidates."""
    parts = Path(name).parts
    if not parts or '..' in parts or Path(name).is_absolute() or '\\' in name \
            or Path(name).as_posix() != name:
        return False
    if not any(name.startswith(prefix + '/') for prefix in DOWNLOADS):
        return False
    if name.startswith('maven/io/github/sjacek/'):
        return False
    return not name.endswith(('.lock', '.lck', '.lastUpdated', '.part', '.tmp'))


def pack(cache=CACHE, archive=ARCHIVE, prefixes=DOWNLOADS):
    """Retain permissions on downloaded launchers without exporting daemon state or signing keys."""
    archive.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, 'w', dereference=True) as output:
        for prefix in prefixes:
            for path in sorted((cache / prefix).rglob('*')):
                relative = path.relative_to(cache).as_posix()
                if path.is_file() and not path.is_symlink() and allowed(relative):
                    output.add(path, arcname=relative, recursive=False)


def restore(cache=CACHE, archive=ARCHIVE, prefixes=DOWNLOADS):
    """A cold run has no snapshot; malformed or unexpected archive entries fail closed."""
    if not archive.exists():
        print('No dependency snapshot; preparing a cold cache.', flush=True)
        return
    with tarfile.open(archive) as source:
        members = source.getmembers()
        if any(not member.isfile() or not allowed(member.name)
               or not any(member.name.startswith(prefix + '/') for prefix in prefixes)
               for member in members):
            raise ValueError('Dependency snapshot contains an unexpected entry')
        source.extractall(cache, members=members, filter='data')


def maven(*arguments):
    """Invoke the checked-in Wrapper with argument boundaries intact, including paths with spaces."""
    wrapper = [str(ROOT / 'mvnw.cmd')] if os.name == 'nt' else ['bash', str(ROOT / 'mvnw')]
    subprocess.run([*wrapper, '-B', '-ntp', '-Dmaven.repo.local=' + str(CACHE / 'maven'),
                    *arguments], cwd=ROOT, check=True)


def warm_maven():
    """Resolve reactor and native-fixture plugins without executing tests or verification hooks."""
    version = ET.parse(ROOT / 'pom.xml').findtext('m:version', namespaces=NS)
    # Fixture plugin resolution needs this checkout's candidate, which pack() explicitly excludes.
    maven('install', '-DskipTests', '-DskipITs')
    maven(DEPENDENCY_PLUGIN + ':go-offline')
    fixtures = ROOT / 'build/cache-warmup/maven'
    providers = set()
    for source in (ROOT / 'shardpilot-maven-plugin/src/it', ROOT / 'examples/maven'):
        for project in sorted(source.iterdir()):
            if not (project / 'pom.xml').is_file():
                continue
            target = fixtures / source.name / project.name
            # Copy POMs only, preserving reactor parents and modules. No source compilation or hooks.
            for pom in sorted(project.rglob('pom.xml')):
                if 'target' in pom.relative_to(project).parts:
                    continue
                destination = target / pom.relative_to(project)
                destination.parent.mkdir(parents=True, exist_ok=True)
                text = pom.read_text(encoding='utf-8').replace('@project.version@', version)
                destination.write_text(text, encoding='utf-8')
                model = ET.fromstring(text)
                properties = {element.tag.rsplit('}', 1)[-1]: element.text
                              for element in model.findall('m:properties/*', NS)}
                for plugin in model.findall('.//m:plugin', NS):
                    artifact = plugin.findtext('m:artifactId', namespaces=NS)
                    if artifact in ('maven-surefire-plugin', 'maven-failsafe-plugin'):
                        plugin_version = plugin.findtext('m:version', namespaces=NS)
                        if plugin_version and plugin_version.startswith('${'):
                            plugin_version = properties[plugin_version[2:-1]]
                        if plugin_version:
                            providers.add(plugin_version)
            maven('-f', str(target / 'pom.xml'), DEPENDENCY_PLUGIN + ':go-offline')
    # Surefire chooses its engine provider at execution time, outside go-offline's normal graph.
    for version in sorted(providers):
        maven(DEPENDENCY_PLUGIN + ':get',
              '-Dartifact=org.apache.maven.surefire:surefire-junit-platform:' + version)


def seed_invoker():
    """Invoker still installs the current reactor into its isolated repository before native tests."""
    shutil.copytree(CACHE / 'maven', ROOT / 'shardpilot-maven-plugin/target/it-repository',
                    dirs_exist_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('restore', 'pack', 'warm-maven', 'seed-invoker'))
    parser.add_argument('kind', nargs='?', choices=('all', 'gradle', 'maven'), default='all')
    options = parser.parse_args()
    if options.command in ('pack', 'restore'):
        kinds = SNAPSHOTS if options.kind == 'all' else (options.kind,)
        for kind in kinds:
            archive = ROOT / 'build' / (kind + '-dependencies.tar')
            {'restore': restore, 'pack': pack}[options.command](CACHE, archive, SNAPSHOTS[kind])
    else:
        {'warm-maven': warm_maven, 'seed-invoker': seed_invoker}[options.command]()


if __name__ == '__main__':
    main()

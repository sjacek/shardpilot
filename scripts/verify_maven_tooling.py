"""Offline native controls prove Site and unknown plugins cannot execute in producer Maven."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

import maven_verification as verification
import maven_tooling


def verify():
    output = verification.ROOT / 'build/dependency-verification'
    output.mkdir(parents=True, exist_ok=True)
    report = output / 'tooling-guard-native.json'
    report.unlink(missing_ok=True)
    env = dict(os.environ)
    if not env.get('SHARDPILOT_MAVEN_VERIFICATION'):
        env = verification.environment(original=env)
    wrapper = [str(verification.ROOT / 'mvnw.cmd')] if os.name == 'nt' else ['bash', str(verification.ROOT / 'mvnw')]
    with tempfile.TemporaryDirectory(prefix='tooling-probe-', dir=output) as temporary:
        project = Path(temporary) / 'pom.xml'
        prefix = '<project><modelVersion>4.0.0</modelVersion><groupId>example.guard</groupId><artifactId>probe</artifactId><version>1</version><packaging>pom</packaging>'
        plain = prefix + '</project>'
        unknown = '<plugin><groupId>example.guard</groupId><artifactId>never-run-plugin</artifactId><version>1</version></plugin>'
        site = ('<plugin><artifactId>maven-site-plugin</artifactId><version>3.22.0</version><executions>'
                '<execution><id>unexpected</id><phase>validate</phase><goals><goal>run</goal></goals></execution>'
                '</executions></plugin>')
        results = {}
        cases = [('positive', plain, ['validate'], False),
                 ('site-phase', plain, ['site'], True),
                 ('site-prefix', plain, ['site:run'], True),
                 ('site-coordinate', plain, ['org.apache.maven.plugins:maven-site-plugin:run'], True),
                 ('site-versioned', plain, ['org.apache.maven.plugins:maven-site-plugin:3.22.0:run'], True),
                 ('unknown-prefix', plain, ['not-reviewed:help'], True),
                 ('unknown-coordinate', plain, ['example.guard:never-run-plugin:1:run'], True),
                 ('unknown-binding', prefix + '<build><plugins>' + unknown + '</plugins></build></project>', ['validate'], True),
                 ('unknown-managed', prefix + '<build><pluginManagement><plugins>' + unknown
                  + '</plugins></pluginManagement></build></project>', ['validate'], True),
                 ('unknown-report', prefix + '<reporting><plugins>' + unknown + '</plugins></reporting></project>', ['validate'], True),
                 ('site-binding', prefix + '<build><plugins>' + site + '</plugins></build></project>', ['validate'], True),
                 ('unknown-profile', prefix + '<profiles><profile><id>tooling-extra-testdata</id><build><plugins>' + unknown
                  + '</plugins></build></profile></profiles></project>', ['-Ptooling-extra-testdata', 'validate'], True),
                 ('environment-site', plain, ['validate'], True)]
        cases.append(('default-site-goal', prefix + '<build><defaultGoal>site:run</defaultGoal></build></project>', [], True))
        for name, model, goals, rejected in cases:
            project.write_text(model, encoding='utf-8')
            command = [*wrapper, '-B', '-ntp', '--offline', '-f', str(project),
                       '-Dmaven.repo.local=' + str(Path(temporary) / 'repository'), *goals]
            selected_env = {**env, 'MAVEN_ARGS': 'site:run'} if name == 'environment-site' else env
            result = subprocess.run(command, cwd=verification.ROOT, env=selected_env, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
            (output / ('tooling-guard-native-' + name + '.log')).write_text(result.stdout, encoding='utf-8')
            if (result.returncode != 0) != rejected or (rejected and 'ShardPilot tooling guard:' not in result.stdout):
                raise ValueError('Native Maven tooling control failed: ' + name)
            results[name] = result.returncode
    jar = maven_tooling.guard_path(verification.ROOT)
    result = {'passed': True, 'offline': True, 'guardSha256': verification.digest(jar),
              'probeSourceSha256': verification.digest(Path(__file__)), 'controls': results}
    report.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return result


if __name__ == '__main__':
    print(json.dumps(verify(), sort_keys=True))

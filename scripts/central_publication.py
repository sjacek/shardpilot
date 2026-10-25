"""Upload once through Maven, then poll Central without repeating an immutable upload.

Only the fixed Central HTTPS endpoint receives credentials. State artifacts contain release
identity, deployment ID and status, never authentication, Maven output or HTTP response bodies.

An upload is deliberately outside the retry loop: a missing response does not prove that the
server rejected the bundle. Recover an accepted upload with its explicit deployment ID. A new
manual dispatch with no ID remains a new upload and requires the maintainer to check Central.
"""
import argparse
import base64
import http.client
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET


CENTRAL = 'https://central.sonatype.com/api/v1/publisher/status'
STATE = Path('build/central-publication/state.json')
STATES = {'PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING', 'PUBLISHED', 'FAILED'}
TRANSIENT = {429, 500, 502, 503, 504}


class PublicationError(RuntimeError):
    """Safe, bounded operational messages, without untrusted response bodies."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Reject redirects even if the target appears trusted; credentials belong to one endpoint."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward a publisher token to a redirect destination.


def deployment_id(value):
    """Validate the operator input before using it in a URL or persistent recovery state."""
    try:
        parsed = str(uuid.UUID(value))
    except (ValueError, AttributeError):
        raise PublicationError('Invalid deployment ID') from None
    if parsed != value:
        raise PublicationError('Deployment ID must use canonical UUID syntax')
    return parsed


def release_identity(environ, pom=Path('pom.xml')):
    """Bind state to the tagged build; the workflow supplies and checks out the tested SHA."""
    version = ET.parse(pom).getroot().findtext('{http://maven.apache.org/POM/4.0.0}version')
    if not version or not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', version):
        raise PublicationError('Expected a stable Maven release version')
    if environ.get('GITHUB_REF') != f'refs/tags/v{version}':
        raise PublicationError('Publication requires the matching immutable version tag')
    sha = environ.get('GITHUB_SHA', '')
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise PublicationError('Missing tested commit SHA')
    return {'version': version, 'tag': f'v{version}', 'commit': sha}


def save(state, path=STATE):
    """Replace the last checkpoint atomically, keeping an earlier complete file on write failure.

    Callers provide only release identity and progress. This is a recovery artifact, not a log
    of Maven output or Central response bodies, which could contain sensitive diagnostics.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def credentials(environ, upload):
    """Build the Central bearer value; status-only recovery needs no private signing material."""
    names = ['MAVEN_CENTRAL_USERNAME', 'MAVEN_CENTRAL_PASSWORD']
    if upload:
        names += ['MAVEN_GPG_KEY', 'MAVEN_GPG_PASSPHRASE']
    if any(not environ.get(name) for name in names):
        raise PublicationError('Missing Maven Central publication credentials')
    return base64.b64encode((environ[names[0]] + ':' + environ[names[1]]).encode()).decode()


def upload_once(settings, state, runner=subprocess.Popen, persist=save):
    """Delegate signing/bundling to the existing profile; Maven stops after accepted upload.

    Persist the accepted ID before checking Maven's exit status: a later process failure must
    not lose the identity of an already accepted bundle. No failure here authorizes a retry.
    """
    command = ['bash', './mvnw', '-B', '-ntp', '-Pcentral-release', 'clean', 'deploy',
               '--settings', str(settings), '-DskipTests', '-DskipITs',
               '-Dcentral.skipUpload=false', '-Dcentral.waitUntil=uploaded']
    child = runner(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    for line in child.stdout:
        # Maven 0.12.0 prints the accepted ID before waiting; persist immediately, not at job exit.
        match = re.search(r'deploymentId:\s*([0-9a-f-]{36})(?![0-9a-f-])', line)
        if match:
            identifier = deployment_id(match[1])
            if state.get('deploymentId') not in (None, identifier):
                raise PublicationError('Maven reported conflicting deployment IDs; inspect Central')
            state.update(deploymentId=identifier, status='UPLOADED')
            persist(state)
        print(line, end='', flush=True)
    result = child.wait()
    if result:
        raise PublicationError('Maven upload failed; inspect Central before any new upload')
    if not state.get('deploymentId'):
        raise PublicationError('Maven returned no deployment ID; inspect Central before any new upload')
    return state['deploymentId']


def unique_fields(pairs):
    """Reject ambiguous JSON rather than silently keeping the last identity or state field."""
    result = {}
    for name, value in pairs:
        if name in result:
            raise PublicationError('Duplicate field in Central status response')
        result[name] = value
    return result


def read_status(identifier, token, timeout, opener):
    """Read one bounded response and check its identity before interpreting release progress."""
    request = urllib.request.Request(CENTRAL + '?id=' + deployment_id(identifier), data=b'', method='POST',
                                     headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/json'})
    with opener.open(request, timeout=timeout) as response:
        payload = response.read(1024 * 1024 + 1)
    if len(payload) > 1024 * 1024:
        raise PublicationError('Oversized Central status response')
    try:
        result = json.loads(payload, object_pairs_hook=unique_fields)
    except (ValueError, UnicodeError):
        raise PublicationError('Invalid Central status response') from None
    if (not isinstance(result, dict) or result.get('deploymentId') != identifier
            or not isinstance(result.get('deploymentState'), str)
            or result['deploymentState'] not in STATES):
        raise PublicationError('Unexpected Central deployment identity or state')
    return result


def verify_release(result, version):
    """Require the named release, and all three Maven coordinates before reporting success.

    Central may return qualified or percent-encoded package URLs. Compare their unqualified
    coordinates; intermediate validation states need not yet include the published inventory.
    """
    if result.get('deploymentName') != f'ShardPilot {version}':
        raise PublicationError('Central deployment belongs to another release')
    if result['deploymentState'] == 'PUBLISHED':
        purls = result.get('purls')
        expected = {f'pkg:maven/io.github.sjacek/{artifact}@{version}' for artifact in
                    ('shardpilot-parent', 'shardpilot-core', 'shardpilot-maven-plugin')}
        if (not isinstance(purls, list) or any(not isinstance(p, str) for p in purls)
                or not expected.issubset({urllib.parse.unquote(p.split('?', 1)[0]) for p in purls})):
            raise PublicationError('Published deployment is missing the expected release coordinates')


def poll(state, token, timeout=1800, clock=time.monotonic, sleep=time.sleep, opener=None, persist=save):
    """Retry only status reads under one monotonic deadline, retaining the accepted upload ID.

    Validation, authentication and malformed responses fail immediately. Transport failures use
    capped backoff; a valid status resets it. VALIDATED still waits for automatic publication.
    Injected clocks, persistence and transport let tests exercise this without Central access.
    """
    # Do not pick up ambient proxy credentials or follow redirects with the publisher token.
    opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    deadline, failures = clock() + timeout, 0
    while True:
        remaining = deadline - clock()
        if remaining <= 0:
            state['status'] = 'WAIT_TIMEOUT'
            persist(state)
            raise PublicationError('Central wait timed out; resume this deployment ID without uploading again')
        delay = 10
        try:
            result = read_status(state['deploymentId'], token, min(30, remaining), opener)
        except urllib.error.HTTPError as error:
            error.close()
            if error.code not in TRANSIENT:
                raise PublicationError(f'Central status HTTP {error.code}; deployment ID retained') from None
            failures += 1
            delay = min(60, 5 * 2 ** min(failures - 1, 4))
            print(f'Transient Central status HTTP {error.code}; retrying the same deployment.', flush=True)
        except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException):
            failures += 1
            delay = min(60, 5 * 2 ** min(failures - 1, 4))
            print('Central status transport failure; retrying the same deployment.', flush=True)
        else:
            verify_release(result, state['version'])
            failures = 0
            state['status'] = result['deploymentState']
            persist(state)
            print('Central deployment ' + state['deploymentId'] + ': ' + state['status'], flush=True)
            if state['status'] == 'PUBLISHED':
                return
            if state['status'] == 'FAILED':
                raise PublicationError('Central rejected the deployment; inspect validation errors in the Portal')
        sleep(min(delay, max(0, deadline - clock())))


def main(argv=None, environ=None):
    """Choose first upload or explicit recovery after validating tag and credential requirements."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--settings', required=True, type=Path)
    args = parser.parse_args(argv)
    environ = os.environ if environ is None else environ
    state = release_identity(environ)
    identifier = environ.get('MAVEN_DEPLOYMENT_ID', '').strip()
    attempt = environ.get('GITHUB_RUN_ATTEMPT', '1')
    # A GitHub rerun starts in a fresh workspace, so local-file detection alone is insufficient.
    if not re.fullmatch(r'[1-9]\d*', attempt) or (int(attempt) > 1 and not identifier):
        raise PublicationError('A rerun requires the accepted deployment ID; do not repeat the upload')
    if not identifier and STATE.exists():
        raise PublicationError('Publication state already exists; resume its deployment ID')
    token = credentials(environ, upload=not identifier)
    if identifier:
        state.update(deploymentId=deployment_id(identifier), status='RESUMING')
    else:
        state['status'] = 'UPLOAD_STARTING'
    save(state)
    # The explicit recovery branch must never reach signing, bundling or Maven deployment.
    if not identifier:
        upload_once(args.settings, state)
    poll(state, token)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (PublicationError, OSError, ET.ParseError) as error:
        # Do not log exception payloads from transport, signing or response parsing.
        message = str(error) if isinstance(error, PublicationError) else type(error).__name__
        print('Central publication failed: ' + message, file=sys.stderr)
        sys.exit(1)

"""Exercise orchestration, real checkpoint files and loopback HTTP without publishing artifacts.

Only the Maven process is simulated. A transport adapter routes the production client's fixed
Central URL to an ephemeral local server; no test endpoint is configurable in production code.
All credentials below are fixture values, and no test reads the operator's environment secrets.
"""
from collections import deque
import contextlib
import http.server
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch
import urllib.parse
import urllib.request

import central_publication as central
from test_central_publication import Clock, IDENTIFIER, VERSION, response


@contextlib.contextmanager
def checkout():
    """Isolate the real relative POM and checkpoint paths, restoring cwd even after failure."""
    previous = Path.cwd()
    with tempfile.TemporaryDirectory() as directory:
        os.chdir(directory)
        try:
            Path('pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
                                      f'<version>{VERSION}</version></project>', encoding='utf-8')
            yield
        finally:
            os.chdir(previous)


@contextlib.contextmanager
def publisher_http(replies):
    """Serve a finite status sequence; record any attempted redirect or unexpected request."""
    queue, requests = deque(replies), []

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            requests.append({'method': self.command, 'path': self.path,
                             'authorization': self.headers.get('Authorization')})
            code, payload, headers = queue.popleft() if queue else (500, {}, {})
            body = json.dumps(payload).encode()
            self.send_response(code)
            for name, value in headers.items():
                # A redirect stays on loopback even if the production guard regresses.
                self.send_header(name, value.replace('LOCAL_ORIGIN', origin))
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        do_GET = do_POST

        def log_message(self, format, *args):
            pass  # Keep fixture HTTP logs out of publication output assertions.

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    origin = f'http://127.0.0.1:{server.server_port}'
    direct = urllib.request.build_opener(urllib.request.ProxyHandler({}), central.NoRedirect())

    class LoopbackTransport:
        def open(self, request, timeout):
            if request.full_url != central.CENTRAL + '?id=' + IDENTIFIER:
                raise AssertionError('Publisher changed the fixed Central URL or deployment ID')
            source = urllib.parse.urlsplit(request.full_url)
            local = urllib.request.Request(origin + source.path + '?' + source.query,
                                           data=request.data, headers=dict(request.header_items()),
                                           method=request.method)
            return direct.open(local, timeout=timeout)

    thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.02}, daemon=True)
    thread.start()
    try:
        yield LoopbackTransport(), requests
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
        if thread.is_alive():
            raise AssertionError('HTTP fixture did not stop')


def environment():
    return {'GITHUB_REF': 'refs/tags/v' + VERSION, 'GITHUB_SHA': 'b' * 40,
            'GITHUB_RUN_ATTEMPT': '1', 'MAVEN_CENTRAL_USERNAME': 'fixture-user',
            'MAVEN_CENTRAL_PASSWORD': 'fixture-password', 'MAVEN_GPG_KEY': 'fixture-key',
            'MAVEN_GPG_PASSPHRASE': 'fixture-passphrase'}


@contextlib.contextmanager
def boundaries(opener, timeout=120):
    """Keep real orchestration and parsing; substitute Maven execution and wall-clock waits."""
    upload_once, poll = central.upload_once, central.poll
    child = Mock(stdout=io.StringIO('Uploaded bundle successfully, deploymentId: ' + IDENTIFIER + '\n'))
    child.wait.return_value = 0
    runner = Mock(return_value=child)

    def status(state, token):
        clock = Clock()
        return poll(state, token, timeout=timeout, clock=clock, sleep=clock.sleep, opener=opener)

    with patch.object(central, 'upload_once', side_effect=lambda settings, state:
                      upload_once(settings, state, runner=runner)) as upload, \
            patch.object(central, 'poll', side_effect=status):
        yield upload, runner


class CentralPublicationIntegrationTest(unittest.TestCase):
    def test_first_upload_retries_http_status_and_persists_the_complete_release_identity(self):
        replies = [(503, {'error': 'fixture-private-body'}, {})] + [
            (200, response(status), {}) for status in
            ('PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING', 'PUBLISHED')]
        output = io.StringIO()
        with checkout(), publisher_http(replies) as (opener, requests), \
                boundaries(opener) as (upload, runner), contextlib.redirect_stdout(output):
            self.assertEqual(central.main(['--settings', 'settings.xml'], environment()), 0)
            recorded = json.loads(central.STATE.read_text(encoding='utf-8'))
            self.assertEqual(recorded, {'version': VERSION, 'tag': 'v' + VERSION,
                                       'commit': 'b' * 40, 'deploymentId': IDENTIFIER, 'status': 'PUBLISHED'})
            upload.assert_called_once()
            runner.assert_called_once()
            self.assertEqual(len(requests), 6)
            self.assertTrue(all(item['method'] == 'POST' for item in requests))
            self.assertTrue(all(item['path'].endswith('?id=' + IDENTIFIER) for item in requests))
            expected_auth = 'Bearer ' + central.credentials(environment(), upload=False)
            self.assertTrue(all(item['authorization'] == expected_auth for item in requests))
            self.assertNotIn(expected_auth, output.getvalue())
            self.assertNotIn('fixture-', output.getvalue() + json.dumps(recorded))

    def test_timeout_checkpoint_can_be_resumed_with_no_second_maven_process_or_signing_key(self):
        replies = [(200, response('PUBLISHING'), {}), (200, response('PUBLISHING'), {}),
                   (200, response(), {})]
        with checkout(), publisher_http(replies) as (opener, requests), \
                boundaries(opener, timeout=15) as (upload, runner):
            with self.assertRaisesRegex(central.PublicationError, 'timed out'):
                central.main(['--settings', 'settings.xml'], environment())
            checkpoint = json.loads(central.STATE.read_text(encoding='utf-8'))
            self.assertEqual(checkpoint['status'], 'WAIT_TIMEOUT')
            self.assertEqual(checkpoint['deploymentId'], IDENTIFIER)
            upload.assert_called_once()
            runner.assert_called_once()
            upload.reset_mock()
            runner.reset_mock()
            resume = {key: value for key, value in environment().items() if not key.startswith('MAVEN_GPG_')}
            resume.update(MAVEN_DEPLOYMENT_ID=checkpoint['deploymentId'], GITHUB_RUN_ATTEMPT='2')
            self.assertEqual(central.main(['--settings', 'unused-settings.xml'], resume), 0)
            upload.assert_not_called()
            runner.assert_not_called()
            self.assertEqual(json.loads(central.STATE.read_text())['status'], 'PUBLISHED')
            self.assertEqual(len(requests), 3)

    def test_real_http_redirect_is_rejected_without_forwarding_the_publisher_token(self):
        replies = [(302, {}, {'Location': 'LOCAL_ORIGIN/forbidden'}), (200, response(), {})]
        with checkout(), publisher_http(replies) as (opener, requests), \
                boundaries(opener) as (_, runner):
            with self.assertRaisesRegex(central.PublicationError, 'HTTP 302'):
                central.main(['--settings', 'settings.xml'], environment())
            self.assertEqual(len(requests), 1, 'the redirect target must never receive a request')
            runner.assert_called_once()
            checkpoint = json.loads(central.STATE.read_text())
            self.assertEqual(checkpoint['deploymentId'], IDENTIFIER)
            self.assertEqual(checkpoint['status'], 'UPLOADED')


if __name__ == '__main__':
    unittest.main()

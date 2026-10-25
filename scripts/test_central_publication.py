"""Offline publisher regressions. No test can contact Central or invoke a real Maven upload."""
import contextlib
import base64
import http.client
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import urllib.error

import central_publication as central


IDENTIFIER = 'efd8b6c5-f1c4-4325-af44-dd91fd75db79'
VERSION = '0.3.1'


def state():
    return {'version': VERSION, 'tag': 'v' + VERSION, 'commit': 'a' * 40, 'deploymentId': IDENTIFIER}


def response(status='PUBLISHED', **overrides):
    value = {'deploymentId': IDENTIFIER, 'deploymentName': 'ShardPilot ' + VERSION,
             'deploymentState': status, 'purls': [f'pkg:maven/io.github.sjacek/{a}@{VERSION}' for a in
                 ('shardpilot-parent', 'shardpilot-core', 'shardpilot-maven-plugin')]}
    value.update(overrides)
    return value


class Clock:
    """Advance only when the poller sleeps, making deadline and backoff assertions deterministic."""
    now = 0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class CentralPublicationTest(unittest.TestCase):
    """Failure-policy tests inject both transport and process boundaries; no remote side effects."""

    def poll(self, replies, timeout=120):
        clock, saved = Clock(), []
        current = state()
        with patch.object(central, 'read_status', side_effect=replies) as read:
            central.poll(current, 'private-auth-value', timeout, clock, clock.sleep,
                         opener=Mock(), persist=lambda item: saved.append(dict(item)))
        return current, read, saved

    def test_502_and_transport_failures_retry_status_for_the_same_deployment(self):
        current, read, saved = self.poll([
            urllib.error.HTTPError('https://central.invalid', 502, 'private-body', {}, None),
            urllib.error.URLError('private-error'), response('PUBLISHING'), response()])
        self.assertEqual(current['status'], 'PUBLISHED')
        self.assertEqual(read.call_count, 4)
        self.assertTrue(all(call.args[0] == IDENTIFIER for call in read.call_args_list))
        self.assertNotIn('private', json.dumps(saved))

    def test_retryable_http_statuses_eventually_publish(self):
        for code in central.TRANSIENT:
            with self.subTest(code=code):
                error = urllib.error.HTTPError('https://central.invalid', code, 'private', {}, None)
                self.assertEqual(self.poll([error, response()])[0]['status'], 'PUBLISHED')

    def test_authentication_failure_is_not_retried_and_body_is_not_exposed(self):
        for code in (401, 403, 404):
            output = io.StringIO()
            with self.subTest(code=code), contextlib.redirect_stdout(output), \
                    patch.object(central, 'read_status', side_effect=urllib.error.HTTPError(
                        'https://private.invalid', code, 'private token', {}, io.BytesIO(b'private body'))) as read:
                with self.assertRaisesRegex(central.PublicationError, f'HTTP {code}') as failure:
                    central.poll(state(), 'private token', opener=Mock(), persist=Mock())
                read.assert_called_once()
                self.assertNotIn('private', str(failure.exception) + output.getvalue())

    def test_failed_validation_cannot_become_a_success(self):
        with self.assertRaisesRegex(central.PublicationError, 'rejected'):
            self.poll([response('FAILED', errors={'private': 'untrusted output'})])

    def test_wrong_release_and_incomplete_published_cohort_are_rejected(self):
        for reply in (response(deploymentName='ShardPilot 9.9.9'), response(purls=[]),
                      response(purls=['pkg:maven/other/artifact@0.3.1'])):
            with self.subTest(reply=reply), self.assertRaises(central.PublicationError):
                self.poll([reply])

    def test_timeout_retains_id_and_does_not_invoke_upload(self):
        clock, current, saved = Clock(), state(), []
        with patch.object(central, 'read_status', return_value=response('PUBLISHING')), \
                patch.object(central, 'upload_once') as upload:
            with self.assertRaisesRegex(central.PublicationError, 'resume'):
                central.poll(current, 'private-token', 15, clock, clock.sleep, Mock(),
                             lambda item: saved.append(dict(item)))
        upload.assert_not_called()
        self.assertEqual(saved[-1]['status'], 'WAIT_TIMEOUT')
        self.assertEqual(saved[-1]['deploymentId'], IDENTIFIER)

    def test_maven_is_invoked_once_and_id_is_persisted_before_process_exit(self):
        current, saved = {'version': VERSION}, []
        child = Mock(stdout=io.StringIO('Uploaded bundle successfully, deploymentId: ' + IDENTIFIER + '\n'))
        child.wait.side_effect = lambda: 0 if saved else self.fail('ID was not persisted before wait')
        runner = Mock(return_value=child)
        self.assertEqual(central.upload_once(Path('settings.xml'), current, runner,
                                           lambda item: saved.append(dict(item))), IDENTIFIER)
        runner.assert_called_once()
        command = runner.call_args.args[0]
        self.assertIn('-Dcentral.skipUpload=false', command)
        self.assertIn('-Dcentral.waitUntil=uploaded', command)

    def test_maven_failure_or_missing_id_never_retries_upload(self):
        for result in (0, 1):
            child = Mock(stdout=io.StringIO('Build output without accepted deployment ID\n'))
            child.wait.return_value = result
            runner = Mock(return_value=child)
            with self.subTest(result=result), self.assertRaises(central.PublicationError):
                central.upload_once(Path('settings.xml'), {}, runner, persist=Mock())
            runner.assert_called_once()

    def test_conflicting_ids_fail_closed(self):
        child = Mock(stdout=io.StringIO('deploymentId: ' + IDENTIFIER + '\n'
                                       'deploymentId: 12345678-1234-1234-1234-123456789abc\n'))
        with self.assertRaisesRegex(central.PublicationError, 'conflicting'):
            central.upload_once(Path('settings.xml'), {}, Mock(return_value=child), persist=Mock())

    def test_accepted_id_survives_a_later_maven_failure(self):
        current, saved = {'version': VERSION}, []
        child = Mock(stdout=io.StringIO('deploymentId: ' + IDENTIFIER + '\n'))
        child.wait.return_value = 1
        runner = Mock(return_value=child)
        with self.assertRaisesRegex(central.PublicationError, 'inspect Central'):
            central.upload_once(Path('settings.xml'), current, runner,
                                lambda item: saved.append(dict(item)))
        runner.assert_called_once()
        self.assertEqual(saved[-1]['deploymentId'], IDENTIFIER)

    def test_repeated_transport_errors_have_a_bounded_wait_and_retain_the_id(self):
        clock, current, saved = Clock(), state(), []
        with patch.object(central, 'read_status', side_effect=TimeoutError) as read:
            with self.assertRaisesRegex(central.PublicationError, 'timed out'):
                central.poll(current, 'private-token', 100, clock, clock.sleep, Mock(),
                             lambda item: saved.append(dict(item)))
        self.assertEqual(clock.now, 100)
        self.assertLess(read.call_count, 10)
        self.assertEqual(saved[-1]['deploymentId'], IDENTIFIER)
        self.assertEqual(saved[-1]['status'], 'WAIT_TIMEOUT')

    def test_http_client_uses_fixed_https_post_and_rejects_wrong_identity(self):
        opener, http = Mock(), Mock()
        http.read.return_value = json.dumps(response()).encode()
        # Magic methods require a context manager rather than an unrestricted Mock.
        opener.open.return_value = contextlib.nullcontext(http)
        self.assertEqual(central.read_status(IDENTIFIER, 'secret', 12, opener)['deploymentState'], 'PUBLISHED')
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, central.CENTRAL + '?id=' + IDENTIFIER)
        self.assertEqual(request.method, 'POST')
        self.assertEqual(opener.open.call_args.kwargs['timeout'], 12)
        self.assertEqual(request.get_header('Authorization'), 'Bearer secret')
        for payload in (b'{invalid', b'{"deploymentState":"PUBLISHED","deploymentState":"FAILED"}',
                        json.dumps(response(deploymentId='wrong')).encode(), b'x' * (1024 * 1024 + 1)):
            opener.open.return_value = contextlib.nullcontext(http)
            http.read.return_value = payload
            with self.subTest(payload_size=len(payload)), self.assertRaises(central.PublicationError):
                central.read_status(IDENTIFIER, 'secret', 12, opener)

    def test_status_rejects_invalid_shapes_and_unknown_states_before_publication_checks(self):
        for value in (None, [], 'PUBLISHED', response(deploymentState=None),
                      response(deploymentState=[]), response(deploymentState='FUTURE_STATE'),
                      response(deploymentId=None)):
            http, opener = Mock(), Mock()
            http.read.return_value = json.dumps(value).encode()
            opener.open.return_value = contextlib.nullcontext(http)
            with self.subTest(value=value), self.assertRaises(central.PublicationError):
                central.read_status(IDENTIFIER, 'test-token', 1, opener)

    def test_published_inventory_accepts_qualified_encoded_purls_in_any_order(self):
        inventory = [p.replace('@', '%40') + '?type=pom' for p in reversed(response()['purls'])]
        central.verify_release(response(purls=inventory), VERSION)
        # Validation can complete before Central reports the final published inventory.
        central.verify_release(response('VALIDATED', purls=None), VERSION)

    def test_published_inventory_requires_each_coordinate_at_the_requested_version(self):
        inventory = response()['purls']
        for missing in range(len(inventory)):
            incomplete = inventory[:missing] + inventory[missing + 1:]
            with self.subTest(missing=missing), self.assertRaises(central.PublicationError):
                central.verify_release(response(purls=incomplete), VERSION)
        for invalid in (None, {}, [None], [p.replace('@0.3.1', '@0.3.0') for p in inventory]):
            with self.subTest(invalid=invalid), self.assertRaises(central.PublicationError):
                central.verify_release(response(purls=invalid), VERSION)

    def test_backoff_is_capped_and_resets_after_a_valid_intermediate_status(self):
        clock, sleeps = Clock(), []

        def sleep(seconds):
            sleeps.append(seconds)
            clock.sleep(seconds)

        failures = [urllib.error.URLError('private') for _ in range(7)]
        replies = failures + [response('VALIDATED'), TimeoutError(), response()]
        with patch.object(central, 'read_status', side_effect=replies):
            central.poll(state(), 'test-token', 400, clock, sleep, Mock(), Mock())
        self.assertEqual(sleeps, [5, 10, 20, 40, 60, 60, 60, 10, 5])

    def test_request_timeout_and_sleep_do_not_exceed_remaining_deadline(self):
        clock = Clock()
        with patch.object(central, 'read_status', side_effect=TimeoutError) as read:
            with self.assertRaisesRegex(central.PublicationError, 'timed out'):
                central.poll(state(), 'test-token', 12, clock, clock.sleep, Mock(), Mock())
        self.assertEqual([call.args[2] for call in read.call_args_list], [12, 7])
        self.assertEqual(clock.now, 12)

    def test_truncated_http_response_retries_the_same_deployment_without_logging_transport_details(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            current, read, _ = self.poll([http.client.IncompleteRead(b'private response'), response()])
        self.assertEqual(current['status'], 'PUBLISHED')
        self.assertEqual(read.call_count, 2)
        self.assertNotIn('private', output.getvalue())

    def test_redirects_are_never_followed_with_the_token(self):
        self.assertIsNone(central.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.invalid'))

    def test_only_matching_release_tag_and_valid_sha_are_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            pom = Path(directory) / 'pom.xml'
            pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><version>0.3.1</version></project>')
            valid = {'GITHUB_REF': 'refs/tags/v0.3.1', 'GITHUB_SHA': 'a' * 40}
            self.assertEqual(central.release_identity(valid, pom)['version'], VERSION)
            for environ in ({}, {**valid, 'GITHUB_REF': 'refs/heads/main'}, {**valid, 'GITHUB_SHA': 'bad'}):
                with self.assertRaises(central.PublicationError):
                    central.release_identity(environ, pom)

    def test_unstable_or_ambiguous_release_versions_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            pom = Path(directory) / 'pom.xml'
            for version in ('', '01.3.1', '0.3.1-SNAPSHOT', '0.3.1-rc1', '0.3.1+build', '0.3'):
                pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
                               f'<version>{version}</version></project>', encoding='utf-8')
                with self.subTest(version=version), self.assertRaises(central.PublicationError):
                    central.release_identity({'GITHUB_REF': 'refs/tags/v' + version,
                                              'GITHUB_SHA': 'a' * 40}, pom)

    def test_noncanonical_deployment_ids_are_rejected(self):
        for identifier in ('', 'not-an-id', IDENTIFIER.upper(), IDENTIFIER.replace('-', ''),
                           IDENTIFIER + '/redirect', ' ' + IDENTIFIER):
            with self.subTest(identifier=identifier), self.assertRaises(central.PublicationError):
                central.deployment_id(identifier)

    def test_credentials_require_every_upload_secret_but_only_the_token_for_resume(self):
        env = {'MAVEN_CENTRAL_USERNAME': 'fixture-user', 'MAVEN_CENTRAL_PASSWORD': 'test-token',
               'MAVEN_GPG_KEY': 'test-key', 'MAVEN_GPG_PASSPHRASE': 'test-passphrase'}
        self.assertEqual(base64.b64decode(central.credentials(env, upload=True)).decode(),
                         'fixture-user:test-token')
        for name in env:
            with self.subTest(name=name), self.assertRaises(central.PublicationError):
                central.credentials({**env, name: ''}, upload=True)
        token_only = {name: value for name, value in env.items() if name.startswith('MAVEN_CENTRAL_')}
        self.assertEqual(central.credentials(token_only, upload=False), central.credentials(env, upload=True))
        for name in token_only:
            with self.subTest(resume_missing=name), self.assertRaises(central.PublicationError):
                central.credentials({**token_only, name: ''}, upload=False)

    def test_resume_bypasses_maven_and_requires_only_central_token(self):
        env = {'MAVEN_CENTRAL_USERNAME': 'name', 'MAVEN_CENTRAL_PASSWORD': 'private',
               'MAVEN_DEPLOYMENT_ID': IDENTIFIER, 'GITHUB_RUN_ATTEMPT': '2'}
        with patch.object(central, 'release_identity', return_value={'version': VERSION}), \
                patch.object(central, 'save'), patch.object(central, 'poll') as poll, \
                patch.object(central, 'upload_once') as upload:
            self.assertEqual(central.main(['--settings', 'settings.xml'], env), 0)
        upload.assert_not_called()
        self.assertEqual(poll.call_args.args[0]['deploymentId'], IDENTIFIER)

    def test_first_attempt_uploads_once_then_polls_its_accepted_id(self):
        env = dict.fromkeys(('MAVEN_CENTRAL_USERNAME', 'MAVEN_CENTRAL_PASSWORD',
                             'MAVEN_GPG_KEY', 'MAVEN_GPG_PASSPHRASE'), 'test-only')
        saved = []
        with patch.object(central, 'release_identity', return_value={'version': VERSION}), \
                patch.object(Path, 'exists', return_value=False), \
                patch.object(central, 'save', side_effect=lambda item: saved.append(dict(item))), \
                patch.object(central, 'upload_once',
                             side_effect=lambda settings, item: item.update(deploymentId=IDENTIFIER)) as upload, \
                patch.object(central, 'poll') as poll:
            self.assertEqual(central.main(['--settings', 'isolated-settings.xml'], env), 0)
        upload.assert_called_once()
        self.assertEqual(upload.call_args.args[0], Path('isolated-settings.xml'))
        self.assertEqual(saved[0]['status'], 'UPLOAD_STARTING')
        self.assertEqual(poll.call_args.args[0]['deploymentId'], IDENTIFIER)

    def test_existing_state_prevents_a_new_upload_without_resume_id(self):
        with patch.object(central, 'release_identity', return_value={'version': VERSION}), \
                patch.object(Path, 'exists', return_value=True), \
                patch.object(central, 'upload_once') as upload:
            with self.assertRaisesRegex(central.PublicationError, 'state already exists'):
                central.main(['--settings', 'settings.xml'], {})
        upload.assert_not_called()

    def test_rerun_without_id_and_invalid_resume_id_cannot_upload(self):
        for env in ({'GITHUB_RUN_ATTEMPT': '2'}, {'MAVEN_DEPLOYMENT_ID': 'bad-id',
                     'MAVEN_CENTRAL_USERNAME': 'name', 'MAVEN_CENTRAL_PASSWORD': 'private'}):
            with patch.object(central, 'release_identity', return_value={'version': VERSION}), \
                    patch.object(central, 'upload_once') as upload:
                with self.assertRaises(central.PublicationError):
                    central.main(['--settings', 'settings.xml'], env)
                upload.assert_not_called()

    def test_missing_signing_credentials_fail_before_first_upload(self):
        with patch.object(central, 'release_identity', return_value={'version': VERSION}), \
                patch.object(Path, 'exists', return_value=False), \
                patch.object(central, 'upload_once') as upload, patch.object(central, 'save') as save:
            with self.assertRaisesRegex(central.PublicationError, 'credentials'):
                central.main(['--settings', 'settings.xml'],
                             {'MAVEN_CENTRAL_USERNAME': 'name', 'MAVEN_CENTRAL_PASSWORD': 'private'})
        upload.assert_not_called()
        save.assert_not_called()

    def test_state_contains_no_credentials_and_replaces_earlier_status(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'state.json'
            central.save({**state(), 'status': 'PUBLISHING'}, path)
            central.save({**state(), 'status': 'PUBLISHED'}, path)
            self.assertEqual(json.loads(path.read_text())['status'], 'PUBLISHED')
            self.assertFalse(path.with_suffix('.tmp').exists())

    def test_atomic_replace_failure_preserves_the_previous_complete_checkpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'nested' / 'state.json'
            central.save({**state(), 'status': 'UPLOADED'}, path)
            previous = path.read_bytes()
            with patch.object(Path, 'replace', side_effect=OSError('fixture write failure')):
                with self.assertRaises(OSError):
                    central.save({**state(), 'status': 'PUBLISHED'}, path)
            self.assertEqual(path.read_bytes(), previous)
            self.assertEqual(json.loads(previous)['deploymentId'], IDENTIFIER)


if __name__ == '__main__':
    unittest.main()

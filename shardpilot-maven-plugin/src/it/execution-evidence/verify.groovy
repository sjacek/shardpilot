// Real Maven invocations exercise descriptor injection, offline execution and failure propagation.
def failed = new File(basedir, 'target/native-failed.json').text
assert failed.contains('"status":"FAILED"')
assert failed.contains('NATIVE_PROCESS_FAILED')
assert failed.contains('"nativeProcess":"FAILED"')
assert new File(basedir, 'target/native-failed.html').text.contains('execution evidence: FAILED')
assert new File(basedir, 'target/failsafe.json').text.contains('"status":"PASSED"')
assert new File(basedir, 'target/failsafe.html').text.contains('execution evidence: PASSED')
assert new File(basedir, 'target/surefire.json').text.contains('"totals":null')
assert new File(basedir, 'target/surefire.json').text.contains('INVALID_EVIDENCE')
assert !new File(basedir, 'target/classes').exists()
assert !new File(basedir, 'target/test-classes').exists()
assert !new File(basedir, 'target/surefire-reports').exists()
assert !new File(basedir, 'target/failsafe-reports').exists()
assert !new File(basedir, 'target/shardpilot').exists()
return true

def success = new File(basedir, 'target/diagnostics/success/report.json').text
def failed = new File(basedir, 'target/diagnostics/failed/report.json').text
assert success.contains('"status":"passed"')
assert failed.contains('"status":"failed"')
assert failed.contains('"exitCode":7')
assert failed.contains('NATIVE_PROCESS_FAILED')
assert new File(basedir, 'target/diagnostics/success/report.html').isFile()
assert new File(basedir, 'target/diagnostics/failed/report.html').isFile()
assert !success.contains('PRIVATE_') && !failed.contains('PRIVATE_')
// The failed prepare invocation must leave the last valid private configuration intact.
def settings = new groovy.json.JsonSlurper().parse(new File(basedir, 'target/shardpilot/diagnostics-config.json'))
assert settings.maxSamples == 10000
assert settings.identity.attempt == 1
assert new File(basedir, 'build.log').text.contains('C4 INVALID_LIMITS')
return true

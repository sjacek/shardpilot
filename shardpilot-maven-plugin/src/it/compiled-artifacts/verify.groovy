// Both rejected invocations must stop before a native JVM can append another marker.
assert new File(basedir, 'native-runs.txt').readLines() == ['surefire', 'failsafe']
assert new File(basedir, 'target/shardpilot/compiled-artifacts.json').isFile()
assert new File(basedir, 'target/surefire-reports/TEST-example.ExampleTest.xml').isFile()
assert new File(basedir, 'target/failsafe-reports/TEST-example.ExampleIT.xml').isFile()
def log = new File(basedir, 'build.log').text
assert log.contains('C3 REVISION_MISMATCH')
assert log.contains('C3 MANIFEST_DIGEST_MISMATCH')
return true

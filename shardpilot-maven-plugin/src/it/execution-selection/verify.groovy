import groovy.json.JsonSlurper

// Failed replay invalidates the generated executable document; failed aggregation replaces PASSED.
assert !new File(basedir, 'target/execution-selection.json').exists()
def report = new JsonSlurper().parse(new File(basedir, 'target/execution-evidence.json'))
assert report.schemaVersion == 2
assert report.status == 'FAILED'
assert report.selectionProvenance == null
assert report.reasons == ['COMPILED_ARTIFACT_MISMATCH']
assert report.totals == null
assert new File(basedir, 'target/execution-evidence.html').text.contains('execution evidence: FAILED')
assert !new File(basedir, 'target/classes').exists()
assert !new File(basedir, 'target/test-classes').exists()
assert !new File(basedir, 'target/surefire-reports').exists()
assert !new File(basedir, 'target/failsafe-reports').exists()
def selection = new JsonSlurper().parse(new File(basedir, 'target/no-c3.json'))
assert selection.schemaVersion == 2
assert selection.provenance.suite == 'profile-smoke-testdata'
assert selection.provenance.compiledArtifactsSha256 == null
return true

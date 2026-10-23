assert new File(basedir, 'target/shardpilot/unit/coverage.html').text.contains('Verification: FAILED')
assert new File(basedir, 'target/shardpilot/unit/coverage.json').text.contains('baseline.yaml')
assert !new File(basedir, 'target/test-classes').exists()
return true

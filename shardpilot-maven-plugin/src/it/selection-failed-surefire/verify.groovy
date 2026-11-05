def root = new File(basedir, 'target/shardpilot/unit/selections/smoke')
assert new File(root, 'actual/actual-1.yaml').text.contains('example.GammaTest')
assert new File(root, 'actual/selection-1.id').text == new File(root, 'plan/selection.id').text
assert new File(root, 'coverage.json').text.contains('"status":"passed"')
// The independent native gate failed even though candidate coverage is complete.
assert new File(root, 'test-results/1/shard-1/TEST-example.GammaTest.xml').text.contains('failures="1"')
return true

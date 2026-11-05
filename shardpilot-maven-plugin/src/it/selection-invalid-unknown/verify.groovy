def root = new File(basedir, 'target/shardpilot/unit/selections/smoke')
assert new File(root, 'selection/selection.json').text.contains('"status":"failed"')
assert !new File(root, 'selection/selected.yaml').exists()
assert !new File(root, 'actual/actual-1.yaml').exists()
assert !new File(root, 'test-results').exists()
return true

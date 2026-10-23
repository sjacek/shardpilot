assert new File(basedir, 'target/shardpilot/unit/actual/actual-2.yaml').text.contains('classes: []')
assert new File(basedir, 'target/shardpilot/unit/excludes.txt').text.contains('**/*')
assert new File(basedir, 'target/shardpilot/unit/coverage.html').text.contains('disabled (plan only)')
assert !new File(basedir, 'target/surefire-reports/TEST-example.OnlyTest.xml').exists()
return true

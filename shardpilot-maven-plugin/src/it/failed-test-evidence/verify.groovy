assert new File(basedir, 'target/shardpilot/unit/plan/planned-1.yaml').text == new File(basedir, 'target/shardpilot/unit/actual/actual-1.yaml').text
assert new File(basedir, 'target/shardpilot/unit/coverage.html').text.contains('Verification: PASSED')
assert new File(basedir, 'target/surefire-reports/TEST-example.OnlyTest.xml').text.contains('<failure')
return true

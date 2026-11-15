def reports = new File(basedir, 'target/shardpilot/unit/selections/profile-smoke-testdata/test-results/1')
assert reports.listFiles().count { it.name.startsWith('TEST-') && it.name.endsWith('.xml') } == 2
return true

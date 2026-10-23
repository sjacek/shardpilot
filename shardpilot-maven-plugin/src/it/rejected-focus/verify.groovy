assert !new File(basedir, 'target/shardpilot/unit/actual').exists()
assert new File(basedir, 'build.log').text.contains('overrides native class exclusions')
return true

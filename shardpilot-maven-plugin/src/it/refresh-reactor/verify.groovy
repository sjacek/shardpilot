def histories = [:]
['first', 'second'].each { String module ->
    def target = new File(basedir, module + '/target')
    def values = [:]
    new File(target, 'shardpilot/unit/durations.tsv').readLines('UTF-8')
            .findAll { it && !it.startsWith('#') }.each { String line ->
        def fields = line.split('\t')
        values[fields[0]] = Double.parseDouble(fields[1])
    }
    histories[module] = values
    def json = new File(target, 'shardpilot/unit/durations.json').getText('UTF-8')
    ['"status":"PASSED"', '"eligible":2', '"coverage":0.5', '"shardCount":2',
     '"measured":1', '"replaced":1', '"kept":1', '"dropped":1'].each { assert json.contains(it) }
    assert !new File(target, 'test-classes').exists()
    assert !new File(target, 'surefire-reports').exists()
    assert !new File(target, 'shardpilot/unit/plan').exists()
    // Both source fixtures use the checkout's line endings, so compare their complete text.
    assert new File(basedir, module + '/history.tsv').getText('UTF-8') ==
            new File(basedir, module + '/history-original.tsv').getText('UTF-8')
}
assert histories.first == ['example.SharedTest': 1.3d, 'example.KeptTest': 2.0d]
assert histories.second == ['example.SharedTest': 7.8d, 'example.KeptTest': 4.0d]
assert !new File(basedir, 'target/shardpilot').exists()
assert !new File(basedir, 'target/test-classes').exists()
assert new File(basedir, 'build.log').text.contains('MultiThreadedBuilder')
return true

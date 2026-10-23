import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

assert !new File(basedir, 'target/shardpilot').exists()
['first', 'second'].each { module ->
    def read = { String path ->
        new Load(LoadSettings.builder().build()).loadFromString(new File(basedir, "$module/target/shardpilot/unit/$path").text).classes as Set
    }
    assert read('plan/baseline.yaml') == ['example.OneTest', 'example.TwoTest'] as Set
    def union = [] as Set
    (1..2).each { index ->
        def actual = read("actual/actual-${index}.yaml")
        assert actual.size() == 1
        assert actual == read("plan/planned-${index}.yaml")
        assert actual.intersect(union).isEmpty()
        union.addAll(actual)
        def reports = new File(basedir, "$module/target/surefire-reports/shard-$index").listFiles().findAll { it.name.startsWith('TEST-') }
        assert reports.size() == 1
        assert reports[0].text.contains('tests="1"')
    }
    assert union.size() == 2
    assert new File(basedir, "$module/target/shardpilot/unit/coverage.html").text.contains('Verification: PASSED')
}
return true

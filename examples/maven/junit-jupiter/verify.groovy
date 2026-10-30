import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

import javax.xml.parsers.DocumentBuilderFactory
import groovy.json.JsonSlurper
import java.math.RoundingMode

// Invoker runs this hook after both native nodes and artifact-only refresh goals. Native XML
// supplies the timing oracle; matching only candidate class names would miss double-counted
// nested suites, incorrect rounding and reports accidentally mixed between unit/integration.
def xmlFactory = DocumentBuilderFactory.newInstance()
xmlFactory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)

def yaml = { String path ->
    def document = new Load(LoadSettings.builder().build()).loadFromString(new File(basedir, path).getText('UTF-8'))
    assert document.schemaVersion == 1
    document.classes as Set
}
Map<String, Set<String>> expected = [unit: ['example.AlphaTest', 'example.BetaTest', 'example.GammaSpec'] as Set<String>,
                                   integration: ['example.AlphaIT', 'example.BetaIT'] as Set<String>]
for (Map.Entry<String, Set<String>> entry : expected.entrySet()) {
    String suite = entry.key
    Set<String> classes = entry.value
    def baseline = yaml("target/shardpilot/$suite/plan/baseline.yaml")
    assert baseline == classes
    def union = [] as Set
    (1..2).each { index ->
        def planned = yaml("target/shardpilot/$suite/plan/planned-${index}.yaml")
        def actual = yaml("target/shardpilot/$suite/actual/actual-${index}.yaml")
        assert planned == actual
        assert planned.intersect(union).isEmpty()
        union.addAll(planned)
    }
    assert union == baseline
    assert new File(basedir, "target/shardpilot/$suite/coverage.json").text.contains('"status":"passed"')
    def html = new File(basedir, "target/shardpilot/$suite/coverage.html").getText('UTF-8')
    assert html.contains('Verification: PASSED')
    classes.each { assert html.contains(it) }
    // The documented consumer flow refreshes both suite namespaces without adopting the candidate.
    def refresh = new JsonSlurper().parse(new File(basedir, "target/shardpilot/$suite/durations.json"))
    assert refresh.status == 'PASSED'
    assert refresh.measured == classes.size()
    assert refresh.eligible == classes.size()
    assert refresh.coverage == 1.0
    assert refresh.shardCount == 2
    assert refresh.pruning == 'baseline'
    assert refresh.kept == 0
    assert refresh.replaced == classes.size()
    assert refresh.dropped == 5 - classes.size() // Shared old TSV contains both independent suites.
    assert refresh.foreign == 0
    assert refresh.ignored.values().every { it == 0 }
    assert refresh.diagnostics.isEmpty()
    assert refresh.recordedLoad.previous.size() == 2
    assert refresh.recordedLoad.refreshed.size() == 2

    String runner = suite == 'unit' ? 'surefire' : 'failsafe'
    Map<String, BigDecimal> nativeTimes = [:]
    new File(basedir, "target/$runner-reports").eachFileRecurse { file ->
        if (file.name.startsWith('TEST-') && file.name.endsWith('.xml')) {
            def xml = xmlFactory.newDocumentBuilder().parse(file).documentElement
            String name = xml.getAttribute('name')
            assert !nativeTimes.containsKey(name), 'Two nodes must not measure the same suite'
            // Surefire's AlphaTest measurement already includes its nested testcase time.
            nativeTimes.put(name, new BigDecimal(xml.getAttribute('time')).max(new BigDecimal('0.1'))
                    .setScale(1, RoundingMode.HALF_UP))
        }
    }
    Map<String, BigDecimal> candidate = [:]
    new File(basedir, "target/shardpilot/$suite/durations.tsv").readLines('UTF-8')
            .findAll { !it.isBlank() && !it.startsWith('#') }.each { line ->
        String[] fields = line.split('\t', -1)
        assert fields.length == 2
        assert !candidate.containsKey(fields[0]), 'Candidate families must be unique'
        assert fields[1] ==~ /\d+\.\d/, 'The default precision is one fractional digit'
        candidate.put(fields[0], new BigDecimal(fields[1]))
    }
    assert candidate.keySet() == classes
    assert nativeTimes.keySet() == classes
    assert candidate == nativeTimes
    assert refresh.measuredSuites == nativeTimes.size()
}
def methods = []
['surefire', 'failsafe'].each { runner ->
    (1..2).each { index ->
        new File(basedir, "target/$runner-reports/shard-$index").eachFileMatch(~/TEST-.*\.xml/) { file ->
            def cases = xmlFactory.newDocumentBuilder().parse(file).getElementsByTagName('testcase')
            (0..<cases.length).each { number ->
                def test = cases.item(number)
                methods << test.getAttribute('classname') + '#' + test.getAttribute('name')
            }
        }
    }
}
assert methods.size() == 6
assert methods.toSet().size() == 6
assert methods.any { it.contains('NestedTests#nested') }
assert !methods.any { it.contains('ForbiddenTest') }
// These are the synthetic maintained weights, not the just-measured candidate values.
assert new File(basedir, 'durations.tsv').readLines('UTF-8') == [
        'example.AlphaTest\t10', 'example.BetaTest\t6', 'example.GammaSpec\t4',
        'example.AlphaIT\t8', 'example.BetaIT\t2']
return true

import java.math.RoundingMode
import javax.xml.parsers.DocumentBuilderFactory

// Native XML is the oracle: nested Maven times are inclusive and must be counted once.
def factory = DocumentBuilderFactory.newInstance()
factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
def expected = [:]
int nativeCases = 0
new File(basedir, 'target/surefire-reports').eachFileRecurse { file ->
    if (file.name.startsWith('TEST-') && file.name.endsWith('.xml')) {
        def suite = factory.newDocumentBuilder().parse(file).documentElement
        def name = suite.getAttribute('name')
        if (!name.contains('Disabled')) {
            assert !expected.containsKey(name)
            expected[name] = new BigDecimal(suite.getAttribute('time')).max(new BigDecimal('0.001'))
                    .setScale(3, RoundingMode.HALF_UP).doubleValue()
            nativeCases += suite.getElementsByTagName('testcase').length
        }
    }
}
assert nativeCases == 3
assert expected.keySet() == ['example.AlphaTest', 'example.BetaTest'] as Set
expected['example.DisabledTest'] = 5.0

def history = { String name ->
    def result = [:]
    new File(basedir, name).readLines('UTF-8').findAll { it && !it.startsWith('#') }.each { line ->
        def parts = line.split('\t')
        result[parts[0]] = Double.parseDouble(parts[1])
    }
    result
}
assert history('target/shardpilot/unit/durations.tsv') == expected
assert history('target/collected.tsv') == expected
def json = new File(basedir, 'target/shardpilot/unit/durations.json').getText('UTF-8')
['"status":"PASSED"', '"measured":2', '"measuredSuites":2', '"kept":1', '"replaced":2',
 '"dropped":1', '"eligible":3', '"skipped":1', '"shardCount":2', '"pruning":"baseline"',
 '"recordedLoad":', '"imbalance":'].each { assert json.contains(it) }
assert new File(basedir, 'target/collected.json').getText('UTF-8') == json
assert history('target/unpruned.tsv')['example.OldTest'] == 7.0
assert new File(basedir, 'target/unpruned.json').text.contains('"pruning":"disabled"')
assert new File(basedir, 'target/unpruned.json').text.contains('"eligible":null')

assert !new File(basedir, 'target/rejected.tsv').exists()
def rejected = new File(basedir, 'target/rejected.json').getText('UTF-8')
assert rejected.contains('"status":"FAILED"')
assert rejected.contains('"reason":"MINIMUM_COVERAGE"')
assert rejected.contains('"measured":2')
// Eight invocations include five refreshes, two rejected; only native shards may write markers.
assert new File(basedir, 'target/executions.txt').readLines('UTF-8').size() == 3
assert new File(basedir, 'history.tsv').getText('UTF-8') == new File(basedir, 'history-original.tsv').getText('UTF-8')
assert !new File(basedir, 'target/shardpilot/integration/durations.tsv').exists()
return true

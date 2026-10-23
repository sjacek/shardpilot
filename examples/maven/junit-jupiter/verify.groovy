import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

import javax.xml.parsers.DocumentBuilderFactory

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
}
def methods = []
['surefire', 'failsafe'].each { runner ->
    (1..2).each { index ->
        new File(basedir, "target/$runner-reports/shard-$index").eachFileMatch(~/TEST-.*\.xml/) { file ->
            def factory = DocumentBuilderFactory.newInstance()
            factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
            def cases = factory.newDocumentBuilder().parse(file).getElementsByTagName('testcase')
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
return true

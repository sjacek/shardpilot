import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

import javax.xml.parsers.DocumentBuilderFactory

def yaml = { String path ->
    def document = new Load(LoadSettings.builder().build()).loadFromString(new File(basedir, path).getText('UTF-8'))
    assert document.schemaVersion == 1
    document.classes as Set
}
def expected = ['example.AlphaSpec', 'example.BetaSpec'] as Set
assert yaml('target/custom evidence/plan/baseline.yaml') == expected
def union = [] as Set
(1..2).each { index ->
    def planned = yaml("target/custom evidence/plan/planned-${index}.yaml")
    def actual = yaml("target/custom evidence/actual/actual-${index}.yaml")
    assert !planned.isEmpty()
    assert actual == planned
    assert planned.intersect(union).isEmpty()
    union.addAll(planned)
}
assert union == expected
assert new File(basedir, 'target/custom reports/selection.json').text.contains('"status":"passed"')
def html = new File(basedir, 'target/custom reports/selection.html').getText('UTF-8')
assert html.contains('Verification: PASSED')
expected.each { assert html.contains(it) }
assert !new File(basedir, 'target/shardpilot').exists()
assert !new File(basedir, 'target/custom evidence/coverage.html').exists()

// Native XML proves that both jobs actually executed the selected methods without duplication.
def methods = []
(1..2).each { index ->
    new File(basedir, "target/surefire-reports/shard-$index").eachFileMatch(~/TEST-.*\.xml/) { file ->
        def factory = DocumentBuilderFactory.newInstance()
        factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
        def cases = factory.newDocumentBuilder().parse(file).getElementsByTagName('testcase')
        (0..<cases.length).each { number ->
            def test = cases.item(number)
            methods << test.getAttribute('classname') + '#' + test.getAttribute('name')
        }
    }
}
assert methods.toSet() == ['example.AlphaSpec#alpha', 'example.AlphaSpec$NestedTests#nested', 'example.BetaSpec#beta'] as Set
assert methods.size() == 3
assert new File(basedir, 'config/excludes.txt').text.contains('**/ForbiddenSpec.java')
return true

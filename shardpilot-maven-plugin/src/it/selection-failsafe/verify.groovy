import groovy.json.JsonSlurper
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import javax.xml.parsers.DocumentBuilderFactory
import java.security.MessageDigest
import java.util.HexFormat

def root = new File(basedir, 'target/shardpilot/integration/selections/smoke')
def yaml = { String name -> new Load(LoadSettings.builder().build()).loadFromString(new File(root, name).getText('UTF-8')).classes as Set }
def expected = ['example.AlphaIT', 'example.AlphaIT$Nested', 'example.BetaIT'] as Set
assert yaml('plan/baseline.yaml') == expected
assert yaml('selection/eligible.yaml').size() == 4
assert yaml('selection/selected.yaml') == expected
assert yaml('plan/planned-1.yaml') == ['example.AlphaIT', 'example.AlphaIT$Nested'] as Set
assert yaml('plan/planned-2.yaml') == ['example.BetaIT'] as Set
(1..2).each { index ->
    assert yaml("actual/actual-${index}.yaml") == yaml("plan/planned-${index}.yaml")
    assert new File(root, "actual/selection-${index}.id").text == new File(root, 'plan/selection.id').text
}
def report = new JsonSlurper().parse(new File(root, 'selection/selection.json'))
// Verify both semantic selection and exact input provenance after a refused native override.
assert report.suite == 'smoke'
assert report.shardCount == 2
assert report.requestedClasses == 2
assert report.manifestSha256 == HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(new File(basedir, 'selection.yaml').bytes))
assert report.eligibleClasses == 4
assert report.selectedClasses == 3
assert report.familyExpansions == ['example.AlphaIT']
assert report.estimatedSeconds == 50
assert new File(root, 'coverage.html').text.contains('Verification: PASSED')
assert new File(root, 'selection/selection.html').text.contains('Candidate selection does not prove execution success')
// Read native XML: candidate manifests alone cannot prove selected methods ran.
(1..2).each { count ->
    def methods = []
    (1..count).each { index ->
        new File(root, "test-results/${count}/shard-${index}").eachFileMatch(~/TEST-.*\.xml/) { file ->
            def factory = DocumentBuilderFactory.newInstance()
            factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
            def cases = factory.newDocumentBuilder().parse(file).getElementsByTagName('testcase')
            (0..<cases.length).each { number ->
                def test = cases.item(number)
                methods << test.getAttribute('classname') + '#' + test.getAttribute('name')
            }
        }
    }
    assert methods.toSet() == ['example.AlphaIT#alpha', 'example.AlphaIT$Nested#nested', 'example.BetaIT#beta'] as Set
    assert methods.size() == 3
}
// The eighth invocation is expected to fail specifically at ShardPilot's bypass guard.
assert new File(basedir, 'build.log').getText('UTF-8').contains('Focused -Dit.test')
assert !new File(root.parentFile.parentFile, 'coverage.json').exists()
return true

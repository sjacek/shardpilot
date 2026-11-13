import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

// Inspect the actual consumer's native XML, independently of its candidate manifests.
def directory = new File(basedir, 'target/shardpilot/unit/selections/smoke')
def xmlFactory = DocumentBuilderFactory.newInstance()
xmlFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
xmlFactory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
xmlFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, '')
xmlFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, '')
def expected = ['example.selection.AlphaTest', 'example.selection.BetaTest'] as Set
def readClasses = { String path ->
    new Load(LoadSettings.builder().build()).loadFromString(new File(directory, path).text).classes as Set
}
assert readClasses('selection/eligible.yaml').size() == 3
assert readClasses('plan/baseline.yaml') == expected
def executed = []
(1..2).each { index ->
    assert readClasses("plan/planned-${index}.yaml") == readClasses("actual/actual-${index}.yaml")
    def reports = new File(directory, "test-results/$index").listFiles().findAll { it.name.startsWith('TEST-') && it.name.endsWith('.xml') }
    assert reports.size() == 1
    reports.each { report ->
        def xml = xmlFactory.newDocumentBuilder().parse(report).documentElement
        assert xml.getAttribute('failures') == '0'
        assert xml.getAttribute('errors') == '0'
        assert xml.getAttribute('skipped') == '0'
        def cases = xml.getElementsByTagName('testcase')
        (0..<cases.length).each { position -> executed.add(cases.item(position).getAttribute('classname')) }
    }
}
assert executed.size() == 2
assert executed.toSet() == expected
assert new File(directory, 'coverage.html').text.contains('Verification: PASSED')
return true

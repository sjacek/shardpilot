import groovy.json.JsonSlurper
import javax.xml.parsers.DocumentBuilderFactory

// Native XML, independently of selected manifests, must prove the same exact three classes once.
def expected = ['example.selection.AlphaTest', 'example.selection.BetaTest', 'example.selection.GammaTest'] as Set
def factory = DocumentBuilderFactory.newInstance()
factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
['runtime', 'integration-runtime'].each { suite ->
    def directory = new File(basedir, "target/$suite/selections/profile-impact-testdata")
    def executed = []
    (1..2).each { node ->
        new File(directory, "test-results/$node").listFiles().findAll { it.name.startsWith('TEST-') && it.name.endsWith('.xml') }.each { file ->
            def xml = factory.newDocumentBuilder().parse(file).documentElement
            assert xml.getAttribute('failures') == '0'
            assert xml.getAttribute('errors') == '0'
            assert xml.getAttribute('skipped') == '0'
            def cases = xml.getElementsByTagName('testcase')
            (0..<cases.length).each { index -> executed.add(cases.item(index).getAttribute('classname')) }
        }
    }
    assert executed.size() == 3
    assert executed.toSet() == expected
    def decision = new File(directory, 'selection/decision/decision.json').text
    assert decision.contains('CHANGED_TEST') && decision.contains('DEPENDENT_MODULE') && decision.contains('REQUIRED_CONTRACT')
    def git = new JsonSlurper().parse(new File(directory, 'selection/decision/git.json'))
    assert git.contracts.size() == 2
    assert git.contracts.values().every { it.failure == 'NONE' && it.pointers == ['/paths/~1payments/summary'] }
    assert new File(directory, 'coverage.html').text.contains('Verification: PASSED')
}
return true

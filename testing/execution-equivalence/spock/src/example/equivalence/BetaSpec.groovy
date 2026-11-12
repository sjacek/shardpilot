package example.equivalence

import org.opentest4j.TestAbortedException
import spock.lang.Specification
import spock.lang.Unroll

// Spock features require instance methods; the native execution-equivalence test verifies discovery.
@SuppressWarnings('GrMethodMayBeStatic')
class BetaSpec extends Specification {
    @Unroll
    def 'repetition #value'() {
        expect: value > 0
        where: value << [1, 2]
    }

    def 'runtime abort'() {
        when: abortAtRuntime()
        then: true
    }

    def 'intentional failure'() { expect: false }

    // Let the abort escape the feature: asserting thrown(...) would turn it into a passing test.
    private static void abortAtRuntime() {
        throw new TestAbortedException('equivalence fixture: expected runtime abort')
    }
}

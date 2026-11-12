package example.equivalence

import spock.lang.Specification

// Spock features require instance methods; the native execution-equivalence test verifies discovery.
@SuppressWarnings('GrMethodMayBeStatic')
class DeltaSpec extends Specification {
    def 'error'() {
        when: failUnexpectedly()
        then: true
    }

    def 'intentional failure'() { expect: false }

    // An uncaught runtime exception must remain an ERROR in native evidence, not an expected pass.
    private static void failUnexpectedly() {
        throw new IllegalStateException('equivalence fixture: expected test error')
    }
}

package example.equivalence

import spock.lang.Ignore
import spock.lang.Specification

// Spock features require instance methods; the native execution-equivalence test verifies discovery.
@SuppressWarnings('GrMethodMayBeStatic')
class GammaSpec extends Specification {
    @Ignore('equivalence fixture: expected discovery-time skip')
    def 'disabled'() { expect: false }

    def 'ordinary'() { expect: true }

    def 'intentional failure'() { expect: false }
}

package example.equivalence

import spock.lang.Specification
import spock.lang.Unroll

// Spock features require instance methods; the native execution-equivalence test verifies discovery.
@SuppressWarnings('GrMethodMayBeStatic')
class AlphaSpec extends Specification {
    def 'ordinary'() { expect: true }

    def 'another ordinary test'() { expect: true }

    @Unroll
    def 'row #iterationIndex: #value'() {
        expect: !value.isBlank()
        where: value << ['same', 'same', 'other']
    }

    def 'intentional failure'() { expect: false }
}

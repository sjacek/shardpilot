import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

// Planning acceptance is separate from the CI driver's native process/C2/C3 acceptance.
def snapshot = new File(basedir, 'target/pilot/selections/profile-smoke-testdata/selection')
def parser = new Load(LoadSettings.builder().build())
assert parser.loadFromString(new File(snapshot, 'eligible.yaml').text).classes.size() == 3
assert parser.loadFromString(new File(snapshot, 'selected.yaml').text).classes as Set ==
        ['example.workflow.LoginTest', 'example.workflow.PaymentTest'] as Set
assert !new File(snapshot.parentFile, 'test-results').exists()
return true

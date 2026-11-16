# Complete selection and execution example

This development example selects two of three native JUnit tests, freezes the decision,
runs two workers and checks the original results together with compiled-file integrity.

See the [complete workflow guide](../../../docs/selection-migration.md) for the stage
commands, local candidate installation, CI trust boundary and rejection scenarios.
The shared policy and native sources match the Gradle example; no consumer selector callback
or application-specific selection script is used.

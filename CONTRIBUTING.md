# Contributing to ShardPilot

ShardPilot is MIT-licensed. Only submit code and examples that you have the right to contribute.
Preserve third-party attribution and license requirements; do not copy application-specific code,
credentials, customer data, private repository addresses or captured application logs into fixtures.

## Local checks

Run `./gradlew check assemble` (or `gradlew.bat check assemble` on Windows) before proposing a change.
Functional tests launch real Gradle TestKit builds and fetch fixture dependencies from Maven Central.

Keep the implementation framework-neutral. Do not introduce a coordinator service, automatic retries,
runtime test dependencies, implicit subproject configuration, or a CI-provider dependency into the core.

Any assignment change needs regression coverage proving determinism and complete, non-overlapping
class ownership. Changes to Gradle integration need TestKit coverage with strict configuration cache,
existing test filters, task opt-in and failed test propagation. Update both Groovy and Kotlin DSL examples
when changing the public extension. Do not advertise a compatibility combination without testing it.

History is untrusted metadata: reject malformed numbers and identities, and do not print raw lines.
If XML history import is added, disable DTDs and external entities and read timing metadata only.

Public repository ownership, release credentials and publication approval remain with the maintainer.

## Documentation changes

Start at the [documentation index](docs/README.md). Keep examples aligned with the public extension
and distinguish implemented behavior from proposed work. When changing the architecture or assignment
algorithm, update the related PlantUML source and regenerate its SVG preview; see the
[diagram maintenance instructions](docs/diagrams/README.md). Do not edit generated SVGs by hand.

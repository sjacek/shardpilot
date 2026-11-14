# Configurable test selection: target design

**Product direction confirmed on 14 November 2026. Core engine implemented in the development
checkout; complete adapter/Git integration is planned and not available in 0.5.0.**

ShardPilot will own the complete selection mechanism: discover eligible tests, obtain the
change set, evaluate configured rules, apply mandatory tests and budgets, produce an immutable
selection, assign shards and explain the decision. A consumer configures the Gradle/Maven
plugin and keeps no selection algorithm in a Python, shell or custom build-script function.

The [0.5.0 selection feature](test-selection.md) already validates and executes a supplied
class manifest. Generating that selection from configuration is the extension implemented first
in the [shared core policy contract](selection-policy.md). Build-tool parameter wiring remains planned.
The existing manifest remains a supported explicit-input mode and a useful interchange
format. Automatic modes generate their manifest internally.

## What the consumer configures

The public configuration must express application knowledge as data. Package names, source
roots, contract locations and mandatory test names belong in configuration rather than in
ShardPilot's implementation. A new project must work without modifying the plugin.

| Configuration area | Required capabilities |
| --- | --- |
| Native suite | Gradle `Test` owner or Maven Surefire/Failsafe suite; eligible class roots and native includes/excludes. |
| Profiles | `full`, `smoke`, `daily`/affected and existing explicit-manifest selection; named consumer profiles reuse these modes. |
| Mandatory tests | Exact classes, packages or patterns; always included in the chosen profile and validated against eligibility. |
| Impact rules | Changed paths/packages/modules/resources/contracts map to test classes/packages/patterns or a full-run decision. |
| Structured contracts | Generic JSON/YAML pointer rules for scoped contract changes; GraphQL/protobuf/module changes can use configured path/module rules. Unknown structure changes follow the configured fallback. |
| Git inputs | Explicit base/head overrides and supported CI-provider inference; merge-request history, pushes, renames, deletions, new branches and shallow checkouts. |
| Budgets | Estimated seconds, family count, unknown-duration estimate, priorities and explicit overflow behavior. |
| Fallbacks | Full execution or a failed decision when change provenance/impact is unknown; explicit handling of documentation-only and empty diffs. |
| Execution gates | Native failures, required families, minimum invocation counts, skip policy and retained evidence through C2. |
| Output | Standard selection/plan artifacts and JSON/HTML showing inclusion, deferral, fallback and budget reasons. |

The configuration surface should have safe defaults and a small common starting point:
suite, profile, mandatory patterns, impact rules and an optional budget. Advanced Git,
contract and prioritization settings are optional. Publish equivalent Gradle Groovy/Kotlin
DSL and Maven POM/property examples. A shared declarative policy file may hold larger rule
sets; it is plugin configuration, not a user-maintained executable selector.

Exact DSL/property names and policy schema must be specified and tested before implementation
is released. This design does not introduce runnable configuration examples for 0.5.0.

## One engine, build-tool adapters

`shardpilot-core` owns deterministic policy evaluation, class-family normalization,
mandatory-set validation, rule precedence, budget decisions and reason codes. It receives
normalized changes, full eligibility, configuration and timing history as inputs. It does
not depend on GitLab, GitHub, Gradle, Maven or application classes.

Adapters acquire those inputs, resolve native suite configuration and apply the resulting
selection to native test tasks. Reusable CI/Git input support belongs in ShardPilot's
distribution, with explicit overrides for local and other CI environments. The consumer
does not implement merge-base discovery or parse Git output in its own scripts.

Native frameworks continue to discover and execute tests. Support must cover Gradle
Jupiter/Spock/TestNG and Maven Surefire/Failsafe with explicit runner/version fixtures.
Tags/groups stay native filters configured through supported adapter parameters. Contradictory
focused filters or an unexpected empty native execution must fail instead of silently
reducing the selected suite.

CI launches the requested jobs and publishes retained artifacts. Infrastructure preparation,
credentials and application fixtures remain consumer configuration. ShardPilot's selection
engine must be usable in another repository with another CI provider and different names.

## Decision pipeline

1. Resolve the native suite and establish full eligibility **E**, independently of selection.
2. Resolve one profile and validate the complete declarative policy before reading changes.
3. Bind the change set to exact base/head commits; read NUL-delimited Git records and both
   old/new paths for renames/deletions. Never narrow an MR to its last commit to fit a budget.
4. Evaluate mandatory tests, directly changed eligible tests and all applicable impact rules.
   Define deterministic union/priority behavior; a full-run rule takes precedence.
5. Apply the profile's budget to explicitly optional additions. Mandatory targets and targets
   marked required by a rule remain protected. The report lists every deferred optional family.
6. Expand selected nested classes to eligible outer families, validate **S ⊆ E**, and freeze
   the selected manifest and decision identity before any shard starts.
7. Reuse the existing history-based assignment and selected-suite namespaces on every node.
8. Validate retained native execution separately through C2; publish selection and execution
   reports with matching identities and independently supplied expected values.

A configured target outside E is an invalid configuration, including a mandatory class that
was renamed without updating the policy. Do not silently remove it. Exact changed tests,
resource/contract rules and optional representative probes need explicit, documented precedence.
Broad rules must not accidentally suppress a more specific rule's required targets.

## Budgets and safe defaults

Timing history estimates selection cost; it is not a promise about wall-clock duration.
JVM/container/application startup, concurrency and unmeasured cases affect actual runtime.
Configure a finite positive estimate for unmeasured families and report which estimates were
substituted. Mandatory tests count toward both time and family limits.

An exceeded budget never silently removes protected tests. The consumer chooses an explicit
overflow policy, such as failing planning or retaining the protected set with an exceeded-budget
diagnostic. A full-run fallback remains visible and may exceed a short-profile budget.

Unknown changed paths, unavailable Git ancestry and unsupported contract changes use an explicit
FULL-or-FAIL policy. Invalid configuration and unreadable/corrupt supplied evidence fail.
An empty diff or documentation-only change follows the selected profile's declared policy;
it must not become an accidental zero-test success. Keep the full-suite gate independent of
short-profile completion and preserve native failures.

Git input acquisition must validate refs and subprocess arguments, sanitize diagnostics and
avoid exposing remote URLs containing credentials. Fetching is an explicit setting, disabled
for offline evaluation; missing local history follows the declared fallback. A planning job
freezes its inputs once rather than letting each shard query mutable branch tips separately.

## Explainability and provenance

For each family, the decision report identifies the rule/profile that selected it, whether
it was mandatory, its measured/estimated cost and any explicit deferral. It records exact
base/head revisions, engine version, policy/history/eligibility hashes, selected-family identity,
fallback reasons and budget totals. Do not include method parameters, captured test output,
credentials or arbitrary Git error payloads.

Identical normalized inputs must produce identical selected families, ordering, reasons and
identity in Gradle and Maven. A changed policy must invalidate the decision even when it happens
to choose the same families. Bind the retained decision to plan/selection identities and the
[C2 evidence contract](execution-evidence-contract.md) with reviewed versioned contract changes.
The existing C2 draft does not yet define a complete automatic-selection provenance envelope.

## Delivery and acceptance

The complete configurable selector is an intended product capability, replacing the previous
conditional C5 investigation. C1 provides timing inputs; C2 provides execution evidence. C3/C4
are separate integrity/diagnostic improvements and do not need to block selector development.

Deliver the mechanism in reviewable increments:

1. Define the common policy contract and implement core profiles, protected targets, rule
   evaluation, history budgets, deterministic fallback and explanation reports.
2. Add Git/CI change acquisition, path/module/structured-contract rules and local overrides
   in the shipped adapters/support layer.
3. Expose equivalent Gradle/Maven parameters, generate selection internally and connect the
   existing assignment/native runner/evidence flows. Preserve explicit-manifest compatibility.
4. Compare consumer configurations against their existing selectors on frozen changes,
   eligibility and histories. Keep application-specific classes out of portable fixtures.
5. Publish, verify fresh consumers from registries, then migrate each service to declarative
   configuration and remove its selector scripts after acceptance.

Acceptance includes multi-commit MRs, new branches, merge commits, missing/shallow Git history,
renames/deletions, paths with spaces, overlapping rules, unknown paths/contracts, mandatory
targets outside eligibility, nested families, unknown durations, budget overflow, deterministic
optional deferral and empty-result policies. Both adapters must select the same family set
and preserve failure/skip gates. A generic example project must work using configuration
alone; no application-specific selector callback is required for a documented mode.

The shared policy contract, profiles, path rules, protected-family budgets and decision reports
are implemented and tested in core. Next come change acquisition and equivalent adapter parameters,
coordinated with C2's validator contract. Structured module/contract inference, automatic directly
changed-test discovery and native adapter wiring remain outside this first implementation stage.

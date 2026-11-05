# Consumer CI: collect reports and refresh history

[CI guide](../../docs/ci.md#refresh-history-from-collected-reports) ·
[History options](../../docs/history-refresh.md)

The two independent GitLab CI examples show **three required shards**, selection verification
and candidate history generation. Copy one into a configured consumer repository and adapt its
task/module paths, image, wrapper requirements and artifact retention to that project:

- [Gradle](gradle/.gitlab-ci.yml): existing `test` task, opt-in verification with count 3
  and `history { reports.setFrom(layout.projectDirectory.dir('collected/test')) }`.
- [Maven](maven/.gitlab-ci.yml): one module with Surefire selection wiring and maintained
  `durationsFile`. Refresh explicitly reads the collected report tree; AUTO uses the prepared baseline.

Each adapter has an independent directory with the standard `.gitlab-ci.yml` filename.
Copy the chosen file to the consuming repository's CI configuration; the two examples are
alternatives with their own job names. Short local `needs: [plan, test]` references use
[GitLab's default artifact downloads](https://docs.gitlab.com/ci/yaml/#needsartifacts)
(`artifacts: true`), including all parallel test nodes. All referenced jobs are defined in
the same document; no external CI include is needed to resolve them.

IntelliJ's pre-commit analysis can report these local `needs` as undefined even when
the open-file inspection resolves them. Each affected `needs` has a narrow
`#noinspection UndefinedJob` comment for that inspection only. It does not change
GitLab behavior or disable other YAML checks. `HistoryRefreshCiExamplesTest` parses
both complete documents and verifies every referenced job, the required gates and
artifact transport. Changing filenames or switching between short and long `needs`
syntax does not address this pre-commit false positive.

Both assume shell tools `bash`, `cp`, `mkdir` and wrapper download prerequisites are available
in the job image. They illustrate the CI DAG and artifact layout, not host provisioning. No
Docker service or publisher credentials are needed. Maven reactors retain module directories;
Failsafe uses separate `integration` plans/reports and `runner=FAILSAFE`.

Planning artifacts are uploaded once. Every test node uploads only its uniquely named actual
manifest and XML under `collected/<suite>/shard-N/`. Downloading all parallel artifacts therefore
keeps equal XML filenames in separate directories. Aggregators never run `clean` or producers.
`when: always` schedules diagnostics after failures; `artifacts: when: always` retains the
result even if its script fails. Neither sets `allow_failure`, masks a native error or adopts
the candidate. A missing runner/upload may leave incomplete evidence; both gates must remain
required and a refresh coverage threshold is not a provenance guarantee.

These examples do not publish artifacts to GitHub/portals, contact private infrastructure or
modify the consuming repository's versioned history. Review TSV/JSON before a separate adoption
commit. Existing selection HTML can use the [GitLab MR link pattern](../../docs/shard-verification.md#finding-a-class-assignment-in-gitlab).

`HistoryRefreshCiExamplesTest` parses both files in the plugin's Gradle `check`: every node and
gate stays required, parallel XML keeps indexed paths, aggregation downloads plan/all-node evidence,
native producers are absent from aggregation commands and coverage/candidate files remain distinct.
These are structural checks, separate from live execution in a consuming GitLab project.

If coverage is green but refresh fails, compare the XML download set independently from actual
manifests. Use the [recovery procedure](../../docs/history-refresh.md#recover-a-missing-shard-download)
to restore the original cohort. `minimumCoverage` is measured-family coverage, so a lower
threshold can accept partial measurements; it does not establish complete artifact delivery.

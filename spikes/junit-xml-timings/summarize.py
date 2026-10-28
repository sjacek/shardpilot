#!/usr/bin/env python3
"""F0 experiment only: export allowlisted timing metadata from our generated XML fixtures.

Run after the standalone Maven Invoker and Gradle TestKit builds. Raw XML remains in
ignored target/build directories: properties, output and failure messages are never
copied into the shareable summary. This is not the production JUnit XML reader.
"""

import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


SUITE_ATTRIBUTES = ("name", "tests", "skipped", "failures", "errors", "time")
CASE_ATTRIBUTES = ("name", "classname", "time")
RESULT_TAGS = ("failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError")


def summarize_xml(path):
    """Keep counters and durations, excluding potentially sensitive report payloads."""
    root = ET.parse(path).getroot()
    suites = []
    for suite in root.iter("testsuite"):
        cases = []
        for case in suite.findall("testcase"):
            record = {key: case.get(key) for key in CASE_ATTRIBUTES}
            record["results"] = [
                {"tag": child.tag, "time": child.get("time")}
                for child in case if child.tag in RESULT_TAGS
            ]
            cases.append(record)
        suites.append({
            "attributes": {key: suite.get(key) for key in SUITE_ATTRIBUTES},
            "testcaseSeconds": round(sum(float(case["time"] or 0) for case in cases), 6),
            "testcases": cases,
        })
    return {"file": path.name, "root": root.tag, "suites": suites}


def summarize_run(name, reports, attempts, phases=None):
    """Refuse incomplete experiments instead of producing an apparently empty observation."""
    paths = sorted(reports.glob("TEST-*.xml"))
    if not paths:
        raise ValueError(f"Missing XML reports for {name}; run the corresponding spike build")
    times = []
    if attempts is not None:
        for line in attempts.read_text(encoding="utf-8").splitlines():
            number, seconds = line.split("\t")
            times.append({"attempt": int(number), "bodySeconds": float(seconds)})
        if [item["attempt"] for item in times] != [1, 2]:
            raise ValueError(f"Expected exactly one failed attempt and one retry for {name}")
    result = {"name": name, "attempts": times, "reports": [summarize_xml(path) for path in paths]}
    if phases is not None:
        result["phases"] = []
        for line in phases.read_text(encoding="utf-8").splitlines():
            phase, seconds = line.split("\t")
            result["phases"].append({"name": phase, "bodySeconds": float(seconds)})
    return result


def collect(spike, include_gradle_gate=False):
    runs = []
    for clone in ("it", "it-no-retry"):
        target = spike / "maven-invoker/target" / clone / "jupiter/target"
        for runner in ("surefire", "failsafe"):
            runs.append(summarize_run(
                f"maven-{runner}-reruns-{'1' if clone == 'it' else '0'}",
                target / f"{runner}-reports",
                target / f"{runner}-attempts.txt" if clone == "it" else None,
            ))
    for merge in ("false", "true"):
        build = spike / "gradle-testkit/build/probes" / f"merge-{merge}" / "build"
        runs.append(summarize_run(f"gradle-merge-{merge}", build / "test-results/test", build / "attempts.txt"))
    if include_gradle_gate:
        gate = spike.parents[1] / "build/spikes/junit-xml-timings"
        for fixture in ("jupiter-spock", "testng"):
            reports = gate / fixture
            runs.append(summarize_run(f"gradle-gate-{fixture}", reports, None, reports / "timing-phases.tsv"))
    return {"schemaVersion": 1, "runs": runs}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--include-gradle-gate", action="store_true",
                        help="Include F0.1–F0.2 evidence retained by ShardPilotFunctionalTest")
    arguments = parser.parse_args()
    summary = collect(Path(__file__).resolve().parent, arguments.include_gradle_gate)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    print(f"Summarized {len(summary['runs'])} completed experiment variants")


if __name__ == "__main__":
    main()

import groovy.json.JsonSlurper
import java.util.concurrent.TimeUnit

// This hook sees only native outcomes. It never uses the planner or manifests as an execution oracle.
File fixtureDirectory = new File(basedir.toString()).canonicalFile
def repository = new File(fixtureDirectory, '../../../..').canonicalFile
def oracle = new File(repository, 'testing/java/io/github/sjacek/shardpilot/testing/VerifyTestExecutions.java')
def java = new File(System.getProperty('java.home'), 'bin/java').absolutePath
def baseline = new File(fixtureDirectory, 'target/executions/baseline/events.tsv')
for (int count : [2, 4]) {
    def report = new File(fixtureDirectory, "target/executions/${count}-shards.json")
    def command = [java, oracle.absolutePath, report.absolutePath, baseline.absolutePath]
    (1..count).each { index ->
        command.add(new File(fixtureDirectory, "target/executions/$count-$index/events.tsv").absolutePath)
    }
    def process = new ProcessBuilder(command).directory(fixtureDirectory).inheritIO().start()
    if (!process.waitFor(90, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw new IllegalStateException('Native execution comparison timed out')
    }
    assert process.exitValue() == 0 : "Native execution equivalence failed: $report"
    def result = new JsonSlurper().parse(report)
    assert result.status == 'PASSED'
    assert result.baselineExecutions == 15 && result.shardExecutions == 15
    assert result.outcomes == [PASSED: 8, FAILED: 4, ERROR: 1, SKIPPED: 1, ABORTED: 1]
}
return true

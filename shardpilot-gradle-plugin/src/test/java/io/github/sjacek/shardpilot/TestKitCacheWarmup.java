package io.github.sjacek.shardpilot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Downloads fixture dependencies with each consumer's resolver, without compiling or running tests. */
public final class TestKitCacheWarmup {
    private TestKitCacheWarmup() { }

    // Keep this aligned with real Jupiter, Spock and TestNG fixtures; a regression test checks coverage.
    static final String BUILD = """
            plugins { id 'java' }
            repositories { mavenCentral() }
            dependencies {
                testImplementation platform('org.junit:junit-bom:6.1.3')
                testImplementation 'org.junit.jupiter:junit-jupiter'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                testImplementation 'org.testng:testng:7.12.0'
                testImplementation 'org.spockframework:spock-core:2.5-groovy-5.0'
                testImplementation 'org.apache.groovy:groovy:5.1.3'
                testRuntimeOnly 'org.apache.groovy:groovy-all:5.1.3'
                testRuntimeOnly 'org.apache.groovy:groovy-astbuilder:5.1.3'
                testRuntimeOnly 'org.apache.groovy:groovy-dateutil:5.1.3'
            }
            tasks.register('warmDependencies') {
                doLast {
                    configurations.testCompileClasspath.files.each { println it.name }
                    configurations.testRuntimeClasspath.files.each { println it.name }
                }
            }
            """;

    /** Arguments are supplied by the build task, independently of the matrix's actual test task. */
    public static void main(String[] args) throws IOException {
        Path cache = Path.of(args[0]);
        Path project = Path.of(args[1]);
        Files.createDirectories(project);
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'ci-dependency-warmup'\n");
        Files.writeString(project.resolve("build.gradle"), BUILD);
        Path metadata = Path.of(args[2]);
        for (String version : Arrays.copyOfRange(args, 3, args.length)) {
            System.out.println("Warming TestKit dependencies for Gradle " + version);
            VerifiedTestKit.create(project, metadata).withTestKitDir(cache.toFile())
                    .withGradleVersion(version)
                    .withArguments("warmDependencies", "--no-configuration-cache", "--stacktrace", "--console=plain",
                            "-Dorg.gradle.java.home=" + System.getProperty("java.home"))
                    .forwardOutput().build();
        }
    }
}

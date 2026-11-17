import io.github.sjacek.shardpilot.ShardPilotExtension

plugins { java; id("io.github.sjacek.shardpilot") }
repositories { mavenCentral() }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
val nativeTest = tasks.named<Test>("test") {
    useJUnitPlatform()
    include("**/*Test.class")
    exclude("**/*$*.class")
    maxHeapSize = "256m"
}
val eligible = sourceSets.test.get().output.classesDirs.asFileTree.matching {
    include("**/*Test.class")
    exclude("**/*$*.class")
}
val pilot = nativeTest.get().extensions.getByType<ShardPilotExtension>()
pilot.enabled.set(true)
pilot.durationsFile.set(layout.projectDirectory.file("durations.tsv"))
pilot.selection {
    policyFile.convention(layout.projectDirectory.file("policy.yaml"))
    profile.convention("smoke-testdata")
    eligibleClasses.from(eligible)
}
pilot.verification { eligibleClasses.from(eligible) }

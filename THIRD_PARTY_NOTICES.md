# Third-party notices

ShardPilot's original implementation is licensed under the root [MIT license](LICENSE).

The generated Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`)
is Gradle code under the [Apache License 2.0](gradle/wrapper/LICENSE), not relicensed to MIT.
Its existing copyright and license headers are preserved. The wrapper JAR also includes
its license as `META-INF/LICENSE`. The wrapper is build tooling, not bundled into ShardPilot's
plugin JAR, source JAR or Javadoc JAR.

The generated Maven Wrapper scripts (`mvnw`, `mvnw.cmd`, wrapper version 3.3.4) also retain their
Apache License 2.0 headers. They download the pinned Apache Maven distribution from Maven Central;
the distribution is not committed or bundled in plugin artifacts. Its checksum is configured in
`.mvn/wrapper/maven-wrapper.properties`.

Gradle API and test libraries retain their respective licenses. Declaring a build or test
dependency does not relicense its code or make its upstream project a ShardPilot contributor.

SnakeYAML Engine (`org.snakeyaml:snakeyaml-engine`) parses and writes YAML manifests under its
upstream Apache License 2.0. It is a core runtime dependency resolved by the respective build tool;
its classes are not shaded into ShardPilot's JAR or injected into consumer test workers.

The Maven adapter uses Apache Maven APIs (provided by the Maven installation) and Apache Surefire's
class-pattern resolver (`surefire-api`, with its runtime dependencies), under their upstream
Apache License 2.0 terms. Maven Invoker/Groovy dependencies are build/test tooling, not consumer
test runtime dependencies. ShardPilot does not repackage or relicense those upstream classes.

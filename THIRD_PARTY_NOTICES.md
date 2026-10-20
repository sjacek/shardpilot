# Third-party notices

ShardPilot's original implementation is licensed under the root [MIT license](LICENSE).

The generated Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`)
is Gradle code under the [Apache License 2.0](gradle/wrapper/LICENSE), not relicensed to MIT.
Its existing copyright and license headers are preserved. The wrapper JAR also includes
its license as `META-INF/LICENSE`. The wrapper is build tooling, not bundled into ShardPilot's
plugin JAR, source JAR or Javadoc JAR.

Gradle API and test libraries retain their respective licenses. Declaring a build or test
dependency does not relicense its code or make its upstream project a ShardPilot contributor.

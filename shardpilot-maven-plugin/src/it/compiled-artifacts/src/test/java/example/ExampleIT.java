package example;

/** Failsafe uses the same sealed compilation cohort without invoking a compile lifecycle. */
public class ExampleIT {
    @org.junit.jupiter.api.Test void worker() throws Exception {
        java.nio.file.Files.writeString(java.nio.file.Path.of("native-runs.txt"), "failsafe\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}

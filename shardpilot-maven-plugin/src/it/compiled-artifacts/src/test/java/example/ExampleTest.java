package example;

/** The native marker is produced only if C3 permits the Surefire worker to start. */
public class ExampleTest {
    @org.junit.jupiter.api.Test void worker() throws Exception {
        java.nio.file.Files.writeString(java.nio.file.Path.of("native-runs.txt"), "surefire\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}

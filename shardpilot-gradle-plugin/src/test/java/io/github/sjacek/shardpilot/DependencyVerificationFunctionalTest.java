package io.github.sjacek.shardpilot;

import com.sun.net.httpserver.HttpServer;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises native verification against a harmless loopback artifact, including poisoned cached bytes. */
class DependencyVerificationFunctionalTest {
    @TempDir Path directory;

    @Test
    void nativeVerificationRejectsChangedCachedArtifactsAndMissingChecksumsWithoutRemoteAccess() throws Exception {
        byte[] jar = "harmless fixture bytes; never executed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] pom = "<project><modelVersion>4.0.0</modelVersion><groupId>example.verification</groupId>"
                .concat("<artifactId>fixture</artifactId><version>1.0</version></project>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var files = Map.of("/example/verification/fixture/1.0/fixture-1.0.jar", jar,
                "/example/verification/fixture/1.0/fixture-1.0.pom", pom);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] content = files.get(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(content == null ? 404 : 200, content == null ? -1 : content.length);
            if (content != null) exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.start();
        // TestKit daemons hold Windows cache locks beyond a build; keep their home outside @TempDir.
        Path kit = Path.of(System.getProperty("org.gradle.testkit.dir"))
                .resolve("verification-probes").resolve(java.util.UUID.randomUUID().toString());
        try {
            Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'verified-cache-fixture'\n");
            Files.writeString(directory.resolve("build.gradle"), """
                    repositories { maven { url = uri('http://127.0.0.1:%d'); allowInsecureProtocol = true } }
                    configurations { reviewed }
                    dependencies { reviewed 'example.verification:fixture:1.0' }
                    tasks.register('probe') { doLast { println configurations.reviewed.files*.name } }
                    """.formatted(server.getAddress().getPort()));
            Path metadata = directory.resolve("gradle/verification-metadata.xml");
            Files.createDirectories(metadata.getParent());
            String component = """
                    <component group="example.verification" name="fixture" version="1.0">
                      <artifact name="fixture-1.0.jar"><sha256 value="%s"/></artifact>
                      <artifact name="fixture-1.0.pom"><sha256 value="%s"/></artifact>
                    </component>
                    """.formatted(sha256(jar), sha256(pom));
            String policy = """
                    <verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
                      <configuration><verify-metadata>true</verify-metadata><verify-signatures>false</verify-signatures></configuration>
                      <components>%s</components>
                    </verification-metadata>
                    """;
            Files.writeString(metadata, policy.formatted(component));
            assertTrue(runner(kit).build().getOutput().contains("fixture-1.0.jar"));
            server.stop(0);
            Path cached;
            try (var paths = Files.walk(kit)) {
                cached = paths.filter(path -> path.getFileName().toString().equals("fixture-1.0.jar"))
                        .findFirst().orElseThrow();
            }
            Files.writeString(cached, "changed harmless cache bytes");
            assertTrue(runner(kit, "--offline").buildAndFail().getOutput().contains("Dependency verification failed"));
            Files.write(cached, jar);
            Files.writeString(metadata, policy.formatted(""));
            assertTrue(runner(kit, "--offline").buildAndFail().getOutput().contains("Dependency verification failed"));
        } finally {
            server.stop(0);
        }
    }

    private GradleRunner runner(Path kit, String... extra) {
        var arguments = new java.util.ArrayList<>(java.util.List.of("probe", "--console=plain", "--dependency-verification=strict",
                "-Dorg.gradle.java.home=" + System.getProperty("java.home")));
        arguments.addAll(java.util.List.of(extra));
        // This deliberately uses a synthetic policy; normal fixtures use VerifiedTestKit's reviewed policy.
        return GradleRunner.create().withProjectDir(directory.toFile()).withTestKitDir(kit.toFile())
                .withGradleVersion(System.getProperty("shardpilot.test.gradleVersion", org.gradle.util.GradleVersion.current().getVersion()))
                .withArguments(arguments);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

package io.github.sjacek.shardpilot;

import org.gradle.testkit.runner.GradleRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Gives isolated consumers the reviewed producer checksum policy, including restored downloads. */
final class VerifiedTestKit {
    private VerifiedTestKit() { }

    static GradleRunner create(Path project) {
        return create(project, Path.of(System.getProperty("shardpilot.test.verificationMetadata")));
    }

    /** Warmup uses the same policy before any fixture library is resolved. */
    static GradleRunner create(Path project, Path metadata) {
        try {
            Path target = project.resolve("gradle/verification-metadata.xml");
            Files.createDirectories(target.getParent());
            Files.copy(metadata, target, StandardCopyOption.REPLACE_EXISTING);
            return GradleRunner.create().withProjectDir(project.toFile());
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot install the reviewed dependency checksums in a TestKit consumer", failure);
        }
    }
}

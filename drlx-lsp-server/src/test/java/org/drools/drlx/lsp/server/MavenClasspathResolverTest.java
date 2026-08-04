package org.drools.drlx.lsp.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class MavenClasspathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveBuildOutputDirs_returnsTargetClasses() throws IOException {
        Path classes = tempDir.resolve("target/classes");
        Files.createDirectories(classes);

        Set<Path> dirs = MavenClasspathResolver.resolveBuildOutputDirs(tempDir);

        assertThat(dirs).containsExactly(classes);
    }

    @Test
    void resolveBuildOutputDirs_emptyWhenNoTargetClasses() {
        Set<Path> dirs = MavenClasspathResolver.resolveBuildOutputDirs(tempDir);

        assertThat(dirs).isEmpty();
    }

    @Test
    void resolve_includesTargetClasses() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Path classes = tempDir.resolve("target/classes");
        Files.createDirectories(classes);

        Set<Path> entries = MavenClasspathResolver.resolve(tempDir);

        assertThat(entries).contains(classes);
    }

    @Test
    void resolve_returnsJarsFromRealProject() {
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        if (!Files.exists(projectRoot.resolve("pom.xml"))) {
            return;
        }

        Set<Path> entries = MavenClasspathResolver.resolve(projectRoot);

        assertThat(entries).isNotEmpty();
        assertThat(entries).anyMatch(p -> p.toString().endsWith(".jar"));
    }
}

package org.drools.drlx.lsp.server;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MavenClasspathProviderTest {

    @Test
    void classpathEntries_returnsConstructedEntries() {
        Set<Path> expected = new LinkedHashSet<>();
        expected.add(Path.of("/project/target/classes"));
        expected.add(Path.of("/home/user/.m2/repository/org/example/lib/1.0/lib-1.0.jar"));

        MavenClasspathProvider provider = new MavenClasspathProvider(expected);

        assertThat(provider.classpathEntries()).isEqualTo(expected);
    }

    @Test
    void classpathEntries_emptySet() {
        MavenClasspathProvider provider = new MavenClasspathProvider(Set.of());

        assertThat(provider.classpathEntries()).isEmpty();
    }
}

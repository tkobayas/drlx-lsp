package org.drools.drlx.completion.semantic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceSiblingResolversTest {

    @AfterEach
    void resetResolver() {
        WorkspaceSiblingResolvers.setActive(null);
    }

    @Test
    void defaultResolverReturnsSameDirectoryDrlxFiles(@TempDir Path tmp) throws Exception {
        Path a = Files.createFile(tmp.resolve("A.drlx"));
        Path b = Files.createFile(tmp.resolve("B.drlx"));
        Files.createFile(tmp.resolve("notes.txt"));

        List<Path> siblings = WorkspaceSiblingResolvers.active().resolveSiblings(a);

        assertThat(siblings).containsExactly(b);
    }

    @Test
    void defaultResolverHandlesNullAndMissingPaths(@TempDir Path tmp) {
        assertThat(WorkspaceSiblingResolvers.active().resolveSiblings(null)).isEmpty();
        assertThat(WorkspaceSiblingResolvers.active()
                .resolveSiblings(tmp.resolve("missing/X.drlx"))).isEmpty();
    }

    @Test
    void setActiveSwapsResolverAndNullRestoresDefault(@TempDir Path tmp) throws Exception {
        Path a = Files.createFile(tmp.resolve("A.drlx"));
        Path b = Files.createFile(tmp.resolve("B.drlx"));

        WorkspaceSiblingResolvers.setActive(file -> List.of());
        assertThat(WorkspaceSiblingResolvers.active().resolveSiblings(a)).isEmpty();

        WorkspaceSiblingResolvers.setActive(null);
        assertThat(WorkspaceSiblingResolvers.active().resolveSiblings(a)).containsExactly(b);
    }
}

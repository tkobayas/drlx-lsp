package org.drools.drlx.lsp.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.drools.drlx.completion.semantic.ClassIndex;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxLspServerRebuildTest {

    @TempDir
    Path tempDir;

    @Test
    void rebuildClassIndexUpdatesClassIndex() throws IOException {
        DrlxLspServer server = TestHelperMethods.getDrlxLspServerForDocument("");

        Path classDir = createClassDir("com/example/Foo.class");
        server.model().rebuild(() -> Set.of(classDir), true);

        server.rebuildClassIndex();

        ClassIndex index = server.model().classIndex();
        assertThat(index.getMatching("Foo")).contains("com.example.Foo");
    }

    @Test
    void didChangeWatchedFilesTriggersRebuild() throws Exception {
        DrlxLspServer server = TestHelperMethods.getDrlxLspServerForDocument("");

        Path classDir = createClassDir("com/example/Bar.class");
        server.model().rebuild(() -> Set.of(classDir), true);

        DrlxLspWorkspaceService workspaceService =
                (DrlxLspWorkspaceService) server.getWorkspaceService();
        workspaceService.didChangeWatchedFiles(new DidChangeWatchedFilesParams());

        Thread.sleep(DrlxLspWorkspaceService.DEBOUNCE_DELAY_MS + 500);

        ClassIndex index = server.model().classIndex();
        assertThat(index.getMatching("Bar")).contains("com.example.Bar");
    }

    @Test
    void rapidFileChangesCoalesceIntoOneRebuild() throws Exception {
        DrlxLspServer server = TestHelperMethods.getDrlxLspServerForDocument("");

        // Initial rebuild on empty classDir (no class files yet)
        Path classDir = tempDir.resolve("classes");
        Files.createDirectories(classDir);
        server.model().rebuild(() -> Set.of(classDir), true);

        // Now add Baz.class to disk (not yet picked up by the index)
        Path pkgDir = classDir.resolve("com/example");
        Files.createDirectories(pkgDir);
        Files.createFile(pkgDir.resolve("Baz.class"));

        DrlxLspWorkspaceService workspaceService =
                (DrlxLspWorkspaceService) server.getWorkspaceService();
        for (int i = 0; i < 100; i++) {
            workspaceService.didChangeWatchedFiles(new DidChangeWatchedFilesParams());
        }

        // Index should not be rebuilt yet (still within debounce window)
        ClassIndex indexBefore = server.model().classIndex();
        assertThat(indexBefore.getMatching("Baz")).isEmpty();

        Thread.sleep(DrlxLspWorkspaceService.DEBOUNCE_DELAY_MS + 500);

        ClassIndex indexAfter = server.model().classIndex();
        assertThat(indexAfter.getMatching("Baz")).contains("com.example.Baz");
    }

    private Path createClassDir(String classFilePath) throws IOException {
        Path classFile = tempDir.resolve(classFilePath);
        Files.createDirectories(classFile.getParent());
        Files.createFile(classFile);
        return tempDir;
    }
}

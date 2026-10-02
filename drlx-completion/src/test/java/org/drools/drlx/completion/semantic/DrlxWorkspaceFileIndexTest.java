package org.drools.drlx.completion.semantic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxWorkspaceFileIndexTest {

    // --- forEachSiblingFile ---

    @Test
    void forEachSiblingFile_diskSibling(@TempDir Path dir) throws Exception {
        Path sibling = dir.resolve("types.drlx");
        Path current = dir.resolve("rules.drlx");
        Files.writeString(sibling, "// sibling");

        List<String> uris = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        DrlxWorkspaceFileIndex.forEachSiblingFile(current, Map.of(), (uri, text) -> {
            uris.add(uri);
            texts.add(text);
        });

        assertThat(uris).containsExactly(sibling.toUri().toString());
        assertThat(texts).containsExactly("// sibling");
    }

    @Test
    void forEachSiblingFile_excludesCurrentDocument(@TempDir Path dir) throws Exception {
        Path current = dir.resolve("rules.drlx");
        Path norm = current.toAbsolutePath().normalize();
        // openFiles にカレント自身を含める
        Map<Path, String> openFiles = Map.of(norm, "// self");

        List<String> uris = new ArrayList<>();
        DrlxWorkspaceFileIndex.forEachSiblingFile(current, openFiles, (uri, text) -> uris.add(uri));

        assertThat(uris).isEmpty();
    }

    @Test
    void forEachSiblingFile_openBufferShadowsDisk(@TempDir Path dir) throws Exception {
        Path sibling = dir.resolve("types.drlx");
        Path current = dir.resolve("rules.drlx");
        Files.writeString(sibling, "// disk version");
        Path norm = sibling.toAbsolutePath().normalize();
        Map<Path, String> openFiles = Map.of(norm, "// buffer version");

        List<String> texts = new ArrayList<>();
        DrlxWorkspaceFileIndex.forEachSiblingFile(current, openFiles, (uri, text) -> texts.add(text));

        // ディスク版は渡されず、バッファ版のみ、1回だけ
        assertThat(texts).containsExactly("// buffer version");
    }

    @Test
    void forEachSiblingFile_nullPath_doesNothing(@TempDir Path dir) throws Exception {
        Path sibling = dir.resolve("types.drlx");
        Files.writeString(sibling, "// ignored");

        List<String> uris = new ArrayList<>();
        DrlxWorkspaceFileIndex.forEachSiblingFile(null, Map.of(), (uri, text) -> uris.add(uri));

        assertThat(uris).isEmpty();
    }

    @Test
    void forEachSiblingFile_nullOpenFiles_readsDisk(@TempDir Path dir) throws Exception {
        Path sibling = dir.resolve("types.drlx");
        Path current = dir.resolve("rules.drlx");
        Files.writeString(sibling, "// disk only");

        List<String> texts = new ArrayList<>();
        DrlxWorkspaceFileIndex.forEachSiblingFile(current, null, (uri, text) -> texts.add(text));

        assertThat(texts).containsExactly("// disk only");
    }

    @Test
    void forEachSiblingFile_multipleSiblings(@TempDir Path dir) throws Exception {
        Path current = dir.resolve("rules.drlx");
        Path s1 = dir.resolve("a.drlx");
        Path s2 = dir.resolve("b.drlx");
        Files.writeString(s1, "// a");
        Files.writeString(s2, "// b");

        List<String> uris = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        DrlxWorkspaceFileIndex.forEachSiblingFile(current, Map.of(), (uri, text) -> {
            uris.add(uri);
            texts.add(text);
        });

        assertThat(uris).containsExactlyInAnyOrder(s1.toUri().toString(), s2.toUri().toString());
        assertThat(texts).containsExactlyInAnyOrder("// a", "// b");
    }
}

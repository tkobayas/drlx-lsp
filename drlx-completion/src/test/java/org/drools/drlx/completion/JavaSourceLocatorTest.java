package org.drools.drlx.completion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSourceLocatorTest {

    @Test
    void locate_findsJavaSource(@TempDir Path module) throws Exception {
        Path classes = module.resolve("target/classes/org/example");
        Files.createDirectories(classes);
        Files.createFile(classes.resolve("Pet.class"));
        Path srcDir = module.resolve("src/main/java/org/example");
        Files.createDirectories(srcDir);
        Path petJava = srcDir.resolve("Pet.java");
        Files.writeString(petJava, "package org.example;\npublic class Pet {\n}\n");

        JavaSourceLocator.Result result = JavaSourceLocator.locate(
                "org.example.Pet", Set.of(module.resolve("target/classes")));

        assertThat(result).isNotNull();
        assertThat(result.location.getUri()).isEqualTo(petJava.toUri().toString());
        assertThat(result.location.getRange().getStart().getLine()).isEqualTo(1);
        assertThat(result.location.getRange().getStart().getCharacter()).isEqualTo(13);
    }

    @Test
    void locate_interface(@TempDir Path module) throws Exception {
        Path classes = module.resolve("target/classes/org/example");
        Files.createDirectories(classes);
        Files.createFile(classes.resolve("Pet.class"));
        Path srcDir = module.resolve("src/main/java/org/example");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Pet.java"),
                "package org.example;\npublic interface Pet {\n}\n");

        JavaSourceLocator.Result result = JavaSourceLocator.locate(
                "org.example.Pet", Set.of(module.resolve("target/classes")));

        assertThat(result).isNotNull();
        assertThat(result.kind).isEqualTo(org.eclipse.lsp4j.SymbolKind.Interface);
    }

    @Test
    void locate_enum(@TempDir Path module) throws Exception {
        Path classes = module.resolve("target/classes/org/example");
        Files.createDirectories(classes);
        Files.createFile(classes.resolve("Color.class"));
        Path srcDir = module.resolve("src/main/java/org/example");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Color.java"),
                "package org.example;\npublic enum Color { RED, GREEN }\n");

        JavaSourceLocator.Result result = JavaSourceLocator.locate(
                "org.example.Color", Set.of(module.resolve("target/classes")));

        assertThat(result).isNotNull();
        assertThat(result.kind).isEqualTo(org.eclipse.lsp4j.SymbolKind.Enum);
        assertThat(result.location.getRange().getStart().getLine()).isEqualTo(1);
    }

    @Test
    void locate_nullFqcn_returnsNull() {
        assertThat(JavaSourceLocator.locate(null, Set.of(Path.of("/tmp")))).isNull();
    }

    @Test
    void locate_emptyBuildOutputDirs_returnsNull() {
        assertThat(JavaSourceLocator.locate("org.example.Pet", Set.of())).isNull();
    }

    @Test
    void locate_noClassFile_returnsNull(@TempDir Path module) throws Exception {
        Path srcDir = module.resolve("src/main/java/org/example");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Pet.java"), "package org.example;\npublic class Pet {}\n");
        Files.createDirectories(module.resolve("target/classes"));

        JavaSourceLocator.Result result = JavaSourceLocator.locate(
                "org.example.Pet", Set.of(module.resolve("target/classes")));

        assertThat(result).isNull();
    }

    @Test
    void locate_noJavaSource_returnsNull(@TempDir Path module) throws Exception {
        Path classes = module.resolve("target/classes/org/example");
        Files.createDirectories(classes);
        Files.createFile(classes.resolve("Pet.class"));

        JavaSourceLocator.Result result = JavaSourceLocator.locate(
                "org.example.Pet", Set.of(module.resolve("target/classes")));

        assertThat(result).isNull();
    }

    @Test
    void locate_fallbackToLineZero(@TempDir Path module) throws Exception {
        Path classes = module.resolve("target/classes/org/example");
        Files.createDirectories(classes);
        Files.createFile(classes.resolve("Pet.class"));
        Path srcDir = module.resolve("src/main/java/org/example");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Pet.java"), "// no type declaration here\n");

        JavaSourceLocator.Result result = JavaSourceLocator.locate(
                "org.example.Pet", Set.of(module.resolve("target/classes")));

        assertThat(result).isNotNull();
        assertThat(result.location.getRange().getStart().getLine()).isEqualTo(0);
        assertThat(result.location.getRange().getStart().getCharacter()).isEqualTo(0);
        assertThat(result.kind).isEqualTo(org.eclipse.lsp4j.SymbolKind.Class);
    }

    @Test
    void locate_innerClass_returnsNull(@TempDir Path module) throws Exception {
        Path classes = module.resolve("target/classes/org/example");
        Files.createDirectories(classes);
        Files.createFile(classes.resolve("Outer$Inner.class"));
        Path srcDir = module.resolve("src/main/java/org/example");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Outer.java"),
                "package org.example;\npublic class Outer { public class Inner {} }\n");

        JavaSourceLocator.Result result = JavaSourceLocator.locate(
                "org.example.Outer$Inner", Set.of(module.resolve("target/classes")));

        assertThat(result).isNull();
    }

    @Test
    void locate_defaultPackage(@TempDir Path module) throws Exception {
        Files.createDirectories(module.resolve("target/classes"));
        Files.createFile(module.resolve("target/classes/Pet.class"));
        Path srcDir = module.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Path petJava = srcDir.resolve("Pet.java");
        Files.writeString(petJava, "public class Pet {}\n");

        JavaSourceLocator.Result result = JavaSourceLocator.locate(
                "Pet", Set.of(module.resolve("target/classes")));

        assertThat(result).isNotNull();
        assertThat(result.location.getUri()).isEqualTo(petJava.toUri().toString());
    }
}

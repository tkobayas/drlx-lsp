package org.drools.drlx.completion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxDefinitionHelperTest {

    private final WorkspaceSemanticModel model =
            new WorkspaceSemanticModel(new CurrentClassloaderProvider());

    private static final String URI = "file:///test.drlx";

    @Test
    void oopathBinding() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p }
                }
                """;
        // "p" in "do { p }" — line 7, char 9
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(7, 9), model);

        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).getUri()).isEqualTo(URI);
        // "p" in "var p : /persons" — line 6, char 8
        assertThat(defs.get(0).getRange().getStart().getLine()).isEqualTo(6);
        assertThat(defs.get(0).getRange().getStart().getCharacter()).isEqualTo(8);
    }

    @Test
    void constraintBinding() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons[$addr : address],
                    do { $addr }
                }
                """;
        // "$addr" in "do { $addr }" — line 8, char 9
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(8, 9), model);

        assertThat(defs).hasSize(1);
        // "$addr" in "[$addr : address]" — line 7
        assertThat(defs.get(0).getRange().getStart().getLine()).isEqualTo(7);
    }

    @Test
    void ruleParameter() {
        // Line 0: import org.drools.drlx.domain.Person;
        // Line 1: import org.drools.drlx.domain.MyUnit;
        // Line 2:
        // Line 3: unit MyUnit;
        // Line 4:
        // Line 5: rule R1(Person p) {
        // Line 6:     do { p }
        // Line 7: }
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1(Person p) {
                    do { p }
                }
                """;
        // "p" in "do { p }" — line 6, char 9
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(6, 9), model);

        assertThat(defs).hasSize(1);
        // "p" in "rule R1(Person p)" — line 5
        assertThat(defs.get(0).getRange().getStart().getLine()).isEqualTo(5);
    }

    @Test
    void rhsLocalVariable() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do {
                        Address x = p.getAddress();
                        x }
                }
                """;
        // "x" in "x }" — line 10, char 8
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(10, 8), model);

        assertThat(defs).hasSize(1);
        // "x" in "Address x = ..." — line 9
        assertThat(defs.get(0).getRange().getStart().getLine()).isEqualTo(9);
    }

    @Test
    void cursorOnDefinitionSite() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p }
                }
                """;
        // Cursor on "p" in "var p : /persons" — line 6, char 8 (the definition itself)
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(6, 8), model);

        assertThat(defs).isEmpty();
    }

    @Test
    void keywordReturnsEmpty() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1(Person p) {
                    do { p }
                }
                """;
        // Cursor on "rule" keyword — line 5, char 0
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(5, 0), model);

        assertThat(defs).isEmpty();
    }

    @Test
    void unknownSymbolReturnsEmpty() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { unknown }
                }
                """;
        // "unknown" — line 7, char 9
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(7, 9), model);

        assertThat(defs).isEmpty();
    }

    @Test
    void importType() {
        // Line 0: import org.drools.drlx.domain.Person;
        // Line 1: import org.drools.drlx.domain.MyUnit;
        // Line 2:
        // Line 3: unit MyUnit;
        // Line 4:
        // Line 5: rule R1(Person p) {
        // Line 6:     do { p }
        // Line 7: }
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1(Person p) {
                    do { p }
                }
                """;
        // Cursor on "Person" in "rule R1(Person p)" — line 5, char 8
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(5, 8), model);

        assertThat(defs).hasSize(1);
        // "Person" in import line — line 0
        assertThat(defs.get(0).getRange().getStart().getLine()).isEqualTo(0);
    }

    @Test
    void cursorOnImportLine() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1(Person p) {
                    do { p }
                }
                """;
        // Cursor on "Person" in the import line — line 0
        // "Person" starts at char 31 in "import org.drools.drlx.domain.Person;"
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(0, 31), model);

        assertThat(defs).isEmpty();
    }

    @Test
    void nullTextReturnsEmpty() {
        List<Location> defs = DrlxDefinitionHelper.definition(URI, null, new Position(0, 0), model);
        assertThat(defs).isEmpty();
    }

    @Test
    void javaSourceDefinition_directJump(@TempDir Path module) throws Exception {
        Path classes = module.resolve("target/classes/org/example");
        Files.createDirectories(classes);
        Files.createFile(classes.resolve("Pet.class"));
        Path srcDir = module.resolve("src/main/java/org/example");
        Files.createDirectories(srcDir);
        Path petJava = srcDir.resolve("Pet.java");
        Files.writeString(petJava, "package org.example;\npublic class Pet {\n}\n");

        Set<Path> entries = new java.util.LinkedHashSet<>(
                new CurrentClassloaderProvider().classpathEntries());
        entries.add(module.resolve("target/classes"));
        WorkspaceSemanticModel testModel = new WorkspaceSemanticModel(() -> entries);

        // Line 0: import org.example.Pet;
        // Line 1:
        // Line 2: rule R1 {
        // Line 3:     do { Pet }
        // Line 4: }
        String text = """
                import org.example.Pet;

                rule R1 {
                    do { Pet }
                }
                """;
        // "Pet" in "do { Pet }" — line 3, char 9
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(3, 9), testModel);

        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).getUri()).isEqualTo(petJava.toUri().toString());
        assertThat(defs.get(0).getRange().getStart().getLine()).isEqualTo(1);
        assertThat(defs.get(0).getRange().getStart().getCharacter()).isEqualTo(13);
    }

    @Test
    void javaSourceDefinition_fromImportLine(@TempDir Path module) throws Exception {
        Path classes = module.resolve("target/classes/org/example");
        Files.createDirectories(classes);
        Files.createFile(classes.resolve("Pet.class"));
        Path srcDir = module.resolve("src/main/java/org/example");
        Files.createDirectories(srcDir);
        Path petJava = srcDir.resolve("Pet.java");
        Files.writeString(petJava, "package org.example;\npublic class Pet {\n}\n");

        Set<Path> entries = new java.util.LinkedHashSet<>(
                new CurrentClassloaderProvider().classpathEntries());
        entries.add(module.resolve("target/classes"));
        WorkspaceSemanticModel testModel = new WorkspaceSemanticModel(() -> entries);

        // Line 0: import org.example.Pet;
        String text = """
                import org.example.Pet;

                rule R1 {
                    do { Pet }
                }
                """;
        // "Pet" on the import line — line 0, char 19
        // "import org.example.Pet;" → "Pet" starts at position 19
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(0, 19), testModel);

        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).getUri()).isEqualTo(petJava.toUri().toString());
    }

    @Test
    void importFallback_whenNoJavaSource() {
        // Uses the default model (CurrentClassloaderProvider).
        // Domain class Person is in src/test/java, not src/main/java,
        // so JavaSourceLocator returns null → falls back to import line.
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1(Person p) {
                    do { p }
                }
                """;
        // "Person" in "rule R1(Person p)" — line 5, char 8
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(5, 8), model);

        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).getUri()).isEqualTo(URI);
        // Falls back to import line — line 0
        assertThat(defs.get(0).getRange().getStart().getLine()).isEqualTo(0);
    }

    @Test
    void ambiguousTypeName_noJavaSource_noImport_returnsEmpty() {
        // "String" is not imported and ClassIndex likely has multiple
        // candidates (java.lang.String, etc.) — resolveFqcn should return null,
        // and with no import match, result is empty.
        String text = """
                rule R1 {
                    do { String }
                }
                """;
        // "String" — line 1, char 9
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(1, 9), model);

        assertThat(defs).isEmpty();
    }
}

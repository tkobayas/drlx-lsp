package org.drools.drlx.completion;

import java.util.List;

import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxRenameHelperTest {

    private final WorkspaceSemanticModel model =
            new WorkspaceSemanticModel(new CurrentClassloaderProvider());

    private static final String URI = "file:///test.drlx";

    // --- prepare ---

    @Test
    void prepare_oopathBinding_returnsRangeAndPlaceholder() {
        // Line 0: import org.drools.drlx.domain.Person;
        // Line 1: import org.drools.drlx.domain.MyUnit;
        // Line 2:
        // Line 3: unit MyUnit;
        // Line 4:
        // Line 5: rule R1 {
        // Line 6:     var p : /persons,
        // Line 7:     do { p }
        // Line 8: }
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p }
                }
                """;
        // Cursor on "p" in "var p : /persons" — line 6, char 8
        DrlxRenameHelper.PreparedRename result =
                DrlxRenameHelper.prepare(URI, text, new Position(6, 8), model);

        assertThat(result).isNotNull();
        assertThat(result.placeholder()).isEqualTo("p");
        assertThat(result.range().getStart().getLine()).isEqualTo(6);
        assertThat(result.range().getStart().getCharacter()).isEqualTo(8);
        assertThat(result.range().getEnd().getCharacter()).isEqualTo(9);
    }

    @Test
    void prepare_constraintBinding_returnsRangeAndPlaceholder() {
        // Line 0: import org.drools.drlx.domain.Person;
        // Line 1: import org.drools.drlx.domain.Address;
        // Line 2: import org.drools.drlx.domain.MyUnit;
        // Line 3:
        // Line 4: unit MyUnit;
        // Line 5:
        // Line 6: rule R1 {
        // Line 7:     var p : /persons[$addr : address],
        // Line 8:     do { $addr }
        // Line 9: }
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
        // Cursor on "$addr" in "do { $addr }" — line 8, char 9
        DrlxRenameHelper.PreparedRename result =
                DrlxRenameHelper.prepare(URI, text, new Position(8, 9), model);

        assertThat(result).isNotNull();
        assertThat(result.placeholder()).isEqualTo("$addr");
        assertThat(result.range().getStart().getLine()).isEqualTo(8);
    }

    @Test
    void prepare_importType_returnsNull() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1(Person p) {
                    do { p }
                }
                """;
        // Cursor on "Person" in "rule R1(Person p)" — line 5, char 8
        DrlxRenameHelper.PreparedRename result =
                DrlxRenameHelper.prepare(URI, text, new Position(5, 8), model);

        assertThat(result).isNull();
    }

    @Test
    void prepare_keyword_returnsNull() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p }
                }
                """;
        // Cursor on "rule" keyword — line 5, char 0
        DrlxRenameHelper.PreparedRename result =
                DrlxRenameHelper.prepare(URI, text, new Position(5, 0), model);

        assertThat(result).isNull();
    }

    @Test
    void prepare_nullText_returnsNull() {
        assertThat(DrlxRenameHelper.prepare(URI, null, new Position(0, 0), model)).isNull();
    }

    // --- rename ---

    @Test
    void rename_oopathBinding_updatesAllReferences() {
        // Line 0: import org.drools.drlx.domain.Person;
        // Line 1: import org.drools.drlx.domain.MyUnit;
        // Line 2:
        // Line 3: unit MyUnit;
        // Line 4:
        // Line 5: rule R1 {
        // Line 6:     var p : /persons,
        // Line 7:     do { p }
        // Line 8: }
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p }
                }
                """;
        // Cursor on "p" in "do { p }" — line 7, char 9
        WorkspaceEdit edit =
                DrlxRenameHelper.rename(URI, text, new Position(7, 9), "person", model);

        assertThat(edit).isNotNull();
        List<TextEdit> edits = edit.getChanges().get(URI);
        assertThat(edits).hasSize(2);
        assertThat(edits).allSatisfy(e -> assertThat(e.getNewText()).isEqualTo("person"));
    }

    @Test
    void rename_constraintBinding_withDollar() {
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
        // Cursor on "$addr" — line 8, char 9; newName with $
        WorkspaceEdit edit =
                DrlxRenameHelper.rename(URI, text, new Position(8, 9), "$address", model);

        assertThat(edit).isNotNull();
        List<TextEdit> edits = edit.getChanges().get(URI);
        assertThat(edits).hasSize(2);
        assertThat(edits).allSatisfy(e -> assertThat(e.getNewText()).isEqualTo("$address"));
    }

    @Test
    void rename_constraintBinding_withoutDollar() {
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
        // Cursor on "$addr" — line 8, char 9; newName without $ — used as-is
        WorkspaceEdit edit =
                DrlxRenameHelper.rename(URI, text, new Position(8, 9), "address", model);

        assertThat(edit).isNotNull();
        List<TextEdit> edits = edit.getChanges().get(URI);
        assertThat(edits).hasSize(2);
        assertThat(edits).allSatisfy(e -> assertThat(e.getNewText()).isEqualTo("address"));
    }

    @Test
    void rename_rhsLocalVariable_updatesAllReferences() {
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
        // Cursor on "x" in "x }" — line 10, char 8
        WorkspaceEdit edit =
                DrlxRenameHelper.rename(URI, text, new Position(10, 8), "result", model);

        assertThat(edit).isNotNull();
        List<TextEdit> edits = edit.getChanges().get(URI);
        assertThat(edits).hasSize(2);
        assertThat(edits).allSatisfy(e -> assertThat(e.getNewText()).isEqualTo("result"));
    }

    @Test
    void rename_scopedToEnclosingRule() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p }
                }

                rule R2 {
                    var p : /persons,
                    do { p }
                }
                """;
        // Cursor on "p" in R1's "do { p }" — line 7, char 9
        WorkspaceEdit edit =
                DrlxRenameHelper.rename(URI, text, new Position(7, 9), "person", model);

        assertThat(edit).isNotNull();
        List<TextEdit> edits = edit.getChanges().get(URI);
        assertThat(edits).hasSize(2);
        // All edits within R1 (lines 5-8), none from R2
        assertThat(edits).allSatisfy(e ->
                assertThat(e.getRange().getStart().getLine()).isLessThanOrEqualTo(8));
    }

    @Test
    void rename_importType_returnsNull() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1(Person p) {
                    do { p }
                }
                """;
        // Cursor on "Person" — line 5, char 8
        WorkspaceEdit edit =
                DrlxRenameHelper.rename(URI, text, new Position(5, 8), "Customer", model);

        assertThat(edit).isNull();
    }

    @Test
    void rename_invalidNewName_returnsNull() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p }
                }
                """;
        assertThat(DrlxRenameHelper.rename(URI, text, new Position(7, 9), "123abc", model)).isNull();
        assertThat(DrlxRenameHelper.rename(URI, text, new Position(7, 9), "has space", model)).isNull();
    }

    @Test
    void rename_emptyNewName_returnsNull() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p }
                }
                """;
        assertThat(DrlxRenameHelper.rename(URI, text, new Position(7, 9), "", model)).isNull();
    }

    @Test
    void rename_nullText_returnsNull() {
        assertThat(DrlxRenameHelper.rename(URI, null, new Position(0, 0), "x", model)).isNull();
    }
}

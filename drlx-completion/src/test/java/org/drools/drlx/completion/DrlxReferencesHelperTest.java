package org.drools.drlx.completion;

import java.util.List;

import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxReferencesHelperTest {

    private final WorkspaceSemanticModel model =
            new WorkspaceSemanticModel(new CurrentClassloaderProvider());

    private static final String URI = "file:///test.drlx";

    @Test
    void oopathBinding_findsAllUsesInRule() {
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
        List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(7, 9), model, true);

        assertThat(refs).hasSize(2);
        // Declaration: "p" in "var p : /persons" — line 6
        assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 6);
        // Usage: "p" in "do { p }" — line 7
        assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 7);
    }

    @Test
    void oopathBinding_excludeDeclaration() {
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
        List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(7, 9), model, false);

        assertThat(refs).hasSize(1);
        // Only usage, not declaration
        assertThat(refs.get(0).getRange().getStart().getLine()).isEqualTo(7);
    }

    @Test
    void constraintBinding_findsAllUsesInRule() {
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
        List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(8, 9), model, true);

        assertThat(refs).hasSize(2);
        assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 7);
        assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 8);
    }

    @Test
    void ruleParameter_findsAllUsesInRule() {
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
        // Cursor on "p" in "do { p }" — line 6, char 9
        List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(6, 9), model, true);

        assertThat(refs).hasSize(2);
        assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 5);
        assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 6);
    }

    @Test
    void rhsLocalVariable_findsAllUsesInRule() {
        // Line 0: import org.drools.drlx.domain.Person;
        // Line 1: import org.drools.drlx.domain.Address;
        // Line 2: import org.drools.drlx.domain.MyUnit;
        // Line 3:
        // Line 4: unit MyUnit;
        // Line 5:
        // Line 6: rule R1 {
        // Line 7:     var p : /persons,
        // Line 8:     do {
        // Line 9:         Address x = p.getAddress();
        // Line 10:        x }
        // Line 11: }
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
        List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(10, 8), model, true);

        assertThat(refs).hasSize(2);
        assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 9);
        assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 10);
    }

    @Test
    void bindingScopedToEnclosingRule() {
        // Line 0: import org.drools.drlx.domain.Person;
        // Line 1: import org.drools.drlx.domain.MyUnit;
        // Line 2:
        // Line 3: unit MyUnit;
        // Line 4:
        // Line 5: rule R1 {
        // Line 6:     var p : /persons,
        // Line 7:     do { p }
        // Line 8: }
        // Line 9:
        // Line 10: rule R2 {
        // Line 11:     var p : /persons,
        // Line 12:     do { p }
        // Line 13: }
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
        List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(7, 9), model, true);

        assertThat(refs).hasSize(2);
        // All refs should be within R1 (lines 5-8), none from R2 (lines 10-13)
        assertThat(refs).allMatch(loc -> loc.getRange().getStart().getLine() <= 8);
    }

    @Test
    void importType_findsAllUsesInFile() {
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
        List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(5, 8), model, true);

        assertThat(refs).hasSize(2);
        // Import line — line 0
        assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 0);
        // Rule parameter — line 5
        assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 5);
    }

    @Test
    void importType_excludeDeclaration() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1(Person p) {
                    do { p }
                }
                """;
        // Cursor on "Person" in "rule R1(Person p)" — line 5, char 8
        List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(5, 8), model, false);

        assertThat(refs).hasSize(1);
        // Only the usage in rule parameter, not the import line
        assertThat(refs.get(0).getRange().getStart().getLine()).isEqualTo(5);
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
        List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(5, 0), model, true);

        assertThat(refs).isEmpty();
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
        List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(7, 9), model, true);

        assertThat(refs).isEmpty();
    }

    @Test
    void nullTextReturnsEmpty() {
        List<Location> refs = DrlxReferencesHelper.references(URI, null, new Position(0, 0), model, true);
        assertThat(refs).isEmpty();
    }
}

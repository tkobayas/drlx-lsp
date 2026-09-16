package org.drools.drlx.completion;

import java.util.List;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxDocumentSymbolHelperTest {

    @Test
    void nullOrEmptyTextYieldsNoSymbols() {
        assertThat(DrlxDocumentSymbolHelper.symbols(null)).isEmpty();
        assertThat(DrlxDocumentSymbolHelper.symbols("")).isEmpty();
        assertThat(DrlxDocumentSymbolHelper.symbols("   \n\t  ")).isEmpty();
    }

    @Test
    void unitAndRulesOutlined() {
        String drlx = """
                import org.example.Person;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { System.out.println(p); }
                }

                rule R2 {
                    var p : /persons,
                    do { }
                }
                """;

        List<DocumentSymbol> symbols = DrlxDocumentSymbolHelper.symbols(drlx);

        assertThat(symbols).hasSize(3);

        DocumentSymbol s0 = symbols.get(0);
        assertThat(s0.getName()).isEqualTo("MyUnit");
        assertThat(s0.getKind()).isEqualTo(SymbolKind.Namespace);
        assertThat(s0.getRange().getStart().getLine()).isEqualTo(2);

        DocumentSymbol s1 = symbols.get(1);
        assertThat(s1.getName()).isEqualTo("R1");
        assertThat(s1.getKind()).isEqualTo(SymbolKind.Method);
        assertThat(s1.getRange().getStart().getLine()).isEqualTo(4);

        DocumentSymbol s2 = symbols.get(2);
        assertThat(s2.getName()).isEqualTo("R2");
        assertThat(s2.getKind()).isEqualTo(SymbolKind.Method);
        assertThat(s2.getRange().getStart().getLine()).isEqualTo(9);
    }

    @Test
    void multipleRulesOutlined() {
        String drlx = """
                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { }
                }

                rule R2 {
                    var p : /persons,
                    do { }
                }
                """;

        List<DocumentSymbol> symbols = DrlxDocumentSymbolHelper.symbols(drlx);

        assertThat(symbols).hasSize(3);
        assertThat(symbols.get(0).getName()).isEqualTo("MyUnit");
        assertThat(symbols.get(0).getKind()).isEqualTo(SymbolKind.Namespace);
        assertThat(symbols.get(1).getName()).isEqualTo("R1");
        assertThat(symbols.get(1).getKind()).isEqualTo(SymbolKind.Method);
        assertThat(symbols.get(2).getName()).isEqualTo("R2");
        assertThat(symbols.get(2).getKind()).isEqualTo(SymbolKind.Method);
    }

    @Test
    void unitOnlyWhenNoRules() {
        String drlx = """
                import org.example.Person;

                unit org.example.MyUnit;
                """;

        List<DocumentSymbol> symbols = DrlxDocumentSymbolHelper.symbols(drlx);

        assertThat(symbols).hasSize(1);
        assertThat(symbols.get(0).getName()).isEqualTo("org.example.MyUnit");
        assertThat(symbols.get(0).getKind()).isEqualTo(SymbolKind.Namespace);
    }

    @Test
    void selectionRangeWithinRange() {
        String drlx = """
                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { }
                }
                """;

        List<DocumentSymbol> symbols = DrlxDocumentSymbolHelper.symbols(drlx);

        assertThat(symbols).isNotEmpty();
        for (DocumentSymbol s : symbols) {
            Range range = s.getRange();
            Range sel = s.getSelectionRange();

            assertThat(isPositionBeforeOrEqual(range.getStart(), sel.getStart())).isTrue();
            assertThat(isPositionBeforeOrEqual(sel.getEnd(), range.getEnd())).isTrue();
        }
    }

    @Test
    void javaStyleFileYieldsNoSymbols() {
        String drlx = """
                package org.example;

                class Foo {
                    rule R1 {
                        var p : /persons,
                        do { }
                    }
                }
                """;

        List<DocumentSymbol> symbols = DrlxDocumentSymbolHelper.symbols(drlx);

        assertThat(symbols).isEmpty();
    }

    @Test
    void partialFileDoesNotThrow() {
        String drlx = "unit ; rule {";

        List<DocumentSymbol> symbols = DrlxDocumentSymbolHelper.symbols(drlx);

        // Should return without throwing exceptions
        assertThat(symbols).isNotNull();
    }

    private boolean isPositionBeforeOrEqual(Position p1, Position p2) {
        if (p1.getLine() < p2.getLine()) return true;
        if (p1.getLine() == p2.getLine()) return p1.getCharacter() <= p2.getCharacter();
        return false;
    }
}

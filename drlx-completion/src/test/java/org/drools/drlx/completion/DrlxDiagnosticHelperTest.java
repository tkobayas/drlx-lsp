package org.drools.drlx.completion;

import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxDiagnosticHelperTest {

    @Test
    void cleanFileProducesNoDiagnostics() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { System.out.println(p); }
                }
                """;
        assertThat(DrlxDiagnosticHelper.validate(text)).isEmpty();
    }

    @Test
    void syntaxErrorProducesDiagnostic() {
        String text = "rule R1 { var p : /persons[ }";
        List<Diagnostic> diags = DrlxDiagnosticHelper.validate(text);
        assertThat(diags).isNotEmpty();
    }

    @Test
    void diagnosticHasRangeSeverityAndSource() {
        String text = """
                rule R1 {
                    var p : /persons[,
                }
                """;
        List<Diagnostic> diags = DrlxDiagnosticHelper.validate(text);

        assertThat(diags).isNotEmpty();
        for (Diagnostic d : diags) {
            assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Error);
            assertThat(d.getSource()).isEqualTo("drlx-parser");
            assertThat(d.getMessage()).isNotBlank();
            assertThat(d.getRange().getStart().getLine())
                    .isEqualTo(d.getRange().getEnd().getLine());
            assertThat(d.getRange().getEnd().getCharacter())
                    .isGreaterThan(d.getRange().getStart().getCharacter());
        }
    }

    @Test
    void multipleBrokenRulesReportMultiple() {
        String text = """
                rule R1 {
                    var p : /persons[,
                }

                rule R2 {
                    var a : /addresses[,
                }
                """;
        List<Diagnostic> diags = DrlxDiagnosticHelper.validate(text);

        assertThat(diags.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void lexerErrorProducesDiagnostic() {
        String text = """
                rule "R1
                {}
                """;
        List<Diagnostic> diags = DrlxDiagnosticHelper.validate(text);

        assertThat(diags).isNotEmpty();
        for (Diagnostic d : diags) {
            assertThat(d.getRange().getStart().getCharacter())
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void eofErrorRangeStaysWithinText() {
        String text = "rule R1 {";
        List<Diagnostic> diags = DrlxDiagnosticHelper.validate(text);

        assertThat(diags).isNotEmpty();
        for (Diagnostic d : diags) {
            assertThat(d.getRange().getEnd().getCharacter())
                    .as("range end must not extend past the line (len=%d): %s",
                            text.length(), d)
                    .isLessThanOrEqualTo(text.length());
        }
    }

    @Test
    void nullTextReturnsEmpty() {
        assertThat(DrlxDiagnosticHelper.validate(null)).isEmpty();
    }

    @Test
    void emptyTextReturnsEmpty() {
        assertThat(DrlxDiagnosticHelper.validate("")).isEmpty();
    }
}

package org.drools.drlx.completion;

import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxLintHelperTest {

    @AfterEach
    void resetSystemProperty() {
        System.clearProperty("drlx.lsp.lint.unbalancedOopathBrackets");
    }

    @Test
    void nullAndEmptyReturnEmpty() {
        assertThat(DrlxLintHelper.lint(null)).isEmpty();
        assertThat(DrlxLintHelper.lint("")).isEmpty();
    }

    @Test
    void cleanRuleIsClean() {
        String text = """
                rule R1 {
                    var $p : /persons[ age > 18 ],
                    do { System.out.println($p); }
                }
                """;
        assertThat(DrlxLintHelper.lint(text)).isEmpty();
    }

    @Test
    void unclosedBracketIsReported() {
        String text = """
                rule R1 {
                    var $p = /persons[ age > 18,
                    do { }
                }
                """;
        List<Diagnostic> diags = DrlxLintHelper.lint(text);
        assertThat(diags).hasSize(1);
        Diagnostic d = diags.get(0);
        assertThat(d.getSource()).isEqualTo("drlx-lint");
        assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
        assertThat(d.getMessage()).isEqualTo("Unclosed '[' in OOPath filter — missing ']'");
        // '[' is at line 1 (0-based), column 21
        assertThat(d.getRange().getStart().getLine()).isEqualTo(1);
        assertThat(d.getRange().getStart().getCharacter()).isEqualTo(21);
        assertThat(d.getRange().getEnd().getCharacter())
                .isEqualTo(d.getRange().getStart().getCharacter() + 1);
    }

    @Test
    void unmatchedCloseBracketIsReported() {
        String text = """
                rule R1 {
                    var $p = /persons] age > 18,
                    do { }
                }
                """;
        List<Diagnostic> diags = DrlxLintHelper.lint(text);
        assertThat(diags).hasSize(1);
        Diagnostic d = diags.get(0);
        assertThat(d.getSource()).isEqualTo("drlx-lint");
        assertThat(d.getMessage()).isEqualTo("Unmatched ']' — no corresponding '['");
        // ']' is at line 1 (0-based), column 21
        assertThat(d.getRange().getStart().getLine()).isEqualTo(1);
        assertThat(d.getRange().getStart().getCharacter()).isEqualTo(21);
    }

    @Test
    void multipleUnclosedBracketsReported() {
        String text = """
                rule R1 {
                    var $p = /persons[ age > foo[ 18,
                    do { }
                }
                """;
        List<Diagnostic> diags = DrlxLintHelper.lint(text);
        assertThat(diags).hasSize(2);
        assertThat(diags).allMatch(d -> d.getMessage()
                .equals("Unclosed '[' in OOPath filter — missing ']'"));
    }

    @Test
    void bracketInStringIsIgnored() {
        String text = """
                rule R1 {
                    var $p = /persons[ name == "[" ],
                    do { }
                }
                """;
        assertThat(DrlxLintHelper.lint(text)).isEmpty();
    }

    @Test
    void bracketInCommentIsIgnored() {
        String text = """
                rule R1 {
                    // [
                    var $p = /persons[ age > 18 ],
                    do { }
                }
                """;
        assertThat(DrlxLintHelper.lint(text)).isEmpty();
    }

    @Test
    void consequenceBracketsIgnored() {
        String text = """
                rule R1 {
                    var $p = /persons,
                    do { int[] arr = new int[3]; }
                }
                """;
        assertThat(DrlxLintHelper.lint(text)).isEmpty();
    }

    @Test
    void passDisabledBySystemProperty() {
        System.setProperty("drlx.lsp.lint.unbalancedOopathBrackets", "off");
        String text = """
                rule R1 {
                    var $p = /persons[ age > 18,
                    do { }
                }
                """;
        assertThat(DrlxLintHelper.lint(text)).isEmpty();
    }
}

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
        System.clearProperty("drlx.lsp.lint.unknownTypes");
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

    @Test
    void unknownType_notFlaggedIfClasspathNotResolved() {
        org.drools.drlx.completion.semantic.WorkspaceSemanticModel model =
                new org.drools.drlx.completion.semantic.WorkspaceSemanticModel(() -> java.util.Set.of());
        // default model rebuild has classpathResolved = true, so explicitly set false
        model.rebuild(() -> java.util.Set.of(), false);

        String text = """
                rule R1 {
                    Preson $p : /persons,
                    do { }
                }
                """;
        assertThat(DrlxLintHelper.lint(text, model)).isEmpty();
    }

    @Test
    void unknownType_reportedWithTypoSuggestion() {
        org.drools.drlx.completion.semantic.WorkspaceSemanticModel model =
                new org.drools.drlx.completion.semantic.WorkspaceSemanticModel(
                        new org.drools.drlx.completion.semantic.CurrentClassloaderProvider());

        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    Preson p : /persons,
                    do { }
                }
                """;
        List<Diagnostic> diags = DrlxLintHelper.lint(text, model);
        assertThat(diags).hasSize(1);
        Diagnostic d = diags.get(0);
        assertThat(d.getSource()).isEqualTo("drlx-type");
        assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
        assertThat(d.getMessage()).isEqualTo("Unknown type 'Preson'. Did you mean 'Person'?");
        assertThat(d.getData()).isEqualTo("Person");
        assertThat(d.getRange().getStart().getLine()).isEqualTo(6);
        assertThat(d.getRange().getStart().getCharacter()).isEqualTo(4);
    }

    @Test
    void unknownType_negativeTests_oopathRootsAndQueriesAreNotTypes() {
        org.drools.drlx.completion.semantic.WorkspaceSemanticModel model =
                new org.drools.drlx.completion.semantic.WorkspaceSemanticModel(
                        new org.drools.drlx.completion.semantic.CurrentClassloaderProvider());

        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    Person p : /persons[ age > 18 ],
                    var a : /persons/address,
                    do { }
                }
                """;
        assertThat(DrlxLintHelper.lint(text, model)).isEmpty();
    }

    @Test
    void unknownType_rhsInstantiationTypo() {
        org.drools.drlx.completion.semantic.WorkspaceSemanticModel model =
                new org.drools.drlx.completion.semantic.WorkspaceSemanticModel(
                        new org.drools.drlx.completion.semantic.CurrentClassloaderProvider());

        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do {
                        Object o = new Preson("Alice");
                    }
                }
                """;
        List<Diagnostic> diags = DrlxLintHelper.lint(text, model);
        assertThat(diags).hasSize(1);
        Diagnostic d = diags.get(0);
        assertThat(d.getSource()).isEqualTo("drlx-type");
        assertThat(d.getMessage()).isEqualTo("Unknown type 'Preson'. Did you mean 'Person'?");
        assertThat(d.getData()).isEqualTo("Person");
    }
}

package org.drools.drlx.completion;

import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.drools.drlx.completion.DRLXCompletionHelper.completionItemStrings;

class DRLXCompletionHelperIncompleteCodeTest {

    @Test
    void emptyInput() {
        String text = "";
        Position caretPosition = new Position(0, 0);

        List<CompletionItem> result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("package", "import", "class");
    }

    @Test
    void incompleteRule_pattern() {
        String text = """
                class Foo {
                    rule R1 {
                        var a : /
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(2);
        caretPosition.setCharacter(17); // After the '/'

        List<CompletionItem> result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER"); // datasource name is IDENTIFIER
    }

    @Test
    void incompleteRule_consequence() {
        String text = """
                class Foo {
                    rule R1 {
                        var a : /as,
                        do { System.
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(3);
        caretPosition.setCharacter(20); // After the 'System.'

        List<CompletionItem> result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("out", "in", "gc()"); // System fields, methods
    }

    @Test
    void incompleteClass_consequence() {
        String text = """
                public class Foo {
                    public void bar() {
                        System.
                """;

        Position caretPosition = new Position();
        List<CompletionItem> result;

        // Test completion after 'System.'
        caretPosition.setLine(2);
        caretPosition.setCharacter(15);
        result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("out", "in", "gc()"); // System fields, methods
    }
}

package org.drools.drlx.completion;

import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.drools.drlx.completion.DrlxCompletionHelper.completionItemStrings;

class DrlxCompletionHelperIncompleteCodeTest {

    private final DrlxCompletionHelper helper = new DrlxCompletionHelper(
            new WorkspaceSemanticModel(new CurrentClassloaderProvider()));

    @Test
    void emptyInput() {
        String text = "";
        Position caretPosition = new Position(0, 0);

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("package", "import", "class");
    }

    @Test
    void incompleteRule_pattern() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(3);
        caretPosition.setCharacter(13); // After the '/'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("IDENTIFIER"); // datasource name is IDENTIFIER
    }

    @Test
    void incompleteRule_consequence_System() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(16); // After the 'System.'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("out", "in", "gc"); // System fields, methods
    }

    @Test
    void incompleteRule_consequence_SystemOut() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.out.
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(20); // After the 'System.out.'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("println"); // System.out fields, methods
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
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("out", "in", "gc"); // System fields, methods
    }

    @Test
    void incompleteRule_inlineCast() {
        String text = """
                import java.util.ArrayList;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { list#ArrayList#.
                """;

        Position caretPosition = new Position();
        List<CompletionItem> result;

        // Test completion after 'list#ArrayList#.'
        caretPosition.setLine(6);
        caretPosition.setCharacter(25);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("trimToSize");
        assertThat(completionItemStrings(result)).doesNotContain("removeRange"); // 'removeRange' is a protected method, so not included in suggestions
    }

    @Test
    void incompleteRule_BigDecimalLiteral() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { 10.5B.
                """;

        Position caretPosition = new Position();
        List<CompletionItem> result;

        // Test completion after '10.5B.'
        caretPosition.setLine(4);
        caretPosition.setCharacter(15);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("precision");
    }

    @Test
    void incompleteRule_PropertyAccessor() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        Person p = new Person("John", new Address("Tokyo"));
                        p.address.
                """;

        Position caretPosition = new Position();
        List<CompletionItem> result;

        // Test completion after 'p.address.'
        caretPosition.setLine(9);
        caretPosition.setCharacter(18);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("city", "getCity", "setCity"); // `city` can be directly accessed in mvel
    }
}

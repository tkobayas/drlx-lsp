package org.drools.drlx.completion;

import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.MemberCompletionProvider;
import org.drools.drlx.completion.semantic.SentinelExpressionTypeResolver;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.drools.drlx.completion.DrlxCompletionHelper.completionItemStrings;

class DrlxCompletionHelperTest {

    private final DrlxCompletionHelper helper = new DrlxCompletionHelper(
            new WorkspaceSemanticModel(new CurrentClassloaderProvider()),
            new SentinelExpressionTypeResolver(),
            new MemberCompletionProvider());

    @Test
    void testRuleDeclaration() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.out.println(a == 3.2B);}
                }
                """;

        Position caretPosition = new Position();
        List<CompletionItem> result;

        // Test completion at the beginning of the file
        caretPosition.setLine(0);
        caretPosition.setCharacter(0);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("package", "import", "unit"); // top level statement

        // Test completion before 'rule '
        caretPosition.setLine(2);
        caretPosition.setCharacter(0);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("rule");

        // Test completion after 'rule '
        caretPosition.setLine(2);
        caretPosition.setCharacter(5);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER"); // rule name is IDENTIFIER

        // Test completion in the middle of pattern - position after 'var a : /'
        // No import for MyUnit, so entry-point names can't be resolved; only keyword '#' is offered
        caretPosition.setLine(3);
        caretPosition.setCharacter(14);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).doesNotContain("IDENTIFIER");

        // Test completion after 'var '
        caretPosition.setLine(3);
        caretPosition.setCharacter(8);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER"); // variable name is IDENTIFIER

        // Test completion inside consequence block
        caretPosition.setLine(4);
        caretPosition.setCharacter(8);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("int", "var", "if"); // any java expressions
    }

    @Test
    void testClassDeclaration() {
        String text = """
                public class Foo {
                    public void bar() {
                        System.out.println("Hello");
                    }
                }
                """;

        Position caretPosition = new Position();
        List<CompletionItem> result;

        // Test completion at the beginning before 'public'
        caretPosition.setLine(0);
        caretPosition.setCharacter(0);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("public", "class", "interface", "enum", "package");

        // Test completion after 'public '
        caretPosition.setLine(0);
        caretPosition.setCharacter(7);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("class", "interface", "enum", "abstract", "final");

        // Test completion after 'public class '
        caretPosition.setLine(0);
        caretPosition.setCharacter(13);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER"); // class name

        // Test completion inside class body
        caretPosition.setLine(1);
        caretPosition.setCharacter(4);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("public", "private", "protected", "static", "final", "void", "int");

        // Test completion inside method body
        caretPosition.setLine(2);
        caretPosition.setCharacter(8);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("int", "var", "if", "for", "while", "return");
    }

    @Test
    void multipleRules() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.out.println(a);}
                }

                rule R2 {
                    var b : /bs,
                    do { System.out.println(b);}
                }
                """;

        Position caretPosition = new Position();

        // Test completion at the start of second rule
        caretPosition.setLine(7);
        caretPosition.setCharacter(0);
        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("rule");
    }

    @Test
    void entryPointCompletion_offersUnitFieldNames() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var p : /
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(13); // after '/'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        List<String> labels = completionItemStrings(result);
        assertThat(labels).contains("persons", "addresses");
        assertThat(labels).doesNotContain("IDENTIFIER");
    }

    @Test
    void entryPointCompletion_noUnitDeclaration_returnsEmpty() {
        String text = """
                rule R1 {
                    var p : /
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(1);
        caretPosition.setCharacter(12);

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        List<String> labels = completionItemStrings(result);
        assertThat(labels).doesNotContain("persons", "addresses");
    }

    @Test
    void oopathChunkCompletion_singleSegment_offersNavigableProperties() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var a : /persons/
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(21); // after '/persons/'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        List<String> labels = completionItemStrings(result);
        assertThat(labels).contains("address", "previousAddresses");
        assertThat(labels).doesNotContain("name", "age");
    }

    @Test
    void oopathChunkCompletion_multiSegment_offersNextLevelProperties() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var c : /persons/address/
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(29); // after '/persons/address/'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        List<String> labels = completionItemStrings(result);
        assertThat(labels).contains("country");
        assertThat(labels).doesNotContain("city");
    }

    @Test
    void oopathChunkCompletion_collectionNavigation_unwrapsElementType() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var c : /persons/previousAddresses/
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(39); // after '/persons/previousAddresses/'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        List<String> labels = completionItemStrings(result);
        assertThat(labels).contains("country");
        assertThat(labels).doesNotContain("city");
    }

    @Test
    void oopathChunkCompletion_noUnitDeclaration_returnsEmpty() {
        String text = """
                rule R1 {
                    var a : /persons/
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(1);
        caretPosition.setCharacter(21);

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        List<String> labels = completionItemStrings(result);
        assertThat(labels).doesNotContain("address", "previousAddresses");
    }

    @Test
    void oopathChunkCompletion_unknownEntryPoint_returnsEmpty() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var a : /unknown/
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(21); // after '/unknown/'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        List<String> labels = completionItemStrings(result);
        assertThat(labels).doesNotContain("address", "previousAddresses", "country");
    }

    @Test
    void constraintCompletion_rootConstraint_offersAllProperties() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var p : /persons[
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(21); // after '['

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        List<String> labels = completionItemStrings(result);
        assertThat(labels).contains("name", "age", "address", "previousAddresses", "this");
    }

    @Test
    void constraintCompletion_chunkConstraint_offersChunkTypeProperties() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var a : /persons/address[
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(29); // after '/persons/address['

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        List<String> labels = completionItemStrings(result);
        assertThat(labels).contains("city", "country", "this");
        assertThat(labels).doesNotContain("name", "age");
    }

    @Test
    void constraintCompletion_collectionChunk_unwrapsElementType() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var a : /persons/previousAddresses[
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(39); // after '/persons/previousAddresses['

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        List<String> labels = completionItemStrings(result);
        assertThat(labels).contains("city", "country", "this");
        assertThat(labels).doesNotContain("name", "age");
    }

    @Test
    void constraintCompletion_noUnitDeclaration_returnsEmpty() {
        String text = """
                rule R1 {
                    var p : /persons[
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(1);
        caretPosition.setCharacter(21); // after '['

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        List<String> labels = completionItemStrings(result);
        assertThat(labels).doesNotContain("name", "age", "address");
    }

    @Test
    void constraintCompletion_unknownEntryPoint_returnsEmpty() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var p : /unknown[
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(21); // after '/unknown['

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        List<String> labels = completionItemStrings(result);
        assertThat(labels).doesNotContain("name", "age", "address");
    }

    @Test
    void testCreateCompletionItem() {
        CompletionItem item = DrlxCompletionHelper.createCompletionItem("test", org.eclipse.lsp4j.CompletionItemKind.Keyword);
        
        assertThat(item.getLabel()).isEqualTo("test");
        assertThat(item.getInsertText()).isEqualTo("test");
        assertThat(item.getKind()).isEqualTo(org.eclipse.lsp4j.CompletionItemKind.Keyword);
    }
}

package org.drools.drlx.completion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.drools.drlx.completion.DRLXCompletionHelper.completionItemStrings;

class DRLXCompletionHelperTest {

    @Test
    void getCompletionItems_testDrlxRule() {
        String text = """
                rule R1 {
                   var a : /as,
                   do { System.out.println(a == 3.2B);}
                }
                """;

        Position caretPosition = new Position();
        List<CompletionItem> result;

        // Test completion at the beginning of the rule
        caretPosition.setLine(0);
        caretPosition.setCharacter(0);
        result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        System.out.println(completionItemStrings(result));
        assertThat(completionItemStrings(result)).contains("rule", "class", "package"); // top level statement

        // Test completion after 'rule '
        caretPosition.setLine(0);
        caretPosition.setCharacter(5);
        result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        System.out.println(completionItemStrings(result));
        assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER"); // rule name is IDENTIFIER

        // Test completion in the middle of pattern - position after 'var a : /'
        caretPosition.setLine(1);
        caretPosition.setCharacter(12);
        result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        System.out.println(completionItemStrings(result));
        assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER"); // datasource name is IDENTIFIER

        // Test completion after 'var '
        caretPosition.setLine(1);
        caretPosition.setCharacter(7);
        result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        System.out.println(completionItemStrings(result));
        assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER"); // variable name is IDENTIFIER

        // Test completion inside consequence block
        caretPosition.setLine(2);
        caretPosition.setCharacter(8);
        result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        System.out.println(completionItemStrings(result));
        assertThat(completionItemStrings(result)).contains("int", "var", "if"); // any java expressions
    }

    @Test
    void getCompletionItems_class() {
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
        result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        System.out.println(completionItemStrings(result));
        assertThat(completionItemStrings(result)).contains("public", "class", "interface", "enum", "rule", "package");

        // Test completion after 'public '
        caretPosition.setLine(0);
        caretPosition.setCharacter(7);
        result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        System.out.println(completionItemStrings(result));
        assertThat(completionItemStrings(result)).contains("class", "interface", "enum", "abstract", "final");

        // Test completion after 'public class '
        caretPosition.setLine(0);
        caretPosition.setCharacter(13);
        result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        System.out.println(completionItemStrings(result));
        assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER"); // class name

        // Test completion inside class body
        caretPosition.setLine(1);
        caretPosition.setCharacter(4);
        result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        System.out.println(completionItemStrings(result));
        assertThat(completionItemStrings(result)).contains("public", "private", "protected", "static", "final", "void", "int");

        // Test completion inside method body
        caretPosition.setLine(2);
        caretPosition.setCharacter(8);
        result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        System.out.println(completionItemStrings(result));
        assertThat(completionItemStrings(result)).contains("int", "var", "if", "for", "while", "return");
    }

    @Test
    void getCompletionItems_multipleRules() {
        String text = """
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
        caretPosition.setLine(5);
        caretPosition.setCharacter(0);
        List<CompletionItem> result = DRLXCompletionHelper.getCompletionItems(text, caretPosition);
        System.out.println(completionItemStrings(result));
        assertThat(completionItemStrings(result)).contains("rule");
    }

    @Test
    void createCompletionItem_test() {
        CompletionItem item = DRLXCompletionHelper.createCompletionItem("test", org.eclipse.lsp4j.CompletionItemKind.Keyword);
        
        assertThat(item.getLabel()).isEqualTo("test");
        assertThat(item.getInsertText()).isEqualTo("test");
        assertThat(item.getKind()).isEqualTo(org.eclipse.lsp4j.CompletionItemKind.Keyword);
    }
}

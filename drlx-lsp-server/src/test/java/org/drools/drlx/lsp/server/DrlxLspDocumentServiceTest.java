package org.drools.drlx.lsp.server;

import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.drools.drlx.completion.DRLXCompletionHelper.completionItemStrings;
import static org.drools.drlx.lsp.server.TestHelperMethods.getDrlxLspDocumentService;

class DrlxLspDocumentServiceTest {

    @Test
    void getCompletionItems_emptyText() {
        DrlxLspDocumentService drlxLspDocumentService = getDrlxLspDocumentService("");

        CompletionParams completionParams = new CompletionParams();
        completionParams.setTextDocument(new TextDocumentIdentifier("myDocument"));
        Position caretPosition = new Position();
        caretPosition.setCharacter(0);
        caretPosition.setLine(0);
        completionParams.setPosition(caretPosition);

        List<CompletionItem> result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(completionItemStrings(result)).contains("package", "import", "class"); // top level statement
    }

    @Test
    void getCompletionItems_drlxRule() {
        String drlx = """
                class Foo {
                    rule R1 {
                        var a : /as,
                        do { System.out.println(a == 3.2B);}
                    }
                }
                """;

        DrlxLspDocumentService drlxLspDocumentService = getDrlxLspDocumentService(drlx);

        CompletionParams completionParams = new CompletionParams();
        completionParams.setTextDocument(new TextDocumentIdentifier("myDocument"));

        // Test completion at beginning of file
        completionParams.setPosition(new Position(0, 0));
        List<CompletionItem> result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(completionItemStrings(result)).contains("package", "import", "class");

        // Test completion after 'rule '
        completionParams.setPosition(new Position(1, 9));
        result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER");

        // Test completion after 'var '
        completionParams.setPosition(new Position(2, 12));
        result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER");

        // Test completion after '/'
        completionParams.setPosition(new Position(2, 17));
        result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER");

        // Test completion inside do block
        completionParams.setPosition(new Position(3, 12));
        result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(result).isNotEmpty();
        // Should have Java keywords
        assertThat(completionItemStrings(result)).contains("int", "var", "if");
    }

    @Test
    void getCompletionItems_multipleRules() {
        String drlx = """
                class Foo {
                    rule R1 {
                        var a : /as,
                        do { System.out.println(a);}
                    }
                    
                    rule R2 {
                        var b : /bs,
                        do { System.out.println(b);}
                    }
                }
                """;

        DrlxLspDocumentService drlxLspDocumentService = getDrlxLspDocumentService(drlx);

        CompletionParams completionParams = new CompletionParams();
        completionParams.setTextDocument(new TextDocumentIdentifier("myDocument"));

        // Test completion between rules
        completionParams.setPosition(new Position(5, 0));
        List<CompletionItem> result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(completionItemStrings(result)).contains("rule");

        // Test completion in second rule
        completionParams.setPosition(new Position(7, 17));
        result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER");
    }

    @Test
    void getCompletionItems_incompleteRule() {
        String drlx = """
                class Foo {
                    rule R1 {
                        var a : /
                """;

        DrlxLspDocumentService drlxLspDocumentService = getDrlxLspDocumentService(drlx);

        CompletionParams completionParams = new CompletionParams();
        completionParams.setTextDocument(new TextDocumentIdentifier("myDocument"));
        
        // Test completion after incomplete '/'
        completionParams.setPosition(new Position(2, 17));
        List<CompletionItem> result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER");
    }
}
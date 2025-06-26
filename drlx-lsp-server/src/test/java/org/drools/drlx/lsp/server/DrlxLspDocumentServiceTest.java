package org.drools.drlx.lsp.server;

import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.drools.drlx.lsp.server.TestHelperMethods.getDrlxLspDocumentService;

class DrlxLspDocumentServiceTest {

    @Test
    void getCompletionItems() {
        DrlxLspDocumentService drlxLspDocumentService = getDrlxLspDocumentService("");

        CompletionParams completionParams = new CompletionParams();
        completionParams.setTextDocument(new TextDocumentIdentifier("myDocument"));
        Position caretPosition = new Position();
        caretPosition.setCharacter(0);
        caretPosition.setLine(0);
        completionParams.setPosition(caretPosition);

        List<CompletionItem> result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(result.stream().map(CompletionItem::getInsertText).anyMatch("rule"::equals)).isTrue();
    }

    @Test
    void getCompletionItems_drlxRule() {
        String drlx = """
                rule R1 {
                   var a : /as,
                   do { System.out.println(a == 3.2B);}
                }
                """;

        DrlxLspDocumentService drlxLspDocumentService = getDrlxLspDocumentService(drlx);

        CompletionParams completionParams = new CompletionParams();
        completionParams.setTextDocument(new TextDocumentIdentifier("myDocument"));

        // Test completion at beginning of file
        completionParams.setPosition(new Position(0, 0));
        List<CompletionItem> result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(hasItem(result, "rule")).isTrue();
        assertThat(hasItem(result, "package")).isTrue();

        // Test completion after 'rule '
        completionParams.setPosition(new Position(0, 5));
        result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(hasItem(result, "IDENTIFIER")).isTrue();

        // Test completion after 'var '
        completionParams.setPosition(new Position(1, 7));
        result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(hasItem(result, "IDENTIFIER")).isTrue();

        // Test completion after '/'
        completionParams.setPosition(new Position(1, 12));
        result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(hasItem(result, "IDENTIFIER")).isTrue();

        // Test completion inside do block
        completionParams.setPosition(new Position(2, 8));
        result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(result).isNotEmpty();
        // Should have Java keywords
        assertThat(result.stream().map(CompletionItem::getInsertText).anyMatch(s -> 
            s.equals("int") || s.equals("var") || s.equals("if"))).isTrue();
    }

    @Test
    void getCompletionItems_multipleRules() {
        String drlx = """
                rule R1 {
                   var a : /as,
                   do { System.out.println(a);}
                }
                
                rule R2 {
                   var b : /bs,
                   do { System.out.println(b);}
                }
                """;

        DrlxLspDocumentService drlxLspDocumentService = getDrlxLspDocumentService(drlx);

        CompletionParams completionParams = new CompletionParams();
        completionParams.setTextDocument(new TextDocumentIdentifier("myDocument"));

        // Test completion between rules
        completionParams.setPosition(new Position(4, 0));
        List<CompletionItem> result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(hasItem(result, "rule")).isTrue();

        // Test completion in second rule
        completionParams.setPosition(new Position(6, 7));
        result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(hasItem(result, "IDENTIFIER")).isTrue();
    }

    @Test
    void getCompletionItems_incompleteRule() {
        String drlx = """
                rule R1 {
                   var a : /
                """;

        DrlxLspDocumentService drlxLspDocumentService = getDrlxLspDocumentService(drlx);

        CompletionParams completionParams = new CompletionParams();
        completionParams.setTextDocument(new TextDocumentIdentifier("myDocument"));
        
        // Test completion after incomplete '/'
        completionParams.setPosition(new Position(1, 12));
        List<CompletionItem> result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(hasItem(result, "IDENTIFIER")).isTrue();
    }

    private boolean hasItem(List<CompletionItem> result, String text) {
        return result.stream().map(CompletionItem::getInsertText).anyMatch(text::equals);
    }
}
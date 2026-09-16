package org.drools.drlx.lsp.server;

import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.drools.drlx.completion.DrlxCompletionHelper.completionItemStrings;
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

        // Test completion after '/' — no unit declaration, so no entry-point names
        completionParams.setPosition(new Position(2, 17));
        result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(completionItemStrings(result)).doesNotContain("IDENTIFIER");

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

        // Test completion in second rule after '/' — no unit declaration
        completionParams.setPosition(new Position(7, 17));
        result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(completionItemStrings(result)).doesNotContain("IDENTIFIER");
    }

    @Test
    void getCompletionItems_entryPointBindingPropertyAccess() {
        String drlx = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p.
                """;

        DrlxLspDocumentService drlxLspDocumentService = getDrlxLspDocumentService(drlx);

        CompletionParams completionParams = new CompletionParams();
        completionParams.setTextDocument(new TextDocumentIdentifier("myDocument"));
        completionParams.setPosition(new Position(7, 11)); // After 'p.'

        List<CompletionItem> result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(completionItemStrings(result)).contains("age", "name", "address", "getAge", "getName", "getAddress");
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
        
        // Test completion after incomplete '/' — no unit declaration
        completionParams.setPosition(new Position(2, 17));
        List<CompletionItem> result = drlxLspDocumentService.getCompletionItems(completionParams);
        assertThat(completionItemStrings(result)).doesNotContain("IDENTIFIER");
    }

    @Test
    void hover_oopathBinding() throws Exception {
        String drlx = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p }
                }
                """;

        DrlxLspDocumentService service = getDrlxLspDocumentService(drlx);

        HoverParams params = new HoverParams();
        params.setTextDocument(new TextDocumentIdentifier("myDocument"));
        params.setPosition(new Position(7, 9));

        Hover hover = service.hover(params).get();

        assertThat(hover).isNotNull();
        String md = hover.getContents().getRight().getValue();
        assertThat(md).contains("Person");
        assertThat(md).contains("name");
    }

    @Test
    void definition_oopathBinding() throws Exception {
        String drlx = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p }
                }
                """;

        DrlxLspDocumentService service = getDrlxLspDocumentService(drlx);

        DefinitionParams params = new DefinitionParams();
        params.setTextDocument(new TextDocumentIdentifier("myDocument"));
        params.setPosition(new Position(7, 9)); // "p" in "do { p }"

        Either<List<? extends Location>, List<? extends LocationLink>> result = service.definition(params).get();

        assertThat(result.getLeft()).hasSize(1);
        assertThat(result.getLeft().get(0).getRange().getStart().getLine()).isEqualTo(6);
    }

    @Test
    void references_findsBindingUses() throws Exception {
        String drlx = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p }
                }
                """;

        DrlxLspDocumentService service = getDrlxLspDocumentService(drlx);

        ReferenceParams params = new ReferenceParams();
        params.setTextDocument(new TextDocumentIdentifier("myDocument"));
        params.setPosition(new Position(7, 9)); // "p" in "do { p }"
        params.setContext(new ReferenceContext(true));

        List<? extends Location> refs = service.references(params).get();

        assertThat(refs).hasSize(2);
        assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 6);
        assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 7);
    }

    @Test
    void documentSymbol_returnsSymbolsForDrlxFile() throws Exception {
        String drlx = """
                import org.drools.drlx.domain.Person;

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

        DrlxLspDocumentService service = getDrlxLspDocumentService(drlx);

        DocumentSymbolParams params = new DocumentSymbolParams(new TextDocumentIdentifier("myDocument"));
        List<Either<SymbolInformation, DocumentSymbol>> result = service.documentSymbol(params).get();

        assertThat(result).hasSize(3);
        assertThat(result.get(0).isRight()).isTrue();
        assertThat(result.get(0).getRight().getName()).isEqualTo("MyUnit");
        assertThat(result.get(0).getRight().getKind()).isEqualTo(SymbolKind.Namespace);

        assertThat(result.get(1).isRight()).isTrue();
        assertThat(result.get(1).getRight().getName()).isEqualTo("R1");
        assertThat(result.get(1).getRight().getKind()).isEqualTo(SymbolKind.Method);

        assertThat(result.get(2).isRight()).isTrue();
        assertThat(result.get(2).getRight().getName()).isEqualTo("R2");
        assertThat(result.get(2).getRight().getKind()).isEqualTo(SymbolKind.Method);
    }

    @Test
    void foldingRange_returnsRangesForDrlxFile() throws Exception {
        String content =
                "/*\n"                              // 0
                + " * multi line comment\n"         // 1
                + " */\n"                           // 2
                + "import org.example.Person;\n"    // 3
                + "import org.example.Order;\n"     // 4
                + "unit MyUnit;\n"                  // 5
                + "rule R1 {\n"                     // 6
                + "    var p : /persons,\n"         // 7
                + "    do {\n"                      // 8
                + "        System.out.println(p);\n"// 9
                + "    }\n"                         // 10
                + "}\n";                            // 11

        DrlxLspDocumentService service = getDrlxLspDocumentService(content);

        FoldingRangeRequestParams params = new FoldingRangeRequestParams(
                new TextDocumentIdentifier("myDocument"));

        List<FoldingRange> ranges = service.foldingRange(params).get();

        assertThat(ranges)
                .extracting(FoldingRange::getStartLine, FoldingRange::getEndLine, FoldingRange::getKind)
                .contains(
                        tuple(0, 2, FoldingRangeKind.Comment),
                        tuple(3, 4, FoldingRangeKind.Imports),
                        tuple(6, 11, FoldingRangeKind.Region));
    }
}
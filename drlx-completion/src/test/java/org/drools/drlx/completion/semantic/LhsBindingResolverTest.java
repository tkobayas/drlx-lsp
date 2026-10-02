package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.junit.jupiter.api.Test;
import org.antlr.v4.runtime.tree.ParseTree;

import static org.assertj.core.api.Assertions.assertThat;

class LhsBindingResolverTest {

    private static final String DRLX_TEXT = """
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;
            rule R {
                var p : /persons,
                do { p. }
            }
            """;

    private static WorkspaceSemanticModel model() {
        return new WorkspaceSemanticModel(new CurrentClassloaderProvider());
    }

    /** Find the token index for the first occurrence of the given identifier text. */
    private static int tokenIndexOf(String text, String identifier) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        for (var t : tokens.getTokens()) {
            if (t.getType() == DrlxLexer.IDENTIFIER && identifier.equals(t.getText())) {
                return t.getTokenIndex();
            }
        }
        return 0;
    }

    @Test
    void resolve_returnsBindingAtCaretPosition() {
        int tokenIndex = tokenIndexOf(DRLX_TEXT, "p");
        VisibleSymbols symbols = LhsBindingResolver.resolve(DRLX_TEXT, tokenIndex, model());
        assertThat(symbols.lookup("p")).isPresent();
    }

    @Test
    void resolve_nullText_returnsEmpty() {
        VisibleSymbols symbols = LhsBindingResolver.resolve(null, 0, model());
        assertThat(symbols.isEmpty()).isTrue();
    }

    @Test
    void resolve_emptyText_returnsEmpty() {
        VisibleSymbols symbols = LhsBindingResolver.resolve("", 0, model());
        assertThat(symbols.isEmpty()).isTrue();
    }

    @Test
    void resolveAll_returnsBindingWithoutTokenIndex() {
        VisibleSymbols symbols = LhsBindingResolver.resolveAll(DRLX_TEXT, model());
        assertThat(symbols.lookup("p")).isPresent();
    }

    @Test
    void resolveAll_nullText_returnsEmpty() {
        VisibleSymbols symbols = LhsBindingResolver.resolveAll(null, model());
        assertThat(symbols.isEmpty()).isTrue();
    }

    @Test
    void resolve_withContext_returnsBinding() {
        WorkspaceSemanticModel m = model();
        int tokenIndex = tokenIndexOf(DRLX_TEXT, "p");
        ANTLRInputStream input = new ANTLRInputStream(DRLX_TEXT);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        var parseTree = parser.drlxStart();
        CompletionContext ctx = m.createContext(parser, parseTree, tokenIndex);

        VisibleSymbols symbols = LhsBindingResolver.resolve(ctx);
        assertThat(symbols.lookup("p")).isPresent();
    }

    @Test
    void resolve_nullContext_returnsEmpty() {
        VisibleSymbols symbols = LhsBindingResolver.resolve((CompletionContext) null);
        assertThat(symbols.isEmpty()).isTrue();
    }
}

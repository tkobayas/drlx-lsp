package org.drools.drlx.completion.semantic;

import java.util.Optional;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TolerantVisitorTypeResolverTest {

    private final WorkspaceSemanticModel model =
            new WorkspaceSemanticModel(new CurrentClassloaderProvider());
    private final TolerantVisitorTypeResolver resolver = new TolerantVisitorTypeResolver();

    @Test
    void resolvesSystemDot() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.
                """;
        Optional<SemanticType> result = resolveAt(text, 4, 16);

        assertThat(result).isPresent();
        assertThat(result.get().isReferenceType()).isTrue();
        assertThat(result.get().resolvedType().describe()).isEqualTo("java.lang.System");
    }

    @Test
    void resolvesSystemOutDot() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.out.
                """;
        Optional<SemanticType> result = resolveAt(text, 4, 20);

        assertThat(result).isPresent();
        assertThat(result.get().isReferenceType()).isTrue();
        assertThat(result.get().resolvedType().describe()).isEqualTo("java.io.PrintStream");
    }

    @Test
    void returnsEmptyWhenScopeTokenIndexNegative() {
        CompletionExpression expr = CompletionExpression.fromCaretPosition(
                parse("x"), 0);
        // scopeTokenIndex = 0 - 2 = -2
        Optional<SemanticType> result = resolver.resolve(expr, VisibleSymbols.empty(), model);

        assertThat(result).isEmpty();
    }

    private ParseTree parse(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        return parser.drlxStart();
    }

    private Optional<SemanticType> resolveAt(String text, int line, int col) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        ParseTree tree = parser.drlxStart();

        int caretTokenIndex = computeTokenIndex(parser, line + 1, col);
        CompletionExpression expr = CompletionExpression.fromCaretPosition(tree, caretTokenIndex);

        return resolver.resolve(expr, VisibleSymbols.empty(), model);
    }

    private int computeTokenIndex(DrlxParser parser, int row, int col) {
        CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();
        int tokenIndex = 0;
        for (Token token : tokens.getTokens()) {
            if (token.getLine() > row || (token.getLine() == row && token.getCharPositionInLine() >= col)) {
                break;
            }
            tokenIndex++;
        }
        return tokenIndex;
    }
}

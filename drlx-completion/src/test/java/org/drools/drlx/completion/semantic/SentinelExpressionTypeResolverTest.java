package org.drools.drlx.completion.semantic;

import java.util.Optional;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mvel3.parser.MvelParser;
import org.mvel3.transpiler.MVELTranspiler;

import static org.assertj.core.api.Assertions.assertThat;

class SentinelExpressionTypeResolverTest {

    private final WorkspaceSemanticModel model =
            new WorkspaceSemanticModel(new CurrentClassloaderProvider());
    private final SentinelExpressionTypeResolver resolver = new SentinelExpressionTypeResolver();

    @BeforeAll
    static void setup() {
        MvelParser.Factory.USE_ANTLR = true;
        MVELTranspiler.ENABLE_REWRITE = false;
    }

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
        assertThat(result.get().resolvedType().describe()).isEqualTo("java.io.PrintStream");
    }

    @Test
    void resolvesBigDecimalLiteral() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { 10.5B.
                """;
        Optional<SemanticType> result = resolveAt(text, 4, 15);

        assertThat(result).isPresent();
        assertThat(result.get().resolvedType().describe()).isEqualTo("java.math.BigDecimal");
    }

    @Test
    void returnsEmptyWhenScopeTokenIndexNegative() {
        DrlxParser parser = createParser("x");
        ParseTree tree = parser.drlxStart();
        CompletionExpression expr = CompletionExpression.fromCaretPosition(parser, tree, 0);
        Optional<SemanticType> result = resolver.resolve(expr, VisibleSymbols.empty(), model);

        assertThat(result).isEmpty();
    }

    private Optional<SemanticType> resolveAt(String text, int line, int col) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        ParseTree tree = parser.drlxStart();

        int caretTokenIndex = computeTokenIndex(parser, line + 1, col);
        CompletionExpression expr = CompletionExpression.fromCaretPosition(parser, tree, caretTokenIndex);

        return resolver.resolve(expr, VisibleSymbols.empty(), model);
    }

    private DrlxParser createParser(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        return new DrlxParser(tokens);
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

package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.drools.drlx.parser.DrlxLexer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenWalkerTest {

    @Test
    void simpleIdentifierDot() {
        CommonTokenStream tokens = tokenize("System.");
        int boundary = TokenWalker.findExpressionBoundary(tokens, 1);
        assertThat(boundary).isEqualTo(0);
    }

    @Test
    void chainedDotAccess() {
        CommonTokenStream tokens = tokenize("System.out.");
        int boundary = TokenWalker.findExpressionBoundary(tokens, 3);
        assertThat(boundary).isEqualTo(0);
    }

    @Test
    void afterSemicolon() {
        CommonTokenStream tokens = tokenize("x = 1; System.");
        int dotIndex = findLastDot(tokens);
        int boundary = TokenWalker.findExpressionBoundary(tokens, dotIndex);
        assertThat(tokens.get(boundary).getText()).isEqualTo("System");
    }

    @Test
    void afterOpenBrace() {
        CommonTokenStream tokens = tokenize("{ System.");
        int dotIndex = findLastDot(tokens);
        int boundary = TokenWalker.findExpressionBoundary(tokens, dotIndex);
        assertThat(tokens.get(boundary).getText()).isEqualTo("System");
    }

    @Test
    void methodCallInChain() {
        CommonTokenStream tokens = tokenize("list.get(0).");
        int dotIndex = findLastDot(tokens);
        int boundary = TokenWalker.findExpressionBoundary(tokens, dotIndex);
        assertThat(tokens.get(boundary).getText()).isEqualTo("list");
    }

    @Test
    void inlineCast() {
        CommonTokenStream tokens = tokenize("list#ArrayList#.");
        int dotIndex = findLastDot(tokens);
        int boundary = TokenWalker.findExpressionBoundary(tokens, dotIndex);
        assertThat(tokens.get(boundary).getText()).isEqualTo("list");
    }

    @Test
    void dotAtStartOfStream() {
        CommonTokenStream tokens = tokenize(".");
        int boundary = TokenWalker.findExpressionBoundary(tokens, 0);
        assertThat(boundary).isEqualTo(0);
    }

    private CommonTokenStream tokenize(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        return tokens;
    }

    private int findLastDot(CommonTokenStream tokens) {
        int lastDot = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).getText().equals(".")) {
                lastDot = i;
            }
        }
        return lastDot;
    }
}

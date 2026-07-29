package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionExpressionTest {

    @Test
    void fromCaretPositionNormal() {
        CompletionExpression expr = CompletionExpression.fromCaretPosition(
                parse("System."), 2);

        assertThat(expr.caretTokenIndex()).isEqualTo(2);
        assertThat(expr.scopeTokenIndex()).isEqualTo(0);
        assertThat(expr.parseTree()).isNotNull();
    }

    @Test
    void fromCaretPositionAtZero() {
        CompletionExpression expr = CompletionExpression.fromCaretPosition(
                parse("x"), 0);

        assertThat(expr.caretTokenIndex()).isEqualTo(0);
        assertThat(expr.scopeTokenIndex()).isEqualTo(-2);
    }

    @Test
    void fromCaretPositionAtOne() {
        CompletionExpression expr = CompletionExpression.fromCaretPosition(
                parse(".x"), 1);

        assertThat(expr.caretTokenIndex()).isEqualTo(1);
        assertThat(expr.scopeTokenIndex()).isEqualTo(-1);
    }

    @Test
    void visibleSymbolsEmptyReturnsEmptyOnLookup() {
        VisibleSymbols symbols = VisibleSymbols.empty();
        assertThat(symbols.lookup("anything")).isEmpty();
    }

    private ParseTree parse(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        return parser.drlxStart();
    }
}

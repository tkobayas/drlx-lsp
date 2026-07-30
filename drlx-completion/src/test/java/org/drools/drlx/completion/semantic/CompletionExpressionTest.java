package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionExpressionTest {

    @Test
    void fromCaretPositionNormal() {
        DrlxParser parser = createParser("System.");
        CompletionExpression expr = CompletionExpression.fromCaretPosition(
                parser, parser.drlxStart(), 2);

        assertThat(expr.caretTokenIndex()).isEqualTo(2);
        assertThat(expr.scopeTokenIndex()).isEqualTo(0);
        assertThat(expr.parseTree()).isNotNull();
        assertThat(expr.parser()).isSameAs(parser);
    }

    @Test
    void fromCaretPositionAtZero() {
        DrlxParser parser = createParser("x");
        CompletionExpression expr = CompletionExpression.fromCaretPosition(
                parser, parser.drlxStart(), 0);

        assertThat(expr.caretTokenIndex()).isEqualTo(0);
        assertThat(expr.scopeTokenIndex()).isEqualTo(-2);
    }

    @Test
    void fromCaretPositionAtOne() {
        DrlxParser parser = createParser(".x");
        CompletionExpression expr = CompletionExpression.fromCaretPosition(
                parser, parser.drlxStart(), 1);

        assertThat(expr.caretTokenIndex()).isEqualTo(1);
        assertThat(expr.scopeTokenIndex()).isEqualTo(-1);
    }

    @Test
    void visibleSymbolsEmptyReturnsEmptyOnLookup() {
        VisibleSymbols symbols = VisibleSymbols.empty();
        assertThat(symbols.lookup("anything")).isEmpty();
    }

    private DrlxParser createParser(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        return new DrlxParser(tokens);
    }
}

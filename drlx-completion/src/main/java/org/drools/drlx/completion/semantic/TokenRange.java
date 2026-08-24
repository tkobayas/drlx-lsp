package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.Token;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public record TokenRange(int startLine, int startCol, int endLine, int endCol) {

    public static TokenRange fromAntlrToken(Token token, int length) {
        int line = token.getLine() - 1;
        int col = token.getCharPositionInLine();
        return new TokenRange(line, col, line, col + length);
    }

    public Range toLspRange() {
        return new Range(new Position(startLine, startCol), new Position(endLine, endCol));
    }
}

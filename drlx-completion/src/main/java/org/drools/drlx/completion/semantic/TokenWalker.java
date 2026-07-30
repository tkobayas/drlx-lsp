package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.drools.drlx.parser.DrlxLexer;

public class TokenWalker {

    public static int findExpressionBoundary(CommonTokenStream tokens, int dotTokenIndex) {
        int index = dotTokenIndex - 1;
        int parenDepth = 0;
        int bracketDepth = 0;

        while (index >= 0) {
            Token token = tokens.get(index);
            int type = token.getType();

            if (parenDepth > 0) {
                if (type == DrlxLexer.LPAREN) {
                    parenDepth--;
                } else if (type == DrlxLexer.RPAREN) {
                    parenDepth++;
                }
                index--;
                continue;
            }

            if (bracketDepth > 0) {
                if (type == DrlxLexer.LBRACK) {
                    bracketDepth--;
                } else if (type == DrlxLexer.RBRACK) {
                    bracketDepth++;
                }
                index--;
                continue;
            }

            if (type == DrlxLexer.RPAREN) {
                parenDepth++;
                index--;
                continue;
            }
            if (type == DrlxLexer.RBRACK) {
                bracketDepth++;
                index--;
                continue;
            }

            if (isExpressionToken(type)) {
                index--;
                continue;
            }

            return index + 1;
        }

        return 0;
    }

    private static boolean isExpressionToken(int type) {
        return type == DrlxLexer.IDENTIFIER
                || type == DrlxLexer.DOT
                || type == DrlxLexer.HASH
                || type == DrlxLexer.BANG
                || type == DrlxLexer.EXCL_DOT
                || type == DrlxLexer.DECIMAL_LITERAL
                || type == DrlxLexer.FLOAT_LITERAL
                || type == DrlxLexer.HEX_LITERAL
                || type == DrlxLexer.BigDecimalLiteral
                || type == DrlxLexer.BigIntegerLiteral
                || type == DrlxLexer.STRING_LITERAL
                || type == DrlxLexer.CHAR_LITERAL
                || type == DrlxLexer.BOOL_LITERAL
                || type == DrlxLexer.NULL_LITERAL
                || type == DrlxLexer.THIS
                || type == DrlxLexer.NEW;
    }
}

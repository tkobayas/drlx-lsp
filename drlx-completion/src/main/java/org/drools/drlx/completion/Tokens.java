package org.drools.drlx.completion;

import java.util.Set;

import org.antlr.v4.runtime.Token;
import org.drools.drlx.parser.DrlxLexer;

public class Tokens {

    public static Set<Integer> IGNORED = Set.of(
            Token.EPSILON, Token.EOF, Token.INVALID_TYPE,

            DrlxLexer.DECIMAL_LITERAL, DrlxLexer.HEX_LITERAL,
            DrlxLexer.OCT_LITERAL, DrlxLexer.BINARY_LITERAL, DrlxLexer.FLOAT_LITERAL, DrlxLexer.HEX_FLOAT_LITERAL,
            DrlxLexer.BOOL_LITERAL, DrlxLexer.CHAR_LITERAL, DrlxLexer.STRING_LITERAL, DrlxLexer.TEXT_BLOCK,
            DrlxLexer.NULL_LITERAL, DrlxLexer.LPAREN, DrlxLexer.RPAREN, DrlxLexer.LBRACE, DrlxLexer.RBRACE, DrlxLexer.LBRACK,
            DrlxLexer.RBRACK, DrlxLexer.SEMI, DrlxLexer.COMMA, DrlxLexer.DOT, DrlxLexer.ASSIGN, DrlxLexer.GT, DrlxLexer.LT,
            DrlxLexer.BANG, DrlxLexer.TILDE, DrlxLexer.QUESTION, DrlxLexer.COLON, DrlxLexer.EQUAL, DrlxLexer.LE, DrlxLexer.GE,
            DrlxLexer.NOTEQUAL, DrlxLexer.AND, DrlxLexer.OR, DrlxLexer.INC, DrlxLexer.DEC, DrlxLexer.ADD, DrlxLexer.SUB, DrlxLexer.MUL,
            DrlxLexer.DIV, DrlxLexer.BITAND, DrlxLexer.BITOR, DrlxLexer.CARET, DrlxLexer.MOD, DrlxLexer.ADD_ASSIGN, DrlxLexer.SUB_ASSIGN,
            DrlxLexer.MUL_ASSIGN, DrlxLexer.DIV_ASSIGN, DrlxLexer.AND_ASSIGN, DrlxLexer.OR_ASSIGN, DrlxLexer.XOR_ASSIGN,
            DrlxLexer.MOD_ASSIGN, DrlxLexer.LSHIFT_ASSIGN, DrlxLexer.RSHIFT_ASSIGN, DrlxLexer.URSHIFT_ASSIGN,
            DrlxLexer.ARROW, DrlxLexer.COLONCOLON, DrlxLexer.AT, DrlxLexer.ELLIPSIS, DrlxLexer.WS, DrlxLexer.COMMENT,
            DrlxLexer.LINE_COMMENT, DrlxLexer.IDENTIFIER
    );
}
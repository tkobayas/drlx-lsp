package org.drools.drlx.completion;

import java.util.Set;

import org.antlr.v4.runtime.Token;
import org.drools.drlx.parser.DRLXLexer;

public class Tokens {

    public static Set<Integer> IGNORED = Set.of(
            Token.EPSILON, Token.EOF, Token.INVALID_TYPE,

            DRLXLexer.DECIMAL_LITERAL, DRLXLexer.HEX_LITERAL,
            DRLXLexer.OCT_LITERAL, DRLXLexer.BINARY_LITERAL, DRLXLexer.FLOAT_LITERAL, DRLXLexer.HEX_FLOAT_LITERAL,
            DRLXLexer.BOOL_LITERAL, DRLXLexer.CHAR_LITERAL, DRLXLexer.STRING_LITERAL, DRLXLexer.TEXT_BLOCK,
            DRLXLexer.NULL_LITERAL, DRLXLexer.LPAREN, DRLXLexer.RPAREN, DRLXLexer.LBRACE, DRLXLexer.RBRACE, DRLXLexer.LBRACK,
            DRLXLexer.RBRACK, DRLXLexer.SEMI, DRLXLexer.COMMA, DRLXLexer.DOT, DRLXLexer.ASSIGN, DRLXLexer.GT, DRLXLexer.LT,
            DRLXLexer.BANG, DRLXLexer.TILDE, DRLXLexer.QUESTION, DRLXLexer.COLON, DRLXLexer.EQUAL, DRLXLexer.LE, DRLXLexer.GE,
            DRLXLexer.NOTEQUAL, DRLXLexer.AND, DRLXLexer.OR, DRLXLexer.INC, DRLXLexer.DEC, DRLXLexer.ADD, DRLXLexer.SUB, DRLXLexer.MUL,
            DRLXLexer.DIV, DRLXLexer.BITAND, DRLXLexer.BITOR, DRLXLexer.CARET, DRLXLexer.MOD, DRLXLexer.ADD_ASSIGN, DRLXLexer.SUB_ASSIGN,
            DRLXLexer.MUL_ASSIGN, DRLXLexer.DIV_ASSIGN, DRLXLexer.AND_ASSIGN, DRLXLexer.OR_ASSIGN, DRLXLexer.XOR_ASSIGN,
            DRLXLexer.MOD_ASSIGN, DRLXLexer.LSHIFT_ASSIGN, DRLXLexer.RSHIFT_ASSIGN, DRLXLexer.URSHIFT_ASSIGN,
            DRLXLexer.ARROW, DRLXLexer.COLONCOLON, DRLXLexer.AT, DRLXLexer.ELLIPSIS, DRLXLexer.WS, DRLXLexer.COMMENT,
            DRLXLexer.LINE_COMMENT, DRLXLexer.IDENTIFIER
    );
}
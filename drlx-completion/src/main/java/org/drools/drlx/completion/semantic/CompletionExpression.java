package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxParser;

/**
 * An extracted expression fragment surrounding the caret, repaired for parsing.
 *
 * <p>Constructed by locating a balanced expression boundary via backward token
 * walking, then injecting a sentinel identifier after the incomplete member
 * operator (e.g. {@code person.address.} becomes {@code person.address.__sentinel__}).
 * The repaired text is reparsed using the DRLX expression grammar to produce
 * a structured AST rather than a raw token sequence.
 *
 * <p>The sentinel's position in the resulting AST marks the scope whose members
 * should be offered as completions. Token walking locates the boundary;
 * the grammar interprets method calls, inline casts, literals, null-safe access,
 * indexing, and other expression forms.
 *
 * <p>For the baseline adapter, this wraps the raw parse tree and caret position
 * that {@code resolveDotAccess} currently receives.
 */
public class CompletionExpression {

    private final DrlxParser parser;
    private final ParseTree parseTree;
    private final int caretTokenIndex;
    private final int scopeTokenIndex;

    private CompletionExpression(DrlxParser parser, ParseTree parseTree,
                                int caretTokenIndex, int scopeTokenIndex) {
        this.parser = parser;
        this.parseTree = parseTree;
        this.caretTokenIndex = caretTokenIndex;
        this.scopeTokenIndex = scopeTokenIndex;
    }

    public static CompletionExpression fromCaretPosition(
            DrlxParser parser, ParseTree tree, int caretTokenIndex) {
        return new CompletionExpression(parser, tree, caretTokenIndex, caretTokenIndex - 2);
    }

    public DrlxParser parser() {
        return parser;
    }

    public ParseTree parseTree() {
        return parseTree;
    }

    public int caretTokenIndex() {
        return caretTokenIndex;
    }

    public int scopeTokenIndex() {
        return scopeTokenIndex;
    }
}

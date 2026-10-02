package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;

/**
 * Façade for resolving LHS variable bindings in a DRLX source text.
 *
 * <p>Encapsulates the parse → context → buildVisibleSymbols() pipeline so
 * that callers (Definition, Rename) share a single entry point rather than
 * duplicating the three-step boilerplate.
 *
 * <p>Note: does NOT call {@code DrlxHoverHelper.createParser()} — that method
 * is package-private in {@code org.drools.drlx.completion} and is not
 * accessible from this package.
 */
public final class LhsBindingResolver {

    private LhsBindingResolver() {}

    /**
     * Returns the bindings from an already-constructed {@link CompletionContext}.
     *
     * <p>This is the thin variant for callers (Hover, References) that need the
     * {@code CompletionContext} for other purposes and already have one built.</p>
     *
     * @param ctx  completion context; if {@code null}, returns {@link VisibleSymbols#empty()}
     * @return     visible symbols; never null
     */
    public static VisibleSymbols resolve(CompletionContext ctx) {
        if (ctx == null) {
            return VisibleSymbols.empty();
        }
        return ctx.buildVisibleSymbols();
    }

    /**
     * Returns the bindings visible at {@code tokenIndex} within {@code text},
     * scoped to the enclosing rule (caret-aware).
     *
     * @param text        full source text of the .drlx file
     * @param tokenIndex  token-stream index of the cursor token
     * @param model       workspace semantic model
     * @return            visible symbols at the position; never null
     */
    public static VisibleSymbols resolve(String text, int tokenIndex,
                                          WorkspaceSemanticModel model) {
        if (text == null || text.isEmpty()) {
            return VisibleSymbols.empty();
        }
        DrlxParser parser = createParser(text);
        ParseTree parseTree = parser.drlxStart();
        CompletionContext ctx = model.createContext(parser, parseTree, tokenIndex);
        return ctx.buildVisibleSymbols();
    }

    /**
     * Returns all bindings declared anywhere in {@code text}, across all
     * rules, without caret-position filtering.
     *
     * @param text   full source text of the .drlx file
     * @param model  workspace semantic model
     * @return       all visible symbols; never null
     */
    public static VisibleSymbols resolveAll(String text, WorkspaceSemanticModel model) {
        return resolve(text, Integer.MAX_VALUE, model);
    }

    private static DrlxParser createParser(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        return new DrlxParser(tokens);
    }
}

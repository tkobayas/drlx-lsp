package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.completion.semantic.CompletionContext;
import org.drools.drlx.completion.semantic.LhsBindingResolver;
import org.drools.drlx.completion.semantic.SymbolEntry;
import org.drools.drlx.completion.semantic.TokenRange;
import org.drools.drlx.completion.semantic.VisibleSymbols;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

public final class DrlxRenameHelper {

    private DrlxRenameHelper() {
    }

    public record PreparedRename(Range range, String placeholder) {
    }

    public static PreparedRename prepare(String uri, String text, Position position,
                                          WorkspaceSemanticModel model) {
        if (text == null || text.isEmpty() || position == null) {
            return null;
        }

        DrlxParser parser = DrlxHoverHelper.createParser(text);
        ParseTree parseTree = parser.drlxStart();
        CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();

        Token token = DrlxHoverHelper.findTokenAt(tokens, position);
        if (token == null || token.getType() != DrlxLexer.IDENTIFIER) {
            return null;
        }
        String word = token.getText();
        int tokenIndex = token.getTokenIndex();

        CompletionContext ctx = model.createContext(parser, parseTree, tokenIndex);
        VisibleSymbols symbols = LhsBindingResolver.resolve(ctx);
        Optional<SymbolEntry> entry = symbols.lookupEntry(word);

        if (entry.isEmpty()) {
            return null;
        }

        TokenRange range = TokenRange.fromAntlrToken(token, word.length());
        return new PreparedRename(range.toLspRange(), word);
    }

    public static WorkspaceEdit rename(String uri, String text, Position position,
                                        String newName, WorkspaceSemanticModel model) {
        if (text == null || text.isEmpty() || position == null || newName == null) {
            return null;
        }

        if (!isValidIdentifier(newName)) {
            return null;
        }

        DrlxParser parser = DrlxHoverHelper.createParser(text);
        ParseTree parseTree = parser.drlxStart();
        CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();

        Token token = DrlxHoverHelper.findTokenAt(tokens, position);
        if (token == null || token.getType() != DrlxLexer.IDENTIFIER) {
            return null;
        }
        String word = token.getText();
        int tokenIndex = token.getTokenIndex();

        CompletionContext ctx = model.createContext(parser, parseTree, tokenIndex);
        VisibleSymbols symbols = LhsBindingResolver.resolve(ctx);
        Optional<SymbolEntry> entry = symbols.lookupEntry(word);

        if (entry.isEmpty()) {
            return null;
        }

        List<Location> refs = DrlxReferencesHelper.references(uri, text, position, model, true);
        if (refs.isEmpty()) {
            return null;
        }

        Map<String, List<TextEdit>> changes = new LinkedHashMap<>();
        for (Location loc : refs) {
            changes.computeIfAbsent(loc.getUri(), k -> new ArrayList<>())
                   .add(new TextEdit(loc.getRange(), newName));
        }
        return new WorkspaceEdit(changes);
    }

    private static boolean isValidIdentifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}

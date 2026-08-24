package org.drools.drlx.completion;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.completion.semantic.CompletionContext;
import org.drools.drlx.completion.semantic.SymbolEntry;
import org.drools.drlx.completion.semantic.TokenRange;
import org.drools.drlx.completion.semantic.VisibleSymbols;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;

public class DrlxDefinitionHelper {

    private DrlxDefinitionHelper() {
    }

    public static List<Location> definition(String uri, String text, Position position, WorkspaceSemanticModel model) {
        if (text == null || text.isEmpty() || position == null) {
            return Collections.emptyList();
        }

        DrlxParser parser = DrlxHoverHelper.createParser(text);
        ParseTree parseTree = parser.drlxStart();
        CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();

        Token token = DrlxHoverHelper.findTokenAt(tokens, position);
        if (token == null || token.getType() != DrlxLexer.IDENTIFIER) {
            return Collections.emptyList();
        }
        String word = token.getText();
        int tokenIndex = token.getTokenIndex();

        CompletionContext ctx = model.createContext(parser, parseTree, tokenIndex);
        VisibleSymbols symbols = ctx.buildVisibleSymbols();
        Optional<SymbolEntry> entry = symbols.lookupEntry(word);
        if (entry.isPresent() && entry.get().range() != null) {
            TokenRange defRange = entry.get().range();
            if (position.getLine() == defRange.startLine()
                    && position.getCharacter() >= defRange.startCol()
                    && position.getCharacter() < defRange.endCol()) {
                return Collections.emptyList();
            }
            return List.of(new Location(uri, defRange.toLspRange()));
        }

        return Collections.emptyList();
    }
}

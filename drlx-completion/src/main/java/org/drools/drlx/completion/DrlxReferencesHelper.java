package org.drools.drlx.completion;

import java.util.ArrayList;
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
import org.drools.drlx.parser.DrlxParser.DrlxCompilationUnitContext;
import org.drools.drlx.parser.DrlxParser.RuleDeclarationContext;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;

public class DrlxReferencesHelper {

    private DrlxReferencesHelper() {
    }

    public static List<Location> references(String uri, String text, Position position,
                                             WorkspaceSemanticModel model, boolean includeDeclaration) {
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

        if (entry.isPresent()) {
            return findBindingReferences(uri, word, tokens, parseTree, tokenIndex,
                    entry.get(), includeDeclaration);
        }

        return findImportTypeReferences(uri, word, tokens, ctx, includeDeclaration);
    }

    private static List<Location> findBindingReferences(String uri, String word,
                                                         CommonTokenStream tokens, ParseTree parseTree,
                                                         int tokenIndex, SymbolEntry entry,
                                                         boolean includeDeclaration) {
        RuleDeclarationContext enclosingRule = findEnclosingRule(parseTree, tokenIndex);
        if (enclosingRule == null) {
            return Collections.emptyList();
        }

        int ruleStart = enclosingRule.getStart().getTokenIndex();
        int ruleStop = enclosingRule.getStop() != null
                ? enclosingRule.getStop().getTokenIndex() : Integer.MAX_VALUE;

        TokenRange declRange = entry.range();

        List<Location> refs = new ArrayList<>();
        tokens.fill();
        for (Token t : tokens.getTokens()) {
            if (t.getType() != DrlxLexer.IDENTIFIER) continue;
            int idx = t.getTokenIndex();
            if (idx < ruleStart || idx > ruleStop) continue;
            if (!t.getText().equals(word)) continue;

            if (!includeDeclaration && declRange != null && isAtRange(t, declRange)) {
                continue;
            }

            refs.add(tokenToLocation(uri, t));
        }
        return refs;
    }

    private static List<Location> findImportTypeReferences(String uri, String word,
                                                            CommonTokenStream tokens,
                                                            CompletionContext ctx,
                                                            boolean includeDeclaration) {
        boolean isImportType = false;
        int importLine = -1;
        for (String fqcn : ctx.imports()) {
            String simpleName = fqcn.contains(".")
                    ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
            if (simpleName.equals(word)) {
                isImportType = true;
                break;
            }
        }
        if (!isImportType) {
            return Collections.emptyList();
        }

        List<Location> refs = new ArrayList<>();
        tokens.fill();
        for (Token t : tokens.getTokens()) {
            if (t.getType() != DrlxLexer.IDENTIFIER) continue;
            if (!t.getText().equals(word)) continue;
            refs.add(tokenToLocation(uri, t));
        }

        if (!includeDeclaration) {
            DrlxCompilationUnitContext cu = findCompilationUnit(ctx.parseTree());
            if (cu != null) {
                for (var imp : cu.importDeclaration()) {
                    if (imp.qualifiedName() == null) continue;
                    String fqcn = imp.qualifiedName().getText();
                    String simpleName = fqcn.contains(".")
                            ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
                    if (simpleName.equals(word)) {
                        importLine = imp.getStart().getLine() - 1;
                        break;
                    }
                }
            }
            if (importLine >= 0) {
                int impLine = importLine;
                refs.removeIf(loc -> loc.getRange().getStart().getLine() == impLine);
            }
        }

        return refs;
    }

    private static RuleDeclarationContext findEnclosingRule(ParseTree parseTree, int tokenIndex) {
        DrlxCompilationUnitContext cu = findCompilationUnit(parseTree);
        if (cu == null) return null;

        for (RuleDeclarationContext rule : cu.ruleDeclaration()) {
            if (rule.getStart() != null) {
                int ruleStart = rule.getStart().getTokenIndex();
                int ruleStop = rule.getStop() != null
                        ? rule.getStop().getTokenIndex() : Integer.MAX_VALUE;
                if (ruleStart <= tokenIndex && ruleStop >= tokenIndex) {
                    return rule;
                }
            }
        }
        return null;
    }

    private static DrlxCompilationUnitContext findCompilationUnit(ParseTree node) {
        if (node instanceof DrlxCompilationUnitContext cu) return cu;
        for (int i = 0; i < node.getChildCount(); i++) {
            DrlxCompilationUnitContext found = findCompilationUnit(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isAtRange(Token token, TokenRange range) {
        int line = token.getLine() - 1;
        int col = token.getCharPositionInLine();
        return line == range.startLine() && col == range.startCol();
    }

    private static Location tokenToLocation(String uri, Token token) {
        TokenRange range = TokenRange.fromAntlrToken(token, token.getText().length());
        return new Location(uri, range.toLspRange());
    }
}

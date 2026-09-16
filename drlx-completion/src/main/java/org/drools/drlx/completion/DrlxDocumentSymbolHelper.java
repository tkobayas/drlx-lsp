package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxParser;
import org.drools.drlx.parser.DrlxParser.DrlxCompilationUnitContext;
import org.drools.drlx.parser.DrlxParser.RuleDeclarationContext;
import org.drools.drlx.parser.DrlxParser.UnitDeclarationContext;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

public final class DrlxDocumentSymbolHelper {

    private DrlxDocumentSymbolHelper() {
    }

    /**
     * Returns the outline symbols for {@code text}, or an empty list.
     */
    public static List<DocumentSymbol> symbols(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        try {
            DrlxParser parser = DrlxHoverHelper.createParser(text);
            ParseTree parseTree = parser.drlxStart();
            if (parseTree == null || isJavaStyleCompilationUnit(parseTree)) {
                return Collections.emptyList();
            }

            List<DocumentSymbol> result = new ArrayList<>();

            DrlxCompilationUnitContext cu = findCompilationUnit(parseTree);
            if (cu != null) {
                UnitDeclarationContext unitDecl = cu.unitDeclaration();
                if (unitDecl != null && unitDecl.qualifiedName() != null) {
                    try {
                        String name = unitDecl.qualifiedName().getText();
                        if (name != null && !name.isEmpty()) {
                            DocumentSymbol symbol = symbol(name, SymbolKind.Namespace, unitDecl, unitDecl.qualifiedName());
                            if (symbol != null) {
                                result.add(symbol);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            List<RuleDeclarationContext> rules = new ArrayList<>();
            findRules(parseTree, rules);
            for (RuleDeclarationContext ruleDecl : rules) {
                if (ruleDecl == null) continue;
                try {
                    if (ruleDecl.identifier() != null) {
                        String name = ruleDecl.identifier().getText();
                        if (name != null && !name.isEmpty()) {
                            DocumentSymbol symbol = symbol(name, SymbolKind.Method, ruleDecl, ruleDecl.identifier());
                            if (symbol != null) {
                                result.add(symbol);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static DocumentSymbol symbol(String name, SymbolKind kind,
                                         ParserRuleContext rangeCtx,
                                         ParserRuleContext selectionCtx) {
        Range fullRange = rangeOf(rangeCtx);
        if (fullRange == null) {
            return null;
        }

        Range selectionRange = rangeOf(selectionCtx);
        if (selectionRange == null || !isContained(selectionRange, fullRange)) {
            selectionRange = fullRange;
        }

        DocumentSymbol sym = new DocumentSymbol();
        sym.setName(name);
        sym.setKind(kind);
        sym.setRange(fullRange);
        sym.setSelectionRange(selectionRange);
        return sym;
    }

    private static Range rangeOf(ParserRuleContext ctx) {
        if (ctx == null || ctx.getStart() == null) {
            return null;
        }
        Token startToken = ctx.getStart();
        Token stopToken = ctx.getStop() != null ? ctx.getStop() : startToken;

        int startLine = Math.max(0, startToken.getLine() - 1);
        int startCol = Math.max(0, startToken.getCharPositionInLine());

        int stopLine = Math.max(0, stopToken.getLine() - 1);
        String stopText = stopToken.getText();
        int stopLength = stopText != null ? stopText.length() : 0;
        int stopCol = Math.max(0, stopToken.getCharPositionInLine() + stopLength);

        return new Range(new Position(startLine, startCol), new Position(stopLine, stopCol));
    }

    private static boolean isContained(Range sub, Range sup) {
        return isBeforeOrEqual(sup.getStart(), sub.getStart()) && isBeforeOrEqual(sub.getEnd(), sup.getEnd());
    }

    private static boolean isBeforeOrEqual(Position p1, Position p2) {
        if (p1.getLine() < p2.getLine()) return true;
        if (p1.getLine() == p2.getLine()) return p1.getCharacter() <= p2.getCharacter();
        return false;
    }

    private static DrlxCompilationUnitContext findCompilationUnit(ParseTree node) {
        if (node instanceof DrlxCompilationUnitContext cu) return cu;
        for (int i = 0; i < node.getChildCount(); i++) {
            DrlxCompilationUnitContext found = findCompilationUnit(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isJavaStyleCompilationUnit(ParseTree node) {
        if (node instanceof DrlxParser.CompilationUnitContext) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (isJavaStyleCompilationUnit(node.getChild(i))) return true;
        }
        return false;
    }

    private static void findRules(ParseTree node, List<RuleDeclarationContext> rules) {
        if (node instanceof RuleDeclarationContext rule) {
            rules.add(rule);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            findRules(node.getChild(i), rules);
        }
    }
}

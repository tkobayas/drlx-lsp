package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.vmware.antlr4c3.CodeCompletionCore;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DRLXLexer;
import org.drools.drlx.parser.DRLXParser;
import org.drools.drlx.parser.TolerantDRLXToJavaParserVisitor;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;

public class DRLXCompletionHelper {

    private static final Set<Integer> PREFERRED_RULES = Set.of(
            DRLXParser.RULE_identifier
    );

    private static final Set<Integer> MINOR_IDENTIFIER_RULES = Set.of(
            DRLXParser.RULE_altAnnotationQualifiedName
    );

    private DRLXCompletionHelper() {
    }

    public static List<CompletionItem> getCompletionItems(String text, Position caretPosition) {
        DRLXParser parser = createDrlxParser(text);

        int row = caretPosition == null ? -1 : caretPosition.getLine() + 1;
        int col = caretPosition == null ? -1 : caretPosition.getCharacter();

        ParseTree parseTree = parser.drlxStart();
        Integer caretTokenIndex = computeTokenIndex(parser, row, col);

        return getCompletionItems(parser, caretTokenIndex, parseTree);
    }

    static List<CompletionItem> getCompletionItems(DRLXParser parser, int caretTokenIndex, ParseTree parseTree) {
        CodeCompletionCore core = new CodeCompletionCore(parser, PREFERRED_RULES, Tokens.IGNORED);
        CodeCompletionCore.CandidatesCollection candidates = core.collectCandidates(caretTokenIndex, null);

        if (isMajorIdentifierRule(candidates)) {
            return createSemanticCompletions(parser, parseTree, caretTokenIndex);
        }

        return candidates.tokens.keySet().stream()
                .filter(Objects::nonNull)
                .map(integer -> parser.getVocabulary().getDisplayName(integer).replace("'", ""))
                .map(String::toLowerCase)
                .map(k -> createCompletionItem(k, CompletionItemKind.Keyword))
                .collect(Collectors.toList());
    }

    private static List<CompletionItem> createSemanticCompletions(DRLXParser parser, ParseTree parseTree, int caretTokenIndex) {
        List<CompletionItem> semanticItems = new ArrayList<>();

        // caret is waiting on completion, check a previous token
        int previousTokenIndex = caretTokenIndex - 1;

        Token token = parser.getTokenStream().get(previousTokenIndex);
        System.out.println("Token at index " + previousTokenIndex + ": text => [" + token.getText() + "] , type => " + token.getType() + " , line => " + token.getLine() + " , col => " + token.getCharPositionInLine());

        if (token.getType() == DRLXLexer.DOT) {
            // Let's assume the user is typing a method or field access
            int scopeTokenIndex = previousTokenIndex - 1;

            // Find the parse tree node at the scope token index
            ParseTree targetNode = findNodeAtTokenIndex(parseTree, scopeTokenIndex);

            System.out.println("Target scope node at index " + scopeTokenIndex + ": " + targetNode.getClass() + " , text => [" + targetNode.getText() + "]");

            TolerantDRLXToJavaParserVisitor visitor = new TolerantDRLXToJavaParserVisitor();
            CompilationUnit compilationUnit = (CompilationUnit) visitor.visit(parseTree);

            ReflectionTypeSolver typeSolver = new ReflectionTypeSolver(false);
            JavaSymbolSolver solver = new JavaSymbolSolver(typeSolver);
            solver.inject(compilationUnit);
        }

        if (semanticItems.isEmpty()) {
            semanticItems.add(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
        }
        return semanticItems;
    }

    private static ParseTree findNodeAtTokenIndex(ParseTree node, int targetTokenIndex) {
        if (node instanceof org.antlr.v4.runtime.tree.TerminalNode) {
            org.antlr.v4.runtime.tree.TerminalNode terminal = (org.antlr.v4.runtime.tree.TerminalNode) node;
            Token token = terminal.getSymbol();
            return token.getTokenIndex() == targetTokenIndex ? node : null;
        }

        if (node instanceof org.antlr.v4.runtime.ParserRuleContext) {
            org.antlr.v4.runtime.ParserRuleContext ruleContext = (org.antlr.v4.runtime.ParserRuleContext) node;

            // Early pruning: check if target token is within this node's range
            if (!isTokenInRange(ruleContext, targetTokenIndex)) {
                return null;
            }

            // Find the most specific (deepest) node containing the target token
            ParseTree mostSpecificMatch = null;

            // Optimized child traversal - only check children that could contain the target
            for (int i = 0; i < node.getChildCount(); i++) {
                ParseTree child = node.getChild(i);

                // Pre-filter: skip children that can't contain the target token
                if (child instanceof org.antlr.v4.runtime.ParserRuleContext) {
                    if (!isTokenInRange((org.antlr.v4.runtime.ParserRuleContext) child, targetTokenIndex)) {
                        continue; // Skip this child entirely
                    }
                }

                ParseTree result = findNodeAtTokenIndex(child, targetTokenIndex);
                if (result != null) {
                    mostSpecificMatch = result;
                    // Continue searching to find the most specific match
                }
            }

            // Return the most specific match, or this node if no child matched
            return mostSpecificMatch != null ? mostSpecificMatch : node;
        }

        return null;
    }

    /**
     * Fast range check for token containment
     */
    private static boolean isTokenInRange(org.antlr.v4.runtime.ParserRuleContext context, int tokenIndex) {
        Token startToken = context.getStart();
        Token stopToken = context.getStop();

        if (startToken == null || stopToken == null) {
            return true; // If we can't determine range, assume it might contain the token
        }

        int startIndex = startToken.getTokenIndex();
        int stopIndex = stopToken.getTokenIndex();

        return tokenIndex >= startIndex && tokenIndex <= stopIndex;
    }

    private static boolean isMajorIdentifierRule(CodeCompletionCore.CandidatesCollection candidates) {
        List<Integer> ruleStack = candidates.rules.get(DRLXParser.RULE_identifier);
        if (ruleStack == null || ruleStack.isEmpty()) {
            return false; // not identifier rule
        }
        Integer lastRule = ruleStack.get(ruleStack.size() - 1);
        return !MINOR_IDENTIFIER_RULES.contains(lastRule);
    }

    static CompletionItem createCompletionItem(String label, CompletionItemKind itemKind) {
        CompletionItem completionItem = new CompletionItem();
        completionItem.setInsertText(label);
        completionItem.setLabel(label);
        completionItem.setKind(itemKind);
        return completionItem;
    }

    private static DRLXParser createDrlxParser(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DRLXLexer lexer = new DRLXLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        return new DRLXParser(tokens);
    }

    private static Integer computeTokenIndex(DRLXParser parser, int row, int col) {
        CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();
        int tokenIndex = 0;

        for (Token token : tokens.getTokens()) {
            if (token.getLine() > row || (token.getLine() == row && token.getCharPositionInLine() >= col)) {
                break;
            }
            tokenIndex++;
        }

        return tokenIndex;
    }

    // convenient method. good for logging or testing
    public static List<String> completionItemStrings(List<CompletionItem> result) {
        return result.stream().map(CompletionItem::getInsertText).toList();
    }
}
package org.drools.drlx.completion;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.vmware.antlr4c3.CodeCompletionCore;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.drools.drlx.parser.DRLXLexer;
import org.drools.drlx.parser.DRLXParser;
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
        
        parser.drlxStart();
        Integer nodeIndex = computeTokenIndex(parser, row, col);
        
        return getCompletionItems(parser, nodeIndex);
    }

    static List<CompletionItem> getCompletionItems(DRLXParser parser, int nodeIndex) {
        CodeCompletionCore core = new CodeCompletionCore(parser, PREFERRED_RULES, Tokens.IGNORED);
        CodeCompletionCore.CandidatesCollection candidates = core.collectCandidates(nodeIndex, null);

        if (isMajorIdentifierRule(candidates)) {
            // We should be able to check associated rule chain, so it could be variable name, class name, method name etc.
            // For now, just IDENTIFIER. To be improved
            return List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
        }

        return candidates.tokens.keySet().stream()
                .filter(Objects::nonNull)
                .map(integer -> parser.getVocabulary().getDisplayName(integer).replace("'", ""))
                .map(String::toLowerCase)
                .map(k -> createCompletionItem(k, CompletionItemKind.Keyword))
                .collect(Collectors.toList());
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
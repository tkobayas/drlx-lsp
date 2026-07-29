package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.vmware.antlr4c3.CodeCompletionCore;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.completion.semantic.CompletionContext;
import org.drools.drlx.completion.semantic.CompletionExpression;
import org.drools.drlx.completion.semantic.ExpressionTypeResolver;
import org.drools.drlx.completion.semantic.MemberCompletionProvider;
import org.drools.drlx.completion.semantic.SemanticType;
import org.drools.drlx.completion.semantic.VisibleSymbols;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DrlxCompletionHelper {

    private static final Logger logger = LoggerFactory.getLogger(DrlxCompletionHelper.class);

    private static final Set<Integer> PREFERRED_RULES = Set.of(
            DrlxParser.RULE_identifier
    );

    private final WorkspaceSemanticModel model;
    private final ExpressionTypeResolver resolver;
    private final MemberCompletionProvider memberProvider;

    public DrlxCompletionHelper(WorkspaceSemanticModel model,
                                ExpressionTypeResolver resolver,
                                MemberCompletionProvider memberProvider) {
        this.model = model;
        this.resolver = resolver;
        this.memberProvider = memberProvider;
    }

    public List<CompletionItem> getCompletionItems(String text, Position caretPosition) {
        DrlxParser parser = createDrlxParser(text);

        int row = caretPosition == null ? -1 : caretPosition.getLine() + 1;
        int col = caretPosition == null ? -1 : caretPosition.getCharacter();

        ParseTree parseTree = parser.drlxStart();
        Integer caretTokenIndex = computeTokenIndex(parser, row, col);

        return getCompletionItems(parser, caretTokenIndex, parseTree);
    }

    private List<CompletionItem> getCompletionItems(DrlxParser parser, int caretTokenIndex, ParseTree parseTree) {
        CodeCompletionCore core = new CodeCompletionCore(parser, PREFERRED_RULES, Tokens.IGNORED);
        CodeCompletionCore.CandidatesCollection candidates = core.collectCandidates(caretTokenIndex, null);

        logger.info("getCompletionItems: candidates = {}", candidates);

        List<CompletionItem> items = new ArrayList<>();

        // 1. Always: keyword completions from candidates.tokens
        candidates.tokens.keySet().stream()
                .filter(Objects::nonNull)
                .map(integer -> parser.getVocabulary().getDisplayName(integer).replace("'", ""))
                .map(String::toLowerCase)
                .map(k -> createCompletionItem(k, CompletionItemKind.Keyword))
                .forEach(items::add);

        // 2. Additionally: semantic completions when identifier rule applies
        CompletionSite site = CompletionContextAnalyzer.analyze(candidates, parser, caretTokenIndex);
        if (site.needsSemanticCompletions()) {
            CompletionContext ctx = model.createContext(parser, parseTree, caretTokenIndex);
            items.addAll(createSemanticCompletions(site, ctx));
        }

        // 3. Deduplicate by (insertText, kind)
        return deduplicateItems(items);
    }

    private List<CompletionItem> createSemanticCompletions(CompletionSite site, CompletionContext ctx) {
        return switch (site) {
            case DOT_ACCESS -> resolveDotAccess(ctx);
            default -> List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
        };
    }

    private List<CompletionItem> resolveDotAccess(CompletionContext ctx) {
        CompletionExpression expression = CompletionExpression.fromCaretPosition(
                ctx.parseTree(), ctx.caretTokenIndex());

        Optional<SemanticType> resolved = resolver.resolve(expression, VisibleSymbols.empty(), model);

        if (resolved.isPresent()) {
            List<CompletionItem> items = memberProvider.completions(resolved.get());
            if (!items.isEmpty()) {
                return items;
            }
        }

        return List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
    }

    private List<CompletionItem> deduplicateItems(List<CompletionItem> items) {
        Set<String> seen = new HashSet<>();
        List<CompletionItem> result = new ArrayList<>();
        for (CompletionItem item : items) {
            String key = item.getInsertText() + "::" + item.getKind();
            if (seen.add(key)) {
                result.add(item);
            }
        }
        return result;
    }

    public static CompletionItem createCompletionItem(String label, CompletionItemKind itemKind) {
        CompletionItem completionItem = new CompletionItem();
        completionItem.setInsertText(label);
        completionItem.setLabel(label);
        completionItem.setKind(itemKind);
        return completionItem;
    }

    private DrlxParser createDrlxParser(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        return new DrlxParser(tokens);
    }

    private Integer computeTokenIndex(DrlxParser parser, int row, int col) {
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

    public static List<String> completionItemStrings(List<CompletionItem> result) {
        return result.stream().map(CompletionItem::getInsertText).toList();
    }
}

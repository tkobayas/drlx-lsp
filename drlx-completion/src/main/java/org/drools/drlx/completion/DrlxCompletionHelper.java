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
    private List<String> lastDiagnostics = List.of();

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

        CompletionSite site = CompletionContextAnalyzer.analyze(candidates, parser, caretTokenIndex, parseTree);

        List<CompletionItem> items = new ArrayList<>();

        // 1. Keyword completions from candidates.tokens (suppressed for
        //    sites where only semantic completions are meaningful)
        if (!site.semanticOnly()) {
            candidates.tokens.keySet().stream()
                    .filter(Objects::nonNull)
                    .map(integer -> parser.getVocabulary().getDisplayName(integer).replace("'", ""))
                    .map(String::toLowerCase)
                    .map(k -> createCompletionItem(k, CompletionItemKind.Keyword))
                    .forEach(items::add);
        }

        // 2. Semantic completions when identifier rule applies
        if (site.needsSemanticCompletions()) {
            CompletionContext ctx = model.createContext(parser, parseTree, caretTokenIndex);
            items.addAll(createSemanticCompletions(site, ctx));
            lastDiagnostics = ctx.diagnostics();
        } else {
            lastDiagnostics = List.of();
        }

        // 3. Deduplicate by (insertText, kind)
        return deduplicateItems(items);
    }

    private List<CompletionItem> createSemanticCompletions(CompletionSite site, CompletionContext ctx) {
        return switch (site) {
            case DOT_ACCESS -> resolveDotAccess(ctx);
            case INLINE_CAST_TYPE, AFTER_NEW -> resolveImportedClassNames(ctx);
            case ACCUMULATE_FUNCTION -> resolveAccumulateFunctionNames();
            case RULE_ANNOTATION -> resolveRuleAnnotationNames();
            case ENTRY_POINT -> resolveEntryPointNames(ctx);
            case OOPATH_CHUNK -> resolveOopathChunkCompletions(ctx);
            case CONSTRAINT_EXPRESSION -> resolveConstraintExpressionCompletions(ctx);
            case QUERY_PARAMETER -> resolveQueryParameterCompletions(ctx);
            case OOPATH_WATCH_LIST -> resolveWatchListCompletions(ctx);
            default -> List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
        };
    }

    private List<CompletionItem> resolveDotAccess(CompletionContext ctx) {
        CompletionExpression expression = CompletionExpression.fromCaretPosition(
                ctx.parser(), ctx.parseTree(), ctx.caretTokenIndex());

        VisibleSymbols symbols = ctx.buildVisibleSymbols();
        Optional<SemanticType> resolved = resolver.resolve(expression, symbols, model);

        if (resolved.isPresent()) {
            List<CompletionItem> items = memberProvider.completions(resolved.get());
            if (!items.isEmpty()) {
                return items;
            }
        }

        return List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
    }

    private List<CompletionItem> resolveImportedClassNames(CompletionContext ctx) {
        List<String> names = ctx.resolveImportedTypeNames();
        if (names.isEmpty()) {
            return List.of();
        }
        return names.stream()
                .map(name -> createCompletionItem(name, CompletionItemKind.Class))
                .toList();
    }

    private static final List<String> ACCUMULATE_FUNCTIONS =
            List.of("avg", "sum", "min", "max", "count", "collectList", "collectSet");

    private List<CompletionItem> resolveAccumulateFunctionNames() {
        return ACCUMULATE_FUNCTIONS.stream()
                .map(name -> createCompletionItem(name, CompletionItemKind.Function))
                .toList();
    }

    private static final List<String> RULE_ANNOTATIONS =
            List.of("ActivationGroup", "DataSource", "DateEffective", "DateExpires",
                    "Description", "Disabled", "Duration", "LockOnActive",
                    "NoLoop", "Salience", "Timer");

    private List<CompletionItem> resolveRuleAnnotationNames() {
        return RULE_ANNOTATIONS.stream()
                .map(name -> createCompletionItem(name, CompletionItemKind.Class))
                .toList();
    }

    private List<CompletionItem> resolveEntryPointNames(CompletionContext ctx) {
        List<String> names = ctx.resolveEntryPointNames();
        if (names.isEmpty()) {
            return List.of();
        }
        return names.stream()
                .map(name -> createCompletionItem(name, CompletionItemKind.Field))
                .toList();
    }

    private List<CompletionItem> resolveOopathChunkCompletions(CompletionContext ctx) {
        List<String> names = ctx.resolveOopathChunkCompletions();
        if (names.isEmpty()) {
            return List.of();
        }
        return names.stream()
                .map(name -> createCompletionItem(name, CompletionItemKind.Property))
                .toList();
    }

    private List<CompletionItem> resolveConstraintExpressionCompletions(CompletionContext ctx) {
        List<String> names = ctx.resolveConstraintCompletions();
        if (names.isEmpty()) {
            return List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
        }
        return names.stream()
                .map(name -> "this".equals(name)
                        ? createCompletionItem(name, CompletionItemKind.Keyword)
                        : createCompletionItem(name, CompletionItemKind.Property))
                .toList();
    }

    private List<CompletionItem> resolveQueryParameterCompletions(CompletionContext ctx) {
        List<String> names = ctx.resolveQueryParameterNames();
        if (names.isEmpty()) {
            return List.of();
        }
        return names.stream()
                .map(name -> createCompletionItem(name, CompletionItemKind.Property))
                .toList();
    }

    private List<CompletionItem> resolveWatchListCompletions(CompletionContext ctx) {
        List<String> names = ctx.resolveWatchListCompletions();
        List<CompletionItem> items = new ArrayList<>(names.stream()
                .map(name -> createCompletionItem(name, CompletionItemKind.Property))
                .toList());
        items.add(createCompletionItem("*", CompletionItemKind.Keyword));
        return items;
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
        completionItem.setSortText(sortPrefix(itemKind) + label);
        return completionItem;
    }

    private static String sortPrefix(CompletionItemKind kind) {
        return switch (kind) {
            case Field, Property, Method, Class -> "0_";
            case Keyword -> "1_";
            default -> "2_";
        };
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

    public List<String> lastDiagnostics() {
        return lastDiagnostics;
    }

    public static List<String> completionItemStrings(List<CompletionItem> result) {
        return result.stream().map(CompletionItem::getInsertText).toList();
    }
}

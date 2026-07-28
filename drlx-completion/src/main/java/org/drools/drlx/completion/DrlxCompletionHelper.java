package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionFieldDeclaration;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionMethodDeclaration;
import com.vmware.antlr4c3.CodeCompletionCore;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.completion.semantic.CompletionContext;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.drools.drlx.parser.TolerantDrlxToJavaParserVisitor;
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

    public DrlxCompletionHelper(WorkspaceSemanticModel model) {
        this.model = model;
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
        List<CompletionItem> semanticItems = new ArrayList<>();

        int previousTokenIndex = ctx.caretTokenIndex() - 1;
        if (previousTokenIndex < 0) {
            semanticItems.add(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
            return semanticItems;
        }

        int scopeTokenIndex = previousTokenIndex - 1;

        TolerantDrlxToJavaParserVisitor visitor = new TolerantDrlxToJavaParserVisitor();
        CompilationUnit compilationUnit = (CompilationUnit) visitor.visit(ctx.parseTree());

        JavaSymbolSolver solver = new JavaSymbolSolver(ctx.typeSolver());
        solver.inject(compilationUnit);

        Map<Integer, Node> tokenIdJPNodeMap = visitor.getTokenIdJPNodeMap();
        Expression scopeNode = (Expression) tokenIdJPNodeMap.get(scopeTokenIndex);
        if (scopeNode == null) {
            logger.info("scopeNode is null");
        } else {
            logger.info("scopeNode: " + scopeNode.getClass() + " , text => [" + scopeNode.toString() + "]");
            ResolvedType resolvedType = scopeNode.calculateResolvedType();
            semanticItems.addAll(createTypeBasedCompletions(resolvedType));
        }

        if (semanticItems.isEmpty()) {
            semanticItems.add(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
        }
        return semanticItems;
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

    static CompletionItem createCompletionItem(String label, CompletionItemKind itemKind) {
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

    private List<CompletionItem> createTypeBasedCompletions(ResolvedType resolvedType) {
        List<CompletionItem> items = new ArrayList<>();

        try {
            if (resolvedType.isReferenceType()) {
                ResolvedReferenceType referenceType = resolvedType.asReferenceType();

                for (ResolvedFieldDeclaration field : referenceType.getAllFieldsVisibleToInheritors()) {
                    if (isAccessible(field)) {
                        CompletionItem item = createCompletionItem(field.getName(), CompletionItemKind.Field);
                        item.setDetail(field.getType().describe());
                        items.add(item);
                    }
                }

                referenceType.getAllMethods().stream()
                        .filter(method -> isAccessible(method))
                        .filter(method -> !method.getName().startsWith("$"))
                        .map(method -> method.getName())
                        .distinct()
                        .forEach(methodName -> items.add(createCompletionItem(methodName, CompletionItemKind.Method)));

                addDirectPropertyAccess(items);

            } else if (resolvedType.isArray()) {
                items.add(createCompletionItem("length", CompletionItemKind.Field));
            }
        } catch (Exception e) {
            System.err.println("Error resolving type members: " + e.getMessage());
        }

        return items;
    }

    private void addDirectPropertyAccess(List<CompletionItem> items) {
        Set<CompletionItem> propertyNames = items.stream()
                .filter(item -> item.getKind() == CompletionItemKind.Method)
                .map(CompletionItem::getInsertText)
                .filter(name -> name.startsWith("get") || name.startsWith("is"))
                .map(name -> {
                    if (name.startsWith("get")) {
                        return name.substring(3, 4).toLowerCase() + name.substring(4);
                    } else {
                        return name.substring(2, 3).toLowerCase() + name.substring(3);
                    }
                })
                .map(propName -> createCompletionItem(propName, CompletionItemKind.Field))
                .collect(Collectors.toSet());

        items.addAll(propertyNames);
    }

    private boolean isAccessible(ResolvedFieldDeclaration field) {
        try {
            if (field instanceof ReflectionFieldDeclaration reflectionField) {
                AccessSpecifier accessSpecifier = reflectionField.accessSpecifier();
                return accessSpecifier == AccessSpecifier.PUBLIC;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isAccessible(ResolvedMethodDeclaration method) {
        try {
            if (method instanceof ReflectionMethodDeclaration reflectionMethod) {
                AccessSpecifier accessSpecifier = reflectionMethod.accessSpecifier();
                return accessSpecifier == AccessSpecifier.PUBLIC;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    public static List<String> completionItemStrings(List<CompletionItem> result) {
        return result.stream().map(CompletionItem::getInsertText).toList();
    }
}

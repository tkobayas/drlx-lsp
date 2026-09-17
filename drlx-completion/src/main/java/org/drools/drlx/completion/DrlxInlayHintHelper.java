package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.completion.semantic.CompletionContext;
import org.drools.drlx.completion.semantic.SemanticType;
import org.drools.drlx.completion.semantic.VisibleSymbols;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.drools.drlx.parser.DrlxParser.AccumulateItemContext;
import org.drools.drlx.parser.DrlxParser.BoundOopathContext;
import org.drools.drlx.parser.DrlxParser.DrlxCompilationUnitContext;
import org.drools.drlx.parser.DrlxParser.DrlxExpressionContext;
import org.drools.drlx.parser.DrlxParser.LocalVariableDeclarationContext;
import org.drools.drlx.parser.DrlxParser.OopathChunkContext;
import org.drools.drlx.parser.DrlxParser.OopathExpressionContext;
import org.drools.drlx.parser.DrlxParser.OopathRootContext;
import org.drools.drlx.parser.DrlxParser.RuleBodyContext;
import org.drools.drlx.parser.DrlxParser.RuleDeclarationContext;
import org.drools.drlx.parser.DrlxParser.RuleItemContext;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public final class DrlxInlayHintHelper {

    private DrlxInlayHintHelper() {
    }

    public static List<InlayHint> inlayHints(String text, Range range, WorkspaceSemanticModel model) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }

        DrlxParser parser = createParser(text);
        ParseTree parseTree = parser.drlxStart();
        DrlxCompilationUnitContext unit = findCompilationUnit(parseTree);
        if (unit == null) {
            return Collections.emptyList();
        }

        List<InlayHint> result = new ArrayList<>();

        for (RuleDeclarationContext rule : unit.ruleDeclaration()) {
            RuleBodyContext body = rule.ruleBody();
            if (body == null) continue;

            // We use CompletionContext at the end of the file/rule to resolve types with full imports/unit context
            CompletionContext ctx = model.createContext(parser, parseTree, Integer.MAX_VALUE);

            for (RuleItemContext item : body.ruleItem()) {
                collectInlayHintsFromTree(item, ctx, parser, result);
            }
        }

        if (range != null) {
            result.removeIf(hint -> !isWithinRange(hint.getPosition(), range));
        }

        return result;
    }

    private static void collectInlayHintsFromTree(ParseTree node, CompletionContext ctx, DrlxParser parser, List<InlayHint> hints) {
        if (node instanceof BoundOopathContext bound) {
            processBoundOopath(bound, ctx, hints);
            return;
        }
        if (node instanceof AccumulateItemContext accItem) {
            processAccumulateItem(accItem, ctx, hints);
            return;
        }
        if (node instanceof OopathExpressionContext oopathExpr) {
            processOopathExpression(oopathExpr, ctx, hints);
            return;
        }
        if (node instanceof LocalVariableDeclarationContext localVar) {
            processLocalVariableDeclaration(localVar, ctx, parser, hints);
            return;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectInlayHintsFromTree(node.getChild(i), ctx, parser, hints);
        }
    }

    private static void processBoundOopath(BoundOopathContext bound, CompletionContext ctx, List<InlayHint> hints) {
        if (bound.identifier().size() >= 2) {
            String typeName = bound.identifier(0).getText();
            if ("var".equals(typeName)) {
                Token bindToken = bound.identifier(1).getStart();
                SemanticType inferred = inferVarBindingType(bound, ctx);
                if (inferred != null) {
                    addTypeHint(bindToken, inferred, hints);
                }
            }
        }
        if (bound.oopathExpression() != null) {
            processOopathExpression(bound.oopathExpression(), ctx, hints);
        }
    }

    private static SemanticType inferVarBindingType(BoundOopathContext bound, CompletionContext ctx) {
        var oopathExpr = bound.oopathExpression();
        if (oopathExpr == null) return null;
        OopathRootContext root = oopathExpr.oopathRoot();
        if (root == null || root.identifier(0) == null) return null;
        String entryPointName = root.identifier(0).getText();
        return ctx.resolveEntryPointType(entryPointName);
    }

    private static void processAccumulateItem(AccumulateItemContext accItem, CompletionContext ctx, List<InlayHint> hints) {
        if (accItem.VAR() != null && accItem.identifier() != null) {
            Token bindToken = accItem.identifier().getStart();
            SemanticType inferred = inferAccumulateResultType(accItem, ctx);
            if (inferred != null) {
                addTypeHint(bindToken, inferred, hints);
            }
        }
    }

    private static SemanticType inferAccumulateResultType(AccumulateItemContext accItem, CompletionContext ctx) {
        var accCall = accItem.accumulateCall();
        if (accCall == null || accCall.qualifiedName() == null) return null;
        String funcName = accCall.qualifiedName().getText();
        String inferredType = switch (funcName) {
            case "sum", "avg", "min", "max" -> "Number";
            case "count" -> "Long";
            case "collectList" -> "java.util.List";
            case "collectSet" -> "java.util.Set";
            default -> "Object";
        };
        return ctx.resolveTypeToSemanticType(inferredType);
    }

    private static void processOopathExpression(OopathExpressionContext oopathExpr, CompletionContext ctx, List<InlayHint> hints) {
        OopathRootContext root = oopathExpr.oopathRoot();
        if (root == null || root.identifier(0) == null) return;

        String rootName = root.identifier(0).getText();
        SemanticType rootType = ctx.resolveEntryPointType(rootName);
        if (rootType == null) {
            if (oopathExpr.getParent() instanceof BoundOopathContext bound && bound.identifier().size() >= 2) {
                String typeName = bound.identifier(0).getText();
                if (!"var".equals(typeName)) {
                    rootType = ctx.resolveTypeToSemanticType(typeName);
                }
            }
        }
        if (rootType == null) return;

        extractBindingsFromDrlxExpressions(root.drlxExpression(), rootType, ctx, hints);

        SemanticType currentType = rootType;
        for (OopathChunkContext chunk : oopathExpr.oopathChunk()) {
            String chunkName = chunk.identifier(0).getText();
            SemanticType chunkType = ctx.resolvePropertyType(currentType, chunkName);
            if (chunkType == null) break;
            SemanticType unwrapped = ctx.unwrapCollectionElementType(chunkType);
            if (unwrapped != null) chunkType = unwrapped;

            extractBindingsFromDrlxExpressions(chunk.drlxExpression(), chunkType, ctx, hints);
            currentType = chunkType;
        }
    }

    private static void extractBindingsFromDrlxExpressions(List<DrlxExpressionContext> drlxExprs, SemanticType ownerType, CompletionContext ctx, List<InlayHint> hints) {
        if (drlxExprs == null) return;
        for (DrlxExpressionContext drlxExpr : drlxExprs) {
            if (drlxExpr.bind != null && drlxExpr.expression() != null) {
                String propName = extractLeadingPropertyName(drlxExpr.expression());
                if (propName != null) {
                    SemanticType propType = ctx.resolvePropertyType(ownerType, propName);
                    if (propType != null) {
                        addTypeHint(drlxExpr.bind.getStart(), propType, hints);
                    }
                }
            }
        }
    }

    private static String extractLeadingPropertyName(DrlxParser.ExpressionContext expr) {
        ParseTree node = expr;
        while (node != null) {
            if (node instanceof DrlxParser.IdentifierContext) {
                return node.getText();
            }
            if (node.getChildCount() == 0) return null;
            node = node.getChild(0);
        }
        return null;
    }

    private static void processLocalVariableDeclaration(LocalVariableDeclarationContext localVar, CompletionContext ctx, DrlxParser parser, List<InlayHint> hints) {
        if (localVar.VAR() != null && localVar.identifier() != null) {
            Token varToken = localVar.identifier().getStart();
            SemanticType st = inferVarInitializerType(localVar, ctx, parser);
            if (st == null) st = ctx.resolveTypeToSemanticType("Object");
            if (st != null) {
                addTypeHint(varToken, st, hints);
            }
        }
    }

    private static SemanticType inferVarInitializerType(LocalVariableDeclarationContext localVar, CompletionContext ctx, DrlxParser parser) {
        if (localVar.expression() == null) return null;
        String exprText = parser.getTokenStream().getText(localVar.expression().getSourceInterval());
        VisibleSymbols symbols = ctx.buildVisibleSymbols();
        return ctx.typeResolver().resolveExpressionType(exprText, symbols, ctx.imports(), ctx.model().projectClassLoader())
                .orElse(null);
    }

    private static void addTypeHint(Token token, SemanticType type, List<InlayHint> hints) {
        String simpleName = simpleTypeName(type);
        if (simpleName.equals("?")) return;

        // Position at the end of the identifier token
        Position pos = new Position(token.getLine() - 1, token.getCharPositionInLine() + token.getText().length());
        InlayHint hint = new InlayHint(pos, Either.forLeft(": " + simpleName));
        hint.setKind(InlayHintKind.Type);
        hint.setPaddingLeft(true);
        hints.add(hint);
    }

    private static String simpleTypeName(SemanticType type) {
        if (type.resolvedType() == null) return "?";
        String desc = type.resolvedType().describe();
        if (desc.startsWith("java.lang.")) desc = desc.substring("java.lang.".length());
        int dot = desc.lastIndexOf('.');
        return dot >= 0 ? desc.substring(dot + 1) : desc;
    }

    private static boolean isWithinRange(Position pos, Range range) {
        if (pos.getLine() < range.getStart().getLine() || pos.getLine() > range.getEnd().getLine()) {
            return false;
        }
        if (pos.getLine() == range.getStart().getLine() && pos.getCharacter() < range.getStart().getCharacter()) {
            return false;
        }
        if (pos.getLine() == range.getEnd().getLine() && pos.getCharacter() > range.getEnd().getCharacter()) {
            return false;
        }
        return true;
    }

    private static DrlxCompilationUnitContext findCompilationUnit(ParseTree node) {
        if (node instanceof DrlxCompilationUnitContext cu) return cu;
        for (int i = 0; i < node.getChildCount(); i++) {
            DrlxCompilationUnitContext found = findCompilationUnit(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private static DrlxParser createParser(String text) {
        DrlxLexer lexer = new DrlxLexer(new ANTLRInputStream(text));
        return new DrlxParser(new CommonTokenStream(lexer));
    }
}

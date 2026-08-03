package org.drools.drlx.completion.semantic;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedArrayType;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxParser;
import org.drools.drlx.parser.DrlxParser.BlockContext;
import org.drools.drlx.parser.DrlxParser.BlockStatementContext;
import org.drools.drlx.parser.DrlxParser.BoundOopathContext;
import org.drools.drlx.parser.DrlxParser.DrlxCompilationUnitContext;
import org.drools.drlx.parser.DrlxParser.ImportDeclarationContext;
import org.drools.drlx.parser.DrlxParser.LocalVariableDeclarationContext;
import org.drools.drlx.parser.DrlxParser.OopathRootContext;
import org.drools.drlx.parser.DrlxParser.RuleConsequenceContext;
import org.drools.drlx.parser.DrlxParser.RuleDeclarationContext;
import org.drools.drlx.parser.DrlxParser.RuleParameterContext;
import org.drools.drlx.parser.DrlxParser.RuleParameterListContext;
import org.drools.drlx.parser.DrlxParser.StatementContext;
import org.drools.drlx.parser.DrlxParser.UnitDeclarationContext;
import org.drools.drlx.parser.DrlxParser.VariableDeclaratorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompletionContext {

    private static final Logger logger = LoggerFactory.getLogger(CompletionContext.class);

    private final WorkspaceSemanticModel model;
    private final DrlxParser parser;
    private final ParseTree tree;
    private final int caretTokenIndex;

    private String unitClassName;
    private boolean unitClassNameResolved;
    private Set<String> imports;
    private List<String> entryPointNames;

    CompletionContext(WorkspaceSemanticModel model, DrlxParser parser, ParseTree tree, int caretTokenIndex) {
        this.model = model;
        this.parser = parser;
        this.tree = tree;
        this.caretTokenIndex = caretTokenIndex;
    }

    public TypeSolver typeSolver() {
        return model.typeSolver();
    }

    public DrlxParser parser() {
        return parser;
    }

    public int caretTokenIndex() {
        return caretTokenIndex;
    }

    public ParseTree parseTree() {
        return tree;
    }

    public String unitClassName() {
        if (!unitClassNameResolved) {
            unitClassNameResolved = true;
            DrlxCompilationUnitContext cu = findDrlxCompilationUnit();
            if (cu != null) {
                UnitDeclarationContext unitDecl = cu.unitDeclaration();
                if (unitDecl != null && unitDecl.qualifiedName() != null) {
                    unitClassName = unitDecl.qualifiedName().getText();
                }
            }
        }
        return unitClassName;
    }

    public Set<String> imports() {
        if (imports == null) {
            imports = new LinkedHashSet<>();
            DrlxCompilationUnitContext cu = findDrlxCompilationUnit();
            if (cu != null) {
                for (ImportDeclarationContext imp : cu.importDeclaration()) {
                    if (imp.qualifiedName() != null) {
                        imports.add(imp.qualifiedName().getText());
                    }
                }
            }
        }
        return imports;
    }

    public List<String> entryPointNames() {
        if (entryPointNames == null) {
            Set<String> seen = new LinkedHashSet<>();
            DrlxCompilationUnitContext cu = findDrlxCompilationUnit();
            if (cu != null) {
                for (RuleDeclarationContext rule : cu.ruleDeclaration()) {
                    collectEntryPoints(rule, seen);
                }
            }
            entryPointNames = new ArrayList<>(seen);
        }
        return entryPointNames;
    }

    public String findEnclosingPatternType() {
        return null;
    }

    public List<String> visibleBindings() {
        return List.of();
    }

    public VisibleSymbols buildVisibleSymbols() {
        DrlxCompilationUnitContext cu = findDrlxCompilationUnit();
        if (cu == null) {
            return VisibleSymbols.empty();
        }

        RuleDeclarationContext enclosingRule = findEnclosingRule(cu);
        if (enclosingRule == null) {
            return VisibleSymbols.empty();
        }

        VisibleSymbols.Builder builder = new VisibleSymbols.Builder();
        extractRuleParameters(enclosingRule, builder);
        extractOopathBindings(enclosingRule, builder);
        extractLocalVariables(enclosingRule, builder);
        return builder.build();
    }

    private RuleDeclarationContext findEnclosingRule(DrlxCompilationUnitContext cu) {
        RuleDeclarationContext lastCandidate = null;
        for (RuleDeclarationContext rule : cu.ruleDeclaration()) {
            if (rule.getStart() != null) {
                int ruleStart = rule.getStart().getTokenIndex();
                int ruleStop = rule.getStop() != null ? rule.getStop().getTokenIndex() : Integer.MAX_VALUE;
                if (ruleStart <= caretTokenIndex && ruleStop >= caretTokenIndex) {
                    return rule;
                }
                if (ruleStart <= caretTokenIndex) {
                    lastCandidate = rule;
                }
            }
        }
        return lastCandidate;
    }

    private void extractRuleParameters(RuleDeclarationContext rule, VisibleSymbols.Builder builder) {
        RuleParameterListContext paramList = rule.ruleParameterList();
        if (paramList == null) return;
        for (RuleParameterContext param : paramList.ruleParameter()) {
            if (param.getStart().getTokenIndex() >= caretTokenIndex) continue;
            String typeName = param.typeType().getText();
            String varName = param.identifier().getText();
            SemanticType st = resolveTypeToSemanticType(typeName);
            if (st != null) builder.add(varName, st);
        }
    }

    private void extractOopathBindings(RuleDeclarationContext rule, VisibleSymbols.Builder builder) {
        if (rule.ruleBody() == null) return;
        for (var ruleItem : rule.ruleBody().ruleItem()) {
            if (ruleItem.getStart() != null && ruleItem.getStart().getTokenIndex() >= caretTokenIndex) continue;
            collectBoundOopathFromTree(ruleItem, builder);
        }
    }

    private void collectBoundOopathFromTree(ParseTree node, VisibleSymbols.Builder builder) {
        if (node instanceof BoundOopathContext bound) {
            if (bound.getStart().getTokenIndex() >= caretTokenIndex) return;
            if (bound.identifier().size() >= 2) {
                String typeName = bound.identifier(0).getText();
                String bindName = bound.identifier(1).getText();
                if (!"var".equals(typeName)) {
                    SemanticType st = resolveTypeToSemanticType(typeName);
                    if (st != null) builder.add(bindName, st);
                } else {
                    SemanticType inferred = inferVarBindingType(bound);
                    if (inferred != null) builder.add(bindName, inferred);
                }
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectBoundOopathFromTree(node.getChild(i), builder);
        }
    }

    private SemanticType inferVarBindingType(BoundOopathContext bound) {
        var oopathExpr = bound.oopathExpression();
        if (oopathExpr == null) return null;
        OopathRootContext root = oopathExpr.oopathRoot();
        if (root == null || root.identifier(0) == null) return null;
        String entryPointName = root.identifier(0).getText();
        return resolveEntryPointType(entryPointName);
    }

    SemanticType resolveEntryPointType(String entryPointName) {
        String unitClass = unitClassName();
        if (unitClass == null) return null;
        String unitFqcn = resolveToFqcn(unitClass);
        if (unitFqcn == null) unitFqcn = unitClass;
        try {
            Class<?> clazz = Class.forName(unitFqcn, false, ClassLoader.getSystemClassLoader());
            for (Field field : clazz.getDeclaredFields()) {
                if (field.getName().equals(entryPointName)) {
                    java.lang.reflect.Type genericType = field.getGenericType();
                    if (genericType instanceof ParameterizedType pt) {
                        java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
                        if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> elementClass) {
                            return resolveTypeToSemanticType(elementClass.getName());
                        }
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            logger.debug("Cannot load unit class '{}': {}", unitFqcn, e.getMessage());
        }
        return null;
    }

    private void extractLocalVariables(RuleDeclarationContext rule, VisibleSymbols.Builder builder) {
        if (rule.ruleBody() == null) return;
        for (var ruleItem : rule.ruleBody().ruleItem()) {
            RuleConsequenceContext consequence = ruleItem.ruleConsequence();
            if (consequence == null) continue;
            StatementContext stmtCtx = consequence.statement();
            if (stmtCtx == null) continue;
            BlockContext blockCtx = findBlockInStatement(stmtCtx);
            if (blockCtx == null) continue;
            for (BlockStatementContext blockStmt : blockCtx.blockStatement()) {
                if (blockStmt.getStart() != null && blockStmt.getStart().getTokenIndex() >= caretTokenIndex) continue;
                LocalVariableDeclarationContext localVar = blockStmt.localVariableDeclaration();
                if (localVar == null) continue;
                extractFromLocalVarDecl(localVar, builder);
            }
        }
    }

    private BlockContext findBlockInStatement(StatementContext stmtCtx) {
        for (int i = 0; i < stmtCtx.getChildCount(); i++) {
            if (stmtCtx.getChild(i) instanceof BlockContext bc) {
                return bc;
            }
        }
        return null;
    }

    private void extractFromLocalVarDecl(LocalVariableDeclarationContext localVar, VisibleSymbols.Builder builder) {
        if (localVar.typeType() != null && localVar.variableDeclarators() != null) {
            String typeName = localVar.typeType().getText();
            for (VariableDeclaratorContext decl : localVar.variableDeclarators().variableDeclarator()) {
                if (decl.variableDeclaratorId() != null) {
                    String varName = decl.variableDeclaratorId().identifier().getText();
                    SemanticType st = resolveTypeToSemanticType(typeName);
                    if (st != null) builder.add(varName, st);
                }
            }
        } else if (localVar.VAR() != null && localVar.identifier() != null) {
            String varName = localVar.identifier().getText();
            SemanticType st = resolveTypeToSemanticType("Object");
            if (st != null) builder.add(varName, st);
        }
    }

    SemanticType resolveTypeToSemanticType(String typeName) {
        try {
            boolean isArray = typeName.endsWith("[]");
            String baseTypeName = isArray ? typeName.substring(0, typeName.indexOf('[')) : typeName;
            String fqcn = resolveToFqcn(baseTypeName);
            if (fqcn == null) return null;
            ResolvedReferenceTypeDeclaration decl = model.typeSolver().solveType(fqcn);
            var resolvedType = new ReferenceTypeImpl(decl);
            if (isArray) {
                return SemanticType.value(new ResolvedArrayType(resolvedType));
            }
            return SemanticType.value(resolvedType);
        } catch (Exception e) {
            logger.debug("Cannot resolve type '{}': {}", typeName, e.getMessage());
            return null;
        }
    }

    String resolveToFqcn(String simpleName) {
        try {
            model.typeSolver().solveType("java.lang." + simpleName);
            return "java.lang." + simpleName;
        } catch (Exception ignored) { }
        for (String imp : imports()) {
            if (imp.endsWith("." + simpleName)) return imp;
        }
        try {
            model.typeSolver().solveType(simpleName);
            return simpleName;
        } catch (Exception ignored) { }
        return null;
    }

    private void collectEntryPoints(RuleDeclarationContext rule, Set<String> seen) {
        if (rule.ruleBody() == null) return;
        for (var ruleItem : rule.ruleBody().ruleItem()) {
            collectEntryPointsFromTree(ruleItem, seen);
        }
    }

    private void collectEntryPointsFromTree(ParseTree node, Set<String> seen) {
        if (node instanceof BoundOopathContext bound) {
            var oopathExpr = bound.oopathExpression();
            if (oopathExpr != null) {
                OopathRootContext root = oopathExpr.oopathRoot();
                if (root != null && root.identifier(0) != null) {
                    seen.add(root.identifier(0).getText());
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectEntryPointsFromTree(node.getChild(i), seen);
        }
    }

    private DrlxCompilationUnitContext findDrlxCompilationUnit() {
        return findContext(tree, DrlxCompilationUnitContext.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> T findContext(ParseTree node, Class<T> type) {
        if (type.isInstance(node)) {
            return (T) node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            T found = findContext(node.getChild(i), type);
            if (found != null) return found;
        }
        return null;
    }
}

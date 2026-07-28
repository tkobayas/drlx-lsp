package org.drools.drlx.completion.semantic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.github.javaparser.resolution.TypeSolver;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxParser;
import org.drools.drlx.parser.DrlxParser.BoundOopathContext;
import org.drools.drlx.parser.DrlxParser.DrlxCompilationUnitContext;
import org.drools.drlx.parser.DrlxParser.ImportDeclarationContext;
import org.drools.drlx.parser.DrlxParser.OopathRootContext;
import org.drools.drlx.parser.DrlxParser.RuleDeclarationContext;
import org.drools.drlx.parser.DrlxParser.UnitDeclarationContext;

public class CompletionContext {

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

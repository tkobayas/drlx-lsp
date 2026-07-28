package org.drools.drlx.completion.semantic;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxParser;

public class WorkspaceSemanticModel {

    private CombinedTypeSolver typeSolver;

    public WorkspaceSemanticModel(ClasspathProvider classpathProvider) {
        this.typeSolver = buildTypeSolver(classpathProvider);
    }

    public TypeSolver typeSolver() {
        return typeSolver;
    }

    public CompletionContext createContext(DrlxParser parser, ParseTree tree, int caretTokenIndex) {
        return new CompletionContext(this, parser, tree, caretTokenIndex);
    }

    public void rebuild(ClasspathProvider classpathProvider) {
        this.typeSolver = buildTypeSolver(classpathProvider);
    }

    private static CombinedTypeSolver buildTypeSolver(ClasspathProvider classpathProvider) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver(false));
        return solver;
    }
}

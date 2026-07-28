package org.drools.drlx.completion.semantic;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceSemanticModelTest {

    @Test
    void typeSolverResolvesJavaLangString() {
        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        TypeSolver solver = model.typeSolver();

        SymbolReference<ResolvedReferenceTypeDeclaration> ref = solver.tryToSolveType("java.lang.String");
        assertThat(ref.isSolved()).isTrue();
    }

    @Test
    void typeSolverResolvesJavaUtilList() {
        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        TypeSolver solver = model.typeSolver();

        SymbolReference<ResolvedReferenceTypeDeclaration> ref = solver.tryToSolveType("java.util.List");
        assertThat(ref.isSolved()).isTrue();
    }

    @Test
    void rebuildReplacesTypeSolver() {
        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        TypeSolver solverBefore = model.typeSolver();

        model.rebuild(new CurrentClassloaderProvider());
        TypeSolver solverAfter = model.typeSolver();

        assertThat(solverAfter).isNotSameAs(solverBefore);
    }
}

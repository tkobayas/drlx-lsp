package org.drools.drlx.completion.semantic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    @Test
    void rebuildOutputDirsPicksUpNewClasses(@TempDir Path tempDir) throws IOException {
        Path classDir = tempDir.resolve("classes");
        Path pkgDir = classDir.resolve("com/example");
        Files.createDirectories(pkgDir);
        Files.createFile(pkgDir.resolve("Foo.class"));

        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        model.rebuild(() -> Set.of(classDir), true);

        assertThat(model.classIndex().getMatching("Foo")).contains("com.example.Foo");

        // Add a new class file and call rebuildOutputDirs
        Files.createFile(pkgDir.resolve("Bar.class"));
        model.rebuildOutputDirs();

        assertThat(model.classIndex().getMatching("Bar")).contains("com.example.Bar");
        assertThat(model.classIndex().getMatching("Foo")).contains("com.example.Foo");
    }

    @Test
    void rebuildOutputDirsPreservesJarClasses(@TempDir Path tempDir) throws IOException {
        Path classDir = tempDir.resolve("classes");
        Path pkgDir = classDir.resolve("com/example");
        Files.createDirectories(pkgDir);
        Files.createFile(pkgDir.resolve("Foo.class"));

        Path jarPath = tempDir.resolve("dep.jar");
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new java.util.jar.JarEntry("com/acme/Order.class"));
            jos.closeEntry();
        }

        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        model.rebuild(() -> Set.of(classDir, jarPath), true);

        assertThat(model.classIndex().getMatching("Foo")).contains("com.example.Foo");
        assertThat(model.classIndex().getMatching("Or")).contains("com.acme.Order");

        // Delete the JAR — rebuildOutputDirs should still have JAR classes from cache
        Files.delete(jarPath);
        model.rebuildOutputDirs();

        assertThat(model.classIndex().getMatching("Foo")).contains("com.example.Foo");
        assertThat(model.classIndex().getMatching("Or")).contains("com.acme.Order");
    }
}

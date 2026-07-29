package org.drools.drlx.completion.semantic;

import com.github.javaparser.resolution.TypeSolver;

/**
 * Provides type resolution against the workspace project's classpath and sources.
 *
 * <p>Resolves fully-qualified and simple class names using the project's Maven
 * dependencies, build output ({@code target/classes}), source roots, and the
 * current compilation unit's imports. Uses {@code Class.forName(fqcn, false, loader)}
 * to avoid executing user static initializers inside the language server.
 *
 * <p>Also provides DRLX-specific resolution: the unit class, its
 * {@code DataSource<T>}/{@code DataStore<T>} fields mapped to entry-point names,
 * and member enumeration that distinguishes static from instance members and
 * preserves generic type parameters.
 *
 * <p>For the baseline adapter, this delegates to
 * {@link WorkspaceSemanticModel#typeSolver()}.
 */
public interface WorkspaceTypes {

    TypeSolver typeSolver();
}

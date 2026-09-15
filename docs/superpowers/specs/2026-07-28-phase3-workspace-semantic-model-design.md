# Phase 3: Workspace Semantic Model — Architecture and Interfaces

Parent design: `Completion_Redesign.md`

## Goal

Introduce a `WorkspaceSemanticModel` that owns project-level type resolution infrastructure, and a per-request `CompletionContext` that extracts DRLX-specific context from the parse tree. Wire both into `DrlxCompletionHelper`, replacing the hardcoded `TypeSolverBuilder` call with the model's configured `TypeSolver` and enabling `CompletionSite`-based dispatch through the context.

The first implementation uses the current classloader only. Real Maven classpath resolution is a separate concern (future GitHub issue).

## Architecture: two-layer model

**Long-lived layer** (`WorkspaceSemanticModel`) — created once by the LSP server, rebuilt when the project classpath changes. Owns a `ClasspathProvider` and a configured JavaParser `CombinedTypeSolver`.

**Per-request layer** (`CompletionContext`) — created from the parse tree on each completion request. Extracts unit class name, imports, entry-point names, enclosing pattern type, and visible bindings. This is what `CompletionSite` dispatch methods receive.

The type representation is JavaParser's `ResolvedType`. For raw-reflection paths (unit class, entry-point resolution), generics handling is not required in DRLX.

## New files

All in `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/`:

- `ClasspathProvider.java` — interface with a single method returning classpath entries
- `CurrentClassloaderProvider.java` — first implementation, extracts URLs from the current classloader
- `WorkspaceSemanticModel.java` — long-lived container, owns `TypeSolver`
- `CompletionContext.java` — per-request context derived from parse tree

## Modified files

- `DrlxCompletionHelper.java` — becomes stateful (takes `WorkspaceSemanticModel`); replaces hardcoded `TypeSolverBuilder` with model's `TypeSolver`; adds `CompletionSite`-based dispatch via `CompletionContext`
- `DrlxLspServer.java` — creates `WorkspaceSemanticModel` with `CurrentClassloaderProvider`, passes to document service
- `DrlxLspDocumentService.java` — receives model, creates `DrlxCompletionHelper` from it
- `TestHelperMethods.java` — updated to construct service with model

## ClasspathProvider interface

```java
package org.drools.drlx.completion.semantic;

import java.nio.file.Path;
import java.util.Set;

public interface ClasspathProvider {
    Set<Path> classpathEntries();
}
```

Single method. Returns JARs and class directories. The seam where real Maven resolution plugs in later.

## CurrentClassloaderProvider

```java
package org.drools.drlx.completion.semantic;

public class CurrentClassloaderProvider implements ClasspathProvider {
    @Override
    public Set<Path> classpathEntries() {
        // Extract URLs from URLClassLoader chain (thread context or system classloader)
        // Convert to Path set
        // Returns empty set if classloader is not URL-based
    }
}
```

Temporary implementation. Sufficient for resolving `java.lang`, `java.util`, and test domain classes that are on the LSP server's own classpath.

## WorkspaceSemanticModel

```java
package org.drools.drlx.completion.semantic;

import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

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
        solver.add(new ReflectionTypeSolver());
        // Future: add JarTypeSolver for each JAR from classpathProvider.classpathEntries()
        return solver;
    }
}
```

For the current-classloader implementation, `ReflectionTypeSolver` alone covers everything. The `classpathProvider` parameter establishes the seam; a future Maven implementation would iterate `classpathProvider.classpathEntries()` and add `JarTypeSolver` instances.

`rebuild()` supports the refresh lifecycle — called when the project recompiles. Currently a no-op in practice since the current classloader doesn't change.

## CompletionContext

```java
package org.drools.drlx.completion.semantic;

public class CompletionContext {
    private final WorkspaceSemanticModel model;
    private final DrlxParser parser;
    private final ParseTree tree;
    private final int caretTokenIndex;

    // Lazily computed from parse tree
    public String unitClassName() { ... }
    public Set<String> imports() { ... }
    public List<String> entryPointNames() { ... }
    public String findEnclosingPatternType() { ... }
    public List<String> visibleBindings() { ... }
    public TypeSolver typeSolver() { return model.typeSolver(); }
}
```

All methods walk the ANTLR parse tree — no reflection, no classpath dependency:

- **`unitClassName()`** — `drlxCompilationUnit` → `unitDeclaration` → `qualifiedName.getText()`. Returns the fully qualified unit class name.
- **`imports()`** — collects `importDeclaration` text from the compilation unit.
- **`entryPointNames()`** — collects entry-point identifiers from `oopathRoot` nodes across all rules in the file. These are syntactic names from the parse tree, not resolved against the unit class (resolution is out of scope for this phase).
- **`findEnclosingPatternType()`** — walks up from the caret token to find the enclosing `boundOopath`'s type name. Used by CONSTRAINT_EXPRESSION.
- **`visibleBindings()`** — collects bind variable names from `boundOopath` and rule parameters that appear before the caret in the current rule. Used by CONSEQUENCE_EXPRESSION.

## Wiring: DrlxCompletionHelper

`DrlxCompletionHelper` changes from static utility to stateful:

```java
public class DrlxCompletionHelper {
    private final WorkspaceSemanticModel model;

    public DrlxCompletionHelper(WorkspaceSemanticModel model) {
        this.model = model;
    }

    public List<CompletionItem> getCompletionItems(String text, Position caretPosition) {
        // parse, compute token index, run C3 (unchanged)
        // ...
        CompletionSite site = CompletionContextAnalyzer.analyze(candidates, parser, caretTokenIndex);
        if (site.needsSemanticCompletions()) {
            CompletionContext ctx = model.createContext(parser, tree, caretTokenIndex);
            semanticItems = createSemanticCompletions(site, ctx);
        }
        // merge, deduplicate (unchanged)
    }

    private List<CompletionItem> createSemanticCompletions(CompletionSite site, CompletionContext ctx) {
        return switch (site) {
            case DOT_ACCESS -> resolveDotAccess(ctx);
            default -> List.of();
        };
    }
}
```

`resolveDotAccess(ctx)` replaces the hardcoded `TypeSolverBuilder.withCurrentClassloader()` call with `ctx.typeSolver()`. The rest of the DOT_ACCESS logic (visitor, tokenIdJPNodeMap, calculateResolvedType, createTypeBasedCompletions) is unchanged.

Other `CompletionSite` values return empty lists — stubs for future phases.

## Wiring: LSP server

```java
// DrlxLspServer.java
public DrlxLspServer() {
    this.model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
    this.documentService = new DrlxLspDocumentService(model);
}

// DrlxLspDocumentService.java
public DrlxLspDocumentService(WorkspaceSemanticModel model) {
    this.completionHelper = new DrlxCompletionHelper(model);
}
```

Dependency chain: `DrlxLspServer` → `WorkspaceSemanticModel` → `DrlxLspDocumentService` → `DrlxCompletionHelper`.

## Testing

**New unit tests** in `org.drools.drlx.completion.semantic`:
- `CurrentClassloaderProviderTest` — returns non-empty paths
- `WorkspaceSemanticModelTest` — TypeSolver resolves `java.lang.String`, `java.util.List`
- `CompletionContextTest` — given DRLX text with unit/imports/patterns, verifies `unitClassName()`, `imports()`, `entryPointNames()`, `findEnclosingPatternType()`, `visibleBindings()`

**Existing tests** — expected to pass without modification since DOT_ACCESS uses the same `ReflectionTypeSolver` (sourced from the model instead of hardcoded). If `ReflectionTypeSolver` resolves types differently than the old `TypeSolverBuilder` path, test failures are acceptable — `ReflectionTypeSolver` is a temporary implementation.

**LSP server tests** — `TestHelperMethods` updated to construct the service with a `WorkspaceSemanticModel`. Test logic unchanged.

## Out of scope

- Real Maven classpath resolution (separate GitHub issue)
- JarTypeSolver integration
- Expression resolver interface (Phase 4)
- Semantic completions for non-DOT_ACCESS sites (future phases fill in the switch cases)
- Diagnostics, hover, definition support (future consumers of the model)

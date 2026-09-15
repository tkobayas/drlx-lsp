---
layout: post
title: "Phase 3: Getting the Seams Right"
date: 2026-07-28
type: phase-update
entry_type: note
subtype: diary
projects: [drlx-lsp]
tags: [completion, architecture, javaparser]
---

The completion redesign has been progressing through phases — characterization tests, CompletionSite enum, additive completions. All of that gave us a grammar-aware classification of where the caret is. But every site except DOT_ACCESS just returned a placeholder IDENTIFIER. The semantic model to make the other sites useful didn't exist yet.

Phase 3 was about building that model — or rather, building the architecture for it. I decided the highest-value work wasn't wiring up Maven classpath resolution or implementing entry-point type mapping. It was getting the interface surfaces right. Real implementations can slot in later; wrong seams are expensive to fix.

## The two-layer split

I brought Claude in to explore the codebase — three parallel agents reading the DRLX completion infrastructure, the DRL reference implementation, and the design doc. The reference LSP had a proven pattern: `ClassIndex` for name-to-FQCN lookup, `ClassMemberIndex` for lazy reflection, `MavenClasspathResolver` shelling out to `mvn dependency:build-classpath`. But DRLX already had JavaParser's symbol solver wired up for DOT_ACCESS, and the design doc called for generics-aware types from the start. Two different type resolution paths.

The key insight was lifecycle. Some things change when the project rebuilds (classpath, type solver). Other things change on every keystroke (which rule the caret is in, which bindings are visible). Mixing them in one object would be wrong.

We settled on two layers:

- **`WorkspaceSemanticModel`** — long-lived, created once by the LSP server. Owns a `ClasspathProvider` and a configured `CombinedTypeSolver`. Rebuilt when the classpath changes.
- **`CompletionContext`** — per-request, created from the parse tree. Walks `DrlxCompilationUnitContext` to extract unit class name, imports, entry-point names. Cheap to create, always fresh.

## ClasspathProvider as the seam

The only interface in the whole thing is `ClasspathProvider` — one method: `Set<Path> classpathEntries()`. The first implementation, `CurrentClassloaderProvider`, just parses `java.class.path`. Real Maven resolution becomes a separate GitHub issue with a clean plug-in point.

## Wiring it through

`DrlxCompletionHelper` was a static utility — `getCompletionItems(text, position)` with a hardcoded `TypeSolverBuilder` inside. We made it stateful:

```java
public DrlxCompletionHelper(WorkspaceSemanticModel model) {
    this.model = model;
}
```

The semantic dispatch became a switch on `CompletionSite`:

```java
private List<CompletionItem> createSemanticCompletions(CompletionSite site, CompletionContext ctx) {
    return switch (site) {
        case DOT_ACCESS -> resolveDotAccess(ctx);
        default -> List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
    };
}
```

Each future phase fills in a case. The `resolveDotAccess` method replaced `TypeSolverBuilder.withCurrentClassloader().withSourceCode("src/main/java")` with `ctx.typeSolver()` — the model's configured solver instead of a hardcoded one.

## The ReflectionTypeSolver trap

Tests broke. Nine failures from the IDENTIFIER placeholder change (easy fix — the default case needed to return the placeholder, not empty). But the tenth was interesting: `UnsolvedSymbol: address` on a chained property access test.

`new ReflectionTypeSolver()` defaults to JRE-only. The old `TypeSolverBuilder.withCurrentClassloader()` internally created `ReflectionTypeSolver(false)`. One boolean parameter, completely invisible from the constructor call, and the solver silently returns unresolved rather than throwing. The error surfaced three layers downstream as an `UnsolvedSymbol` on a specific property name.

Fix: `new ReflectionTypeSolver(false)`. Garden entry submitted.

## What landed

Five commits, four new files in `org.drools.drlx.completion.semantic`, all 67 tests passing. The dependency chain is clean: `DrlxLspServer` → `WorkspaceSemanticModel` → `DrlxLspDocumentService` → `DrlxCompletionHelper`. The `withSourceCode("src/main/java")` hardcoded path is gone — source roots will come from the classpath provider when Maven resolution arrives.

The architecture is in place. The switch statement has eleven empty cases waiting for real semantic completions.

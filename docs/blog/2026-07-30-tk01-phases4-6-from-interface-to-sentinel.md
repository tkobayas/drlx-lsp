---
layout: post
title: "Phases 4-6: From Interface to Sentinel"
date: 2026-07-30
type: phase-update
entry_type: note
subtype: diary
projects: [drlx-lsp]
tags: [completion, mvel3, type-resolution, sentinel-pattern]
---

Phase 3 gave us the seams — `WorkspaceSemanticModel`, `CompletionContext`, `ClasspathProvider`. The completion pipeline had a clean architecture but only one working case: DOT_ACCESS, wired directly through the tolerant full-file visitor. Everything else returned a placeholder. Three phases later, the tolerant visitor is gone entirely.

## The interface that made the swap possible

I wanted the replacement resolver to be a drop-in swap, not a rewrite of `DrlxCompletionHelper`. That meant putting an interface between the helper and whatever does the type resolution. We introduced `ExpressionTypeResolver` with four domain types:

```java
interface ExpressionTypeResolver {
    Optional<SemanticType> resolve(
        CompletionExpression expression,
        VisibleSymbols symbols,
        WorkspaceTypes workspaceTypes);
}
```

`SemanticType` wraps JavaParser's `ResolvedType` with a category enum — TYPE, VALUE, ARRAY, PRIMITIVE, UNRESOLVED. The review doc motivated each type precisely: `Class<?>` erases generics (fatal for `DataSource<Person>`), a flat binding map leaks across rules, raw reflection can't distinguish static from instance members. I followed the spec literally rather than starting thin.

We extracted `MemberCompletionProvider` from `DrlxCompletionHelper` — the type-to-completion-item conversion. Then wrapped the existing tolerant visitor logic in `TolerantVisitorTypeResolver` as a baseline adapter. Pure extraction — the resolution logic moved unchanged, all 78 tests passed. `DrlxCompletionHelper` dropped from 251 to 145 lines.

## The MVEL insight

The original plan for the replacement resolver was: token walk backward to find the expression boundary, inject a sentinel identifier after the trailing dot, reparse through `DrlxParser`, convert to JavaParser AST via `DrlxToJavaParserVisitor`, resolve the sentinel's scope.

I was partway through writing the spec when I remembered `TypeResolveTest` in the MVEL3 project. `MVELCompiler.transpile()` already does exactly what steps 4-6 would do — parses MVEL expressions through `MvelParser` (which natively handles inline casts, null-safe `!.`, BigDecimal literals), builds a `CompilationUnit` with `JavaSymbolSolver` injected, and returns a ready-to-resolve AST. The MVEL transpiler is mature and battle-tested. `DrlxToJavaParserVisitor` is not.

The sentinel approach simplified to:

1. `TokenWalker.findExpressionBoundary()` — walk backward from the dot
2. Concatenate tokens, append `.__sentinel__`
3. `MVELCompiler.transpile()` — one call
4. Find `FieldAccessExpr("__sentinel__")` in the AST, resolve its scope

So `System.out.` becomes `System.out.__sentinel__`, transpiles into a `CompilationUnit` where `__sentinel__` is a field access on `System.out`, and `calculateResolvedType()` on the scope gives us `PrintStream`.

## What the characterization tests revealed

We wrote 20 test fixtures from the review spec. The tolerant visitor baseline passed 6 of them — `System.`, `System.out.`, `10.5B.`, `p.address.` (RHS local), inline cast, broken-code-after-caret. It failed on caret-in-middle-of-document (complete code after the caret confused the full-file visitor).

The sentinel resolver passed 5 of the same 6. The one it lost — `p.address.` — is fundamental to the isolated-expression approach: the transpiler only sees `p.address.__sentinel__` and has no idea that `p` was declared as `Person` three lines earlier. That needs `VisibleSymbols` to pass variable declarations into the transpiler.

One surprise: `10.5B.__sentinel__` failed initially with "Unsolved symbol: BigDecimal". The MVEL parser recognises `BigDecimalLiteralExpr` but the symbol solver needs `java.math.BigDecimal` imported. We added it as a default import alongside `BigInteger`.

## The cleanup

With the sentinel resolver verified, we swapped the default in `DrlxLspDocumentService` and deleted `TolerantVisitorTypeResolver` entirely. The completion pipeline is now:

```
DrlxCompletionHelper
  → CompletionContextAnalyzer (C3 + token position)
  → DOT_ACCESS: SentinelExpressionTypeResolver
      → TokenWalker → sentinel injection → MVELCompiler.transpile()
  → MemberCompletionProvider (type → completion items)
```

No tolerant visitor, no full-file JavaParser conversion, no `tokenIdJPNodeMap`. Two integration tests are `@Disabled` pending `VisibleSymbols` — the price of the cleaner architecture. Issue #5 (the original "review and refactor incomplete code completion") is closed.

11 commits across three phases, 103 tests passing, 15 skipped for future work tracked in issues #6, #7, and #8.

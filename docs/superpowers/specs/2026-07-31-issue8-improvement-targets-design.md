# Issue #8: Enable improvement target tests

## Context

Five `ExpressionTypeResolverCharacterizationTest` tests are `@Disabled` as issue #8 improvement targets. Diagnostic run revealed 2 already pass, and 3 need local variable resolution that belongs in issue #7 (VisibleSymbols). This spec covers enabling the passing tests, re-categorizing the variable-dependent ones, and fixing null-safe `!.` normalization so it's ready when #7 provides variable types.

## Changes

### A. Enable 2 passing tests

Remove `@Disabled` from:
- `caretInMiddleOfDocument` — `System.out.` with valid code after the caret
- `inlineCastQualifiedType` — `list#java.util.ArrayList#.` with fully qualified cast type

These already resolve correctly with the current `SentinelExpressionTypeResolver`.

### B. Re-categorize 3 tests from #8 to #7

Change `@Disabled` reason on:
- `methodReturnType` — `list.get(0).` needs `list` declared as `List`
- `nullSafeAccess` — `p!.address!.` needs `p` declared as `Person`
- `arrayIndexedAccess` — `arr[0].` needs `arr` declared as `String[]`

All three fail with "Unsolved symbol" because the local variable declared in the `do { }` block is unknown to MVEL. Once #7's VisibleSymbols (or Declaration API) provides the variable type, these will resolve.

### C. Null-safe `!.` normalization in SentinelExpressionTypeResolver

**Problem**: DRLX `!.` (EXCL_DOT) transpiles to `NullSafeFieldAccessExpr`, which is NOT a subclass of `FieldAccessExpr`. The current `findSentinelScope` only checks `instanceof FieldAccessExpr`, so it misses the sentinel in null-safe chains.

**Fix** (two parts):

1. **Normalize in token loop**: In `resolve()` lines 44-49, when concatenating tokens from boundary to dot, replace `EXCL_DOT` token text with `"."`. Null-safe access has the same resolved type as regular access — the normalization is type-safe.

2. **Safety net in findSentinelScope**: Add `|| node instanceof NullSafeFieldAccessExpr` check alongside `FieldAccessExpr`. This handles cases where normalization doesn't fully prevent `NullSafeFieldAccessExpr` nodes (e.g., if the dot before the caret itself is an EXCL_DOT).

Both require importing `org.mvel3.parser.ast.expr.NullSafeFieldAccessExpr`.

## Files

- `SentinelExpressionTypeResolver.java` — normalize EXCL_DOT + NullSafeFieldAccessExpr handling
- `ExpressionTypeResolverCharacterizationTest.java` — 2 @Disabled removed, 3 re-labeled #7

## Verification

```
mvn -pl drlx-completion test -Dtest="ExpressionTypeResolverCharacterizationTest"
```

Expected: 6 pass (4 existing + 2 newly enabled), 11 skipped (3 re-labeled #7 + 8 existing #7/#6).

Full suite: `mvn -pl drlx-completion test` — all non-disabled tests pass.

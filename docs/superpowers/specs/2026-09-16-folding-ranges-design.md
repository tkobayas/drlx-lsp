# Design: Folding Ranges (Issue #11, Item #6)

**Date:** 2026-09-16  
**Status:** Approved  
**Scope:** `drlx-completion` module + `drlx-lsp-server` module

---

## Summary

Implement `textDocument/foldingRange` for DRLX files. This provides code folding in the editor for:
1. Multi-line top-level constructs (rules, units) as `FoldingRangeKind.Region`.
2. Multi-line `import` declaration groups as `FoldingRangeKind.Imports`.
3. Multi-line block comments (`/* ... */`) and contiguous line comments (`// ...`) as `FoldingRangeKind.Comment`.

Single-line constructs or comments produce no folding range. Java-style (`compilationUnit`) files return an empty list and are ignored.

---

## Grammar Scope & Detection

DRLX files under `drlxStart`:
- `drlxCompilationUnit`: Handled.
- `compilationUnit` (Java style): Returns empty list.

---

## Folding Range Kinds & Logic

### 1. Regions (`FoldingRangeKind.Region`)
- Extracted by reusing `DrlxDocumentSymbolHelper.symbols(text)`.
- For each symbol whose full range spans across lines (`endLine > startLine`), generate a `FoldingRange(startLine, endLine)` with `kind = FoldingRangeKind.Region`.

### 2. Imports (`FoldingRangeKind.Imports`)
- From `DrlxCompilationUnitContext.importDeclaration()`:
  - If 1 or more imports exist and the group spans across lines (i.e. from the start line of the first import to the end line of the last import where `endLine > startLine`), generate a single `FoldingRange(firstStartLine, lastEndLine)` with `kind = FoldingRangeKind.Imports`.

### 3. Comments (`FoldingRangeKind.Comment`)
- Extracted via ANTLR token stream inspection (tokens on comments/hidden channels or lexer tokens):
  - **Block Comments (`/* ... */`)**: If `endLine > startLine`, generate `FoldingRange(startLine, endLine)` with `kind = FoldingRangeKind.Comment`.
  - **Contiguous Single-line Comments (`// ...`)**: If two or more line comments appear on consecutive lines (line $N$, line $N+1$, ...), merge them into a single `FoldingRange(startLine, endLine)` with `kind = FoldingRangeKind.Comment`.

---

## Architecture & Integration

### `drlx-completion` Module
- Class: `org.drools.drlx.completion.DrlxFoldingRangeHelper`
  - `public static List<FoldingRange> foldingRanges(String text)`
  - Returns `List<org.eclipse.lsp4j.FoldingRange>` (0-based line indices).
- Unit Tests: `org.drools.drlx.completion.DrlxFoldingRangeHelperTest`
  - Tests covering empty/null text, rule blocks, imports folding, block comments, consecutive line comments, single-line no-folds.

### `drlx-lsp-server` Module
- `DrlxLspServer`:
  - Advertise capability: `capabilities.setFoldingRangeProvider(true);`
- `DrlxLspDocumentService`:
  - Implement `foldingRange(FoldingRangeRequestParams params)`:
    - Look up document text from cache.
    - Delegate to `DrlxFoldingRangeHelper.foldingRanges(text)`.
    - Return `CompletableFuture.completedFuture(ranges)`.
- Integration Tests: `org.drools.drlx.lsp.server.DrlxLspDocumentServiceTest`
  - Test verifying `service.foldingRange(params)` returns expected ranges for opened DRLX document.

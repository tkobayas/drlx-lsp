# Design Spec: Rename (Bound Variables)

**Date:** 2026-10-01  
**Issue:** [#11](https://github.com/tkobayas/drlx-lsp/issues/11) Item #11  
**Status:** Proposed

---

## 1. Overview & Goal

Implement `textDocument/prepareRename` and `textDocument/rename` for bound variables in DRLX files. This allows users to rename pattern bindings (`$p`, `$addr`), rule parameters, constraint bindings, and RHS local variables with all references updated consistently within the enclosing rule.

**Out of scope:** Rule/query names, import types (classpath types are never renameable), cross-file rename.

---

## 2. Architecture

```
DrlxLspDocumentService
  ├── prepareRename(uri, position) → DrlxRenameHelper.prepare()
  └── rename(uri, position, newName) → DrlxRenameHelper.rename()

DrlxRenameHelper (new, static utility)
  ├── prepare(uri, text, position, model) → PreparedRename | null
  └── rename(uri, text, position, newName, model) → WorkspaceEdit | null
         └── delegates to DrlxReferencesHelper.references() for location collection
```

---

## 3. Detailed Specifications

### 3.1 Renameable Symbols

A symbol is renameable if and only if it is found in `VisibleSymbols` at the caret position — i.e., it is a bound variable within a rule. This includes:

- OOPath pattern bindings: `var $p : /persons`
- Constraint bindings: `[$addr : address]`
- Rule parameters (if present)
- RHS local variables: `var x = ...`

Import types (`Person`, `Address`) resolve via references but are **not** renameable — `prepare()` returns `null` so the client refuses.

### 3.2 `prepare(uri, text, position, model)` → `PreparedRename | null`

1. Parse `text` with `DrlxHoverHelper.createParser()`
2. Find token at `position` with `DrlxHoverHelper.findTokenAt()`
3. If token is null or not `IDENTIFIER` → return `null`
4. Build `VisibleSymbols` via `model.createContext()` + `buildVisibleSymbols()`
5. `symbols.lookupEntry(word)` → if absent, return `null` (not a bound variable)
6. Return `PreparedRename(range, placeholder)` from the token's range and text

`PreparedRename` is a simple record/class with `Range range` and `String placeholder`.

### 3.3 `rename(uri, text, position, newName, model)` → `WorkspaceEdit | null`

1. Same token lookup + `VisibleSymbols` check as `prepare()`
2. **Identifier validation:** `newName` must be a valid Java identifier (including `$` as a legal identifier character). If not, return `null`.
4. Call `DrlxReferencesHelper.references(uri, text, position, model, true)` (includeDeclaration = true)
5. If no references found, return `null`
6. Convert each `Location` to a `TextEdit` with the replacement name
7. Group by URI into a `WorkspaceEdit` and return

### 3.4 Identifier Validation

`newName` is valid if:
- Non-empty
- First character satisfies `Character.isJavaIdentifierStart()` (includes `$`)
- Remaining characters satisfy `Character.isJavaIdentifierPart()`

### 3.5 LSP Server Integration

**`DrlxLspDocumentService`:**
- Add `prepareRename(PrepareRenameParams)` → `CompletableFuture<Either<Range, PrepareRenameResult>>`
- Add `rename(RenameParams)` → `CompletableFuture<WorkspaceEdit>`
- Both follow existing pattern: get uri/text from `sourcesMap`, delegate to `DrlxRenameHelper`

**`DrlxLspServer.initialize()`:**
- Register `setRenameProvider(new RenameOptions(true))` — the `true` enables `prepareRename` support

---

## 4. Test Plan

Tests in `DrlxRenameHelperTest`, following `DrlxReferencesHelperTest` patterns.

### 4.1 Happy Path

| Test case | Input | Expected |
|-----------|-------|----------|
| Rename OOPath binding | `$p` → `$person` at `var $p : /persons` | All `$p` references in rule updated |
| Rename constraint binding | `$addr` → `$address` at `[$addr : address]` | All `$addr` in rule updated |
| Rename RHS local variable | `x` → `result` at `var x = ...` | All `x` in RHS updated |
| Rename with `$` in newName | `$addr` → `$address` | All `$addr` references updated to `$address` |
| Rename dropping `$` | `$addr` → `address` | All `$addr` references updated to `address` |

### 4.2 Rejection Cases

| Test case | Expected |
|-----------|----------|
| Caret on import type (`Person`) | `prepare()` returns `null` |
| Caret on keyword | `prepare()` returns `null` |
| Caret on whitespace/operator | `prepare()` returns `null` |
| Invalid new name (e.g. `123abc`) | `rename()` returns `null` |
| Empty new name | `rename()` returns `null` |

### 4.3 `prepareRename`

| Test case | Expected |
|-----------|----------|
| Caret on renameable binding | Returns range + placeholder text |
| Caret on non-renameable symbol | Returns `null` |

---

## 5. Edge Cases

- **Same name:** If `newName` equals the current name (after normalization), still return the edit (LSP spec does not require rejection).
- **Multiple bindings with same name:** Not possible in valid DRLX — a name in `VisibleSymbols` is unique within a rule.
- **Binding used in both LHS and RHS:** `DrlxReferencesHelper` already handles this by scanning all tokens in the enclosing rule.

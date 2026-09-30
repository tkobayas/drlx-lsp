# Implementation Plan: Code Actions (Unknown Type Quick Fix) & ClassIndex

**Date:** 2026-09-30  
**Spec:** [`docs/superpowers/specs/2026-09-30-code-actions-unknown-type-spec.md`](docs/superpowers/specs/2026-09-30-code-actions-unknown-type-spec.md)  
**Issue:** [#11](https://github.com/tkobayas/drlx-lsp/issues/11) Items #10 & #13

---

## 1. Plan Overview

We will implement this in 4 distinct phases:
- **Phase 1 (Item #13)**: `ClassIndex` creation, unit tests, and integration into `WorkspaceSemanticModel`.
- **Phase 2 (Lint unknown types)**: `lintUnknownTypes` & Levenshtein distance matching in `DrlxLintHelper`, with unit tests.
- **Phase 3 (Item #10)**: `DrlxCodeActionHelper` & unit tests for `"Replace with '<suggestion>'"`.
- **Phase 4 (LSP Server Integration)**: Wire `textDocument/codeAction` in `DrlxLspDocumentService` and `DrlxLspServer`, plus server integration tests.

---

## 2. Step-by-Step Implementation

### Phase 1: `ClassIndex` & Semantic Model (Item #13)

- **Step 1.1**: Create `org.drools.drlx.completion.semantic.ClassIndex`
  - Implement scanning of directories and JAR files for `.class` files.
  - Implement `simpleNames()`, `getMatching(prefix)`, `getAll()`, `merge(...)`, and `empty()`.
- **Step 1.2**: Write unit tests in `org.drools.drlx.completion.semantic.ClassIndexTest`.
- **Step 1.3**: Update `WorkspaceSemanticModel` to build and hold `ClassIndex`, exposing `public ClassIndex classIndex()`.
- **Verification**: Run `mvn clean test -pl drlx-completion`.

### Phase 2: Unknown Type Lint & Typo Suggestions

- **Step 2.1**: Implement Levenshtein distance calculation in `DrlxLintHelper` (bounded $\le 2$).
- **Step 2.2**: Implement `lintUnknownTypes(String text, WorkspaceSemanticModel model)` in `DrlxLintHelper`:
  - Handle null/empty input or when `model == null` safely (return empty list).
  - Use `DrlxHoverHelper.createParser(text)` and parse tree `parser.drlxStart()`.
  - Extract imports and unit declarations from parse tree or `CompletionContext`.
  - Scan type references in DRLX:
    - Pattern root types / identifiers (e.g. `var $p = /Persons[...]` or `/com.sample.Person[...]`).
    - Explicit variable types (e.g. `Person $p = ...`).
    - RHS instantiations (`new TypeName(...)`).
  - Resolve against `model.typeSolver()` and `model.classIndex()` (considering imports and `java.lang.*`).
  - When unresolved, find the best match with distance $\le 2$ from `model.classIndex().simpleNames()`.
  - Generate `Diagnostic` with:
    - `source = "drlx-type"`
    - `severity = Warning` (configurable via `drlx.lsp.lint.unknownTypes`)
    - `data = suggestion` (the replacement string)
- **Step 2.3**: Update `DrlxLintHelper.lint(String text, WorkspaceSemanticModel model)`:
  - Combine OOPath bracket lint + `lintUnknownTypes`.
  - Preserve backward-compatible `lint(String text)` for callers without semantic model.
- **Step 2.4**: Add unit tests in `DrlxLintHelperTest` for unknown type detection and typo suggestions.
- **Verification**: Run `mvn clean test -pl drlx-completion`.

### Phase 3: `DrlxCodeActionHelper` (Item #10)

- **Step 3.1**: Create `org.drools.drlx.completion.DrlxCodeActionHelper`:
  - Filter `context.getDiagnostics()` that overlap the requested `Range`.
  - Check `diagnostic.getSource().equals("drlx-type")`.
  - Extract suggestion from `diagnostic.getData()` (handling `String` and Gson `JsonPrimitive`).
  - Validate non-null range and suggestion.
  - Build `CodeAction`:
    - `title = "Replace with '" + suggestion + "'"`
    - `kind = CodeActionKind.QuickFix`
    - `diagnostics = [diagnostic]`
    - `edit = WorkspaceEdit` with `TextEdit(diagnostic.getRange(), suggestion)` for `uri`.
- **Step 3.2**: Write unit tests in `org.drools.drlx.completion.DrlxCodeActionHelperTest`:
  - Matching diagnostic overlapping range produces QuickFix.
  - Diagnostic outside range produces no actions.
  - Diagnostic without suggestion data produces no actions.
- **Verification**: Run `mvn clean test -pl drlx-completion`.

### Phase 4: Server Integration & End-to-End Tests

- **Step 4.1**: In `DrlxLspServer.java`, advertise `initializeResult.getCapabilities().setCodeActionProvider(true)`.
- **Step 4.2**: In `DrlxLspDocumentService.java`:
  - Update `validate(String uri)` to call `DrlxLintHelper.lint(text, model)`.
  - Implement `codeAction(CodeActionParams params)` returning `CompletableFuture<List<Either<Command, CodeAction>>>`.
- **Step 4.3**: Add integration tests in `drlx-lsp-server` (`DrlxLspDocumentServiceTest.java`):
  - Document opened with typo type name (e.g. `Preson`).
  - Diagnostics publish `drlx-type` with `data="Person"`.
  - Client sends `codeAction` request.
  - Server returns QuickFix `Replace with 'Person'`.
- **Verification**: Run full build and tests: `mvn clean install`.

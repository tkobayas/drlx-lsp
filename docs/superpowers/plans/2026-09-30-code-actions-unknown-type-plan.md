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
  - Implement exact lookups: `getBySimpleName(simpleName)`, `containsSimpleName(simpleName)`, `containsFqcn(fqcn)`.
  - Implement `simpleNames()`, prefix search `getMatching(prefix)`, `getAll()`, `merge(...)`, and `empty()`.
- **Step 1.2**: Write unit tests in `org.drools.drlx.completion.semantic.ClassIndexTest`:
  - Test scanning classes from directories and JAR files.
  - Test exact matches vs prefix searches (e.g. `Perso` must not exact-match `Person`).
- **Step 1.3**: Update `WorkspaceSemanticModel` to build and hold `ClassIndex` and readiness flag `classpathResolved`:
  - `rebuild(ClasspathProvider classpathProvider, boolean resolved)`
  - Expose `public ClassIndex classIndex()` and `public boolean isClasspathResolved()`.
- **Verification**: Run `mvn -pl drlx-completion -am install`.

### Phase 2: Unknown Type Lint & Typo Suggestions

- **Step 2.1**: Implement Levenshtein distance calculation in `DrlxLintHelper` (bounded $\le 2$).
- **Step 2.2**: Implement `lintUnknownTypes(String text, WorkspaceSemanticModel model)` in `DrlxLintHelper`:
  - Gate on readiness: return empty list immediately if `model == null` or `!model.isClasspathResolved()`.
  - Use `DrlxHoverHelper.createParser(text)` and parse tree `parser.drlxStart()`.
  - Extract imports and unit declarations.
  - Scan actual Java type references:
    - Explicit pattern variable types: `Person $p : /persons[...]` or `Person $p = /persons[...]`.
    - Explicit local variable types: `Person p = ...`.
    - RHS instantiations: `new TypeName(...)`.
    - **Exclude OOPath roots** (`/persons` is a unit DataSource field / query, not a Java type).
  - Resolve types against `model.typeSolver()` and `model.classIndex()` using exact matches.
  - Build candidate pool of resolvable types at the use site (imported types, same unit/package, `java.lang.*`).
  - Find typo match with distance $\le 2$ from candidate pool.
  - Generate `Diagnostic` with:
    - `source = "drlx-type"`
    - `severity = Warning` (configurable via `drlx.lsp.lint.unknownTypes`)
    - `data = suggestion` (the replacement string)
- **Step 2.3**: Update `DrlxLintHelper.lint(String text, WorkspaceSemanticModel model)`:
  - Combine OOPath bracket lint + `lintUnknownTypes`.
  - Preserve backward-compatible `lint(String text)` for callers without semantic model.
- **Step 2.4**: Add unit tests in `DrlxLintHelperTest`:
  - Test unknown type detection and typo suggestions on explicit type positions and `new T(...)`.
  - Negative test: verify OOPath roots (`/persons`) and queries do not trigger unknown-type warnings.
  - Test classpath unready state returns empty list.
- **Verification**: Run `mvn -pl drlx-completion -am install`.

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
- **Verification**: Run `mvn -pl drlx-completion -am install`.

### Phase 4: Server Integration & End-to-End Tests

- **Step 4.1**: In `DrlxLspServer.java`:
  - Advertise `initializeResult.getCapabilities().setCodeActionProvider(true)`.
  - Pass `resolved = false` during Phase 1 rebuild, and `resolved = true` during Phase 2 background rebuild.
  - Trigger `textService.revalidateOpenDocuments()` once Phase 2 finishes.
- **Step 4.2**: In `DrlxLspDocumentService.java`:
  - Update `validate(String uri)` to call `DrlxLintHelper.lint(text, model)`.
  - Implement `revalidateOpenDocuments()` to refresh diagnostics for all open documents.
  - Implement `codeAction(CodeActionParams params)` returning `CompletableFuture<List<Either<Command, CodeAction>>>`.
- **Step 4.3**: Add integration tests in `drlx-lsp-server` (`DrlxLspDocumentServiceTest.java`):
  - Document opened with typo type name (e.g. `Preson $p : /persons`).
  - Diagnostics publish `drlx-type` with `data="Person"`.
  - Client sends `codeAction` request.
  - Server returns QuickFix `Replace with 'Person'`.
  - Test delayed classpath resolution: initial state emits no false diagnostics; after classpath resolves, diagnostics refresh automatically.
- **Verification**: Run full build and install: `mvn clean install`.

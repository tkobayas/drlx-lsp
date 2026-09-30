# Design Spec: Code Actions (Unknown Type Quick Fix) & ClassIndex

**Date:** 2026-09-30  
**Issue:** [#11](https://github.com/tkobayas/drlx-lsp/issues/11) Items #10 & #13  
**Status:** Proposed

---

## 1. Overview & Goal

This feature implements:
1. **Item #13: `ClassIndex`** — A workspace-wide index of available Java class names scanned from classpath directories and JAR files.
2. **`lintUnknownTypes` in `DrlxLintHelper`** — Detection of unknown/unresolved type names in DRLX documents with typo suggestions (Levenshtein distance $\le 2$) attached via `Diagnostic.data`.
3. **Item #10: Code Actions / Quick Fixes (`DrlxCodeActionHelper`)** — LSP `textDocument/codeAction` support providing `"Replace with '<suggestion>'"` quick fixes for unknown type diagnostics.

---

## 2. Architecture & Components

```
+-------------------------------------------------------------+
|                      ClassIndex (#13)                       |
| - Scans directories & JARs for .class files                 |
| - Maps simpleName -> List<FQCN> (exact matches)             |
| - Provides simpleNames() for typo suggestion candidate pool |
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|               WorkspaceSemanticModel Integration            |
| - Stores & updates ClassIndex on rebuild()                  |
| - Tracks isClasspathResolved readiness flag                 |
| - Exposes classIndex() & isClasspathResolved()              |
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|              DrlxLintHelper.lintUnknownTypes                |
| - Skips if classpath is not yet fully resolved              |
| - Finds actual Java type references (explicit variable      |
|   type declarations, RHS new T(...), etc.)                  |
|   (Explicitly NOT OOPath roots /persons which are fields)   |
| - Resolves types with TypeSolver / ClassIndex (exact match) |
| - Suggests resolvable typo candidate (Levenshtein <= 2)     |
| - Emits Diagnostic with source="drlx-type", data=suggestion |
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|                 DrlxCodeActionHelper (#10)                  |
| - Handles textDocument/codeAction                           |
| - Generates CodeAction: "Replace with '<suggestion>'"       |
| - LSP Server advertises codeActionProvider = true           |
+-------------------------------------------------------------+
```

---

## 3. Detailed Specifications

### 3.1. `ClassIndex` (`org.drools.drlx.completion.semantic.ClassIndex`)

- **Class Definition**:
  ```java
  public class ClassIndex {
      private final Map<String, List<String>> index;

      public static ClassIndex empty();
      public static ClassIndex build(Set<Path> classpathEntries);
      public static ClassIndex merge(ClassIndex base, ClassIndex overlay);

      public Set<String> simpleNames();
      public List<String> getBySimpleName(String simpleName); // Exact match
      public boolean containsSimpleName(String simpleName);   // Exact match check
      public boolean containsFqcn(String fqcn);               // Exact FQCN check
      public List<String> getMatching(String prefix);         // Prefix search for completions
      public List<String> getAll();
      public int size();
  }
  ```
- **Scanning Logic**:
  - For directory entries: recursively find `.class` files, relativize paths to FQCN.
  - For `.jar` files: read `JarFile` entries ending with `.class`.
  - Filter out inner classes (`$`), `module-info`, `package-info`.
  - Key map by simple class name (e.g. `"Person"` -> `["com.example.Person"]`).

### 3.2. `WorkspaceSemanticModel` Integration & Classpath Readiness

- Fields:
  - `private volatile ClassIndex classIndex = ClassIndex.empty();`
  - `private volatile boolean classpathResolved = false;`
- In `rebuild(ClasspathProvider classpathProvider, boolean resolved)`:
  - `this.classIndex = ClassIndex.build(classpathProvider.classpathEntries());`
  - `this.classpathResolved = resolved;`
- Getters:
  - `public ClassIndex classIndex() { return classIndex; }`
  - `public boolean isClasspathResolved() { return classpathResolved; }`

### 3.3. `DrlxLintHelper.lintUnknownTypes`

- **Readiness Gating**:
  - If `model == null` or `!model.isClasspathResolved()`, skip `lintUnknownTypes` and return empty list to prevent false positives while Maven dependencies are resolving.
- **Configuration**:
  - System property: `drlx.lsp.lint.unknownTypes` (default: `"warning"`, options: `off|hint|info|warning|error`).
- **Detection Targets (Actual Java Type Positions)**:
  - Explicit pattern variable types: e.g. `Person $p : /persons[...]` or `Person $p = /persons[...]`.
  - Explicit local variable types: e.g. `Person p = ...`.
  - Consequence / RHS object instantiations: e.g. `new Preson(...)`.
  - **Explicitly Excluded**: OOPath root names (e.g. `/persons`) — these are rule-unit DataSource fields or query names, resolved via unit context, not Java type references.
- **Verification (Exact Match)**:
  - Fully qualified names (containing `.`): verified against `model.typeSolver()` or `model.classIndex().containsFqcn(fqcn)`.
  - Simple names: resolved against document imports, current unit/package, `java.lang.*`, or single unambiguous match in `model.classIndex()`.
  - Exact match is required — prefix matches (`getMatching()`) are NOT used for existence checks.
- **Typo Candidate Pool (Resolvability Guarantee)**:
  - Only propose typo suggestions that will actually resolve at the use site:
    - Types already imported in the document.
    - Types in `java.lang.*`.
    - Types in the current unit/package.
    - Types in `model.classIndex().simpleNames()` whose package is already imported or single known type.
  - Compute Levenshtein distance ($\le 2$) against this resolvable candidate pool.
- **Diagnostic Generation**:
  - `range`: span of the misspelled type name.
  - `source`: `"drlx-type"`.
  - `severity`: configured severity (default: `DiagnosticSeverity.Warning`).
  - `message`: `"Unknown type 'Preson'. Did you mean 'Person'?"` (or `"Unknown type 'Preson'"` if no suggestion).
  - `data`: suggestion string (e.g. `"Person"`).

### 3.4. `DrlxCodeActionHelper` (`org.drools.drlx.completion.DrlxCodeActionHelper`)

- **Public API**:
  ```java
  public final class DrlxCodeActionHelper {
      public static List<Either<Command, CodeAction>> codeActions(
              String uri,
              String text,
              Range range,
              CodeActionContext context,
              WorkspaceSemanticModel model);
  }
  ```
- **Quick Fix Logic**:
  - Iterate over `context.getDiagnostics()` that overlap `range`.
  - Filter for `d.getSource().equals("drlx-type")`.
  - Extract suggestion from `d.getData()` (string or JsonPrimitive).
  - If valid suggestion exists:
    - Create `CodeAction`:
      - `title`: `"Replace with '" + suggestion + "'"`
      - `kind`: `CodeActionKind.QuickFix`
      - `diagnostics`: `Collections.singletonList(d)`
      - `edit`: `WorkspaceEdit` with `TextEdit(d.getRange(), suggestion)` for `uri`.

### 3.5. LSP Server Integration

- **`DrlxLspServer`**:
  - `initializeResult.getCapabilities().setCodeActionProvider(true);`
- **`DrlxLspDocumentService`**:
  - Expose `revalidateOpenDocuments()` to re-run validation on all open documents and publish diagnostics once Maven background resolution completes in `DrlxLspServer`.
  - Override `codeAction(CodeActionParams params)`:
    ```java
    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            String text = sourcesMap.get(uri);
            if (text == null) return Collections.emptyList();
            return DrlxCodeActionHelper.codeActions(
                uri, text, params.getRange(), params.getContext(), model
            );
        });
    }
    ```
  - `validate()`:
    ```java
    private List<Diagnostic> validate(String uri) {
        String text = sourcesMap.get(uri);
        if (text == null) return Collections.emptyList();
        List<Diagnostic> result = new ArrayList<>(DrlxDiagnosticHelper.validate(text));
        result.addAll(DrlxLintHelper.lint(text, model));
        return result;
    }
    ```
- **`DrlxLspServer`**:
  - On Phase 1 (instant build outputs): calls `model.rebuild(new MavenClasspathProvider(buildOutputDirs), false)`.
  - On Phase 2 completion (full Maven resolution): calls `model.rebuild(new MavenClasspathProvider(fullClasspath), true)`, then triggers `textService.revalidateOpenDocuments()`.

---

## 4. Testing Strategy

1. **`ClassIndexTest`**:
   - Verify scanning classes from directory and JAR.
   - Verify exact lookup (`containsSimpleName`, `getBySimpleName`, `containsFqcn`) vs prefix search (`getMatching`).
   - Verify `new Perso()` is not matched as exact when only `Person` is present.
   - Verify filtering of inner classes (`$`), `module-info`, `package-info`.
2. **`DrlxLintHelperTest` (unknown types)**:
   - Verify `lintUnknownTypes` identifies misspelled type names (e.g. `Preson $p : /persons` -> `Person`).
   - Negative tests: Verify OOPath data sources (`/persons`) and queries are NOT treated as unknown types.
   - Verify readiness gating: returns empty when `classpathResolved == false`.
   - Verify suggestions are resolvable at the use site (imported or in package).
   - Verify valid types produce no diagnostics.
3. **`DrlxCodeActionHelperTest`**:
   - Verify `codeActions()` produces `Replace with 'Person'` QuickFix for diagnostic with `data = "Person"`.
   - Verify non-overlapping ranges or unrelated diagnostics produce no actions.
4. **Server Integration (`DrlxLspDocumentServiceTest` & `DrlxLspServerTest`)**:
   - Test LSP `codeAction` request returns quick fix edit.
   - Test delayed classpath resolution: opening file before resolution yields no false warning; completing resolution revalidates and refreshes diagnostics.

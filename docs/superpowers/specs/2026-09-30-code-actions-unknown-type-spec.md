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
| - Maps simpleName -> List<FQCN>                             |
| - Provides simpleNames() for typo suggestion candidate pool |
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|               WorkspaceSemanticModel Integration            |
| - Stores & updates ClassIndex on rebuild()                  |
| - Exposes classIndex()                                      |
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|              DrlxLintHelper.lintUnknownTypes                |
| - Finds type references in DRLX (OOPath, RHS new T, etc.)   |
| - Checks if type is known via TypeSolver / ClassIndex       |
| - Computes closest typo suggestion (Levenshtein <= 2)       |
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
      public List<String> getMatching(String prefix);
      public List<String> getAll();
      public int size();
  }
  ```
- **Scanning Logic**:
  - For directory entries: recursively find `.class` files, relativize paths to FQCN.
  - For `.jar` files: read `JarFile` entries ending with `.class`.
  - Filter out inner classes (`$`), `module-info`, `package-info`.
  - Key map by simple class name (e.g. `"Person"` -> `["com.example.Person"]`).

### 3.2. `WorkspaceSemanticModel` Integration

- Field: `private volatile ClassIndex classIndex = ClassIndex.empty();`
- In `rebuild(ClasspathProvider classpathProvider)`:
  - `this.classIndex = ClassIndex.build(classpathProvider.classpathEntries());`
- Getter: `public ClassIndex classIndex() { return classIndex; }`

### 3.3. `DrlxLintHelper.lintUnknownTypes`

- **Configuration**:
  - System property: `drlx.lsp.lint.unknownTypes` (default: `"warning"`, options: `off|hint|info|warning|error`).
- **Detection Targets**:
  - OOPath root identifiers / types (e.g. `var $p = /Persons[...]` or `/com.sample.Person[...]` -> check type `Persons` or `Person`).
  - RHS object instantiations (`new Typename(...)`).
  - Any declared package/imports are taken into account.
- **Verification & Typo Matching**:
  - If a type cannot be solved via `WorkspaceSemanticModel.typeSolver()` or `ClassIndex.getMatching(simpleName)`:
    - Search `ClassIndex.simpleNames()` (plus common `java.lang.*` types) for candidates with Levenshtein distance $\le 2$.
    - Pick candidate with minimal edit distance.
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

---

## 4. Testing Strategy

1. **`ClassIndexTest`**:
   - Verify scanning classes from directory and JAR.
   - Verify `simpleNames()`, `getMatching()`, filtering of inner classes.
2. **`DrlxLintHelperTest` (unknown types)**:
   - Verify `lintUnknownTypes` identifies misspelled type names (e.g. `Preson` -> `Person`).
   - Verify suggestion attached to `Diagnostic.data`.
   - Verify valid types produce no diagnostics.
3. **`DrlxCodeActionHelperTest`**:
   - Verify `codeActions()` produces `Replace with 'Person'` QuickFix for diagnostic with `data = "Person"`.
   - Verify non-overlapping ranges or unrelated diagnostics produce no actions.
4. **Server Integration (`DrlxLspDocumentServiceTest`)**:
   - Test LSP `codeAction` request returns quick fix edit.

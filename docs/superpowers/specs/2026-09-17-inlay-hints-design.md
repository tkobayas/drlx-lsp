# Design: Inlay Hints (Issue #11 item #7)

## Overview

Implement LSP Inlay Hints support (`textDocument/inlayHint`) in `drlx-lsp`.
Inlay hints provide inline type annotations for implicitly typed and bound variables in DRLX files, such as OOPath pattern bindings (`var $p = /persons`), constraint bindings (`/persons[$a: age]`), accumulate bindings (`var $cnt = count(...)`), and consequence `var` local variables.

## Scope & Target Bindings

The inlay hints display type annotations (`InlayHintKind.Type`) formatted as `: SimpleTypeName` at the end position of the variable identifier.

### 1. OOPath Pattern Bindings
- `var $p = /persons` -> Inlay hint at `$p`: `: Person`
- Explicit type declarations such as `Person $p = /persons` will **not** show an inlay hint (as the type is already explicit).

### 2. Constraint Bindings
- `/persons[ $a: age ]` or `/persons[ $name: name ]` -> Inlay hint at `$a`: `: int`, `$name`: `: String`

### 3. Accumulate Bindings
- `var $cnt = count(/items)` -> Inlay hint at `$cnt`: `: Long`
- `var $sum = sum(/items[price])` -> Inlay hint at `$sum`: `: Number`

### 4. Consequence (RHS) `var` Declarations
- `var msg = "Hello"` -> Inlay hint at `msg`: `: String`
- `var count = 10` -> Inlay hint at `count`: `: int` / `: Integer`

## Architecture & Components

### 1. `DrlxInlayHintHelper` (`drlx-completion`)
- Package: `org.drools.drlx.completion`
- Entry method:
  ```java
  public static List<InlayHint> inlayHints(String text, Range range, WorkspaceSemanticModel model)
  ```
- Implementation details:
  - Parses text using `DrlxParser.drlxStart()`.
  - Walks the AST/ParseTree using an ANTLR listener or visitor.
  - For each binding/variable definition:
    - Resolves type using `CompletionContext` / `SemanticType` inference methods.
    - If a valid type is resolved, creates `InlayHint`:
      - `position`: `new Position(token.getLine() - 1, token.getCharPositionInLine() + token.getText().length())`
      - `label`: `Either.forLeft(": " + simpleTypeName)`
      - `kind`: `InlayHintKind.Type`
      - `paddingLeft`: `true`
  - If a `range` parameter is provided, filters results to only hints whose positions lie within the requested `range`.

### 2. `DrlxLspDocumentService` (`drlx-lsp-server`)
- Implement `inlayHint(InlayHintParams params)`:
  ```java
  @Override
  public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
      String uri = params.getTextDocument().getUri();
      String text = documentContents.get(uri);
      Range range = params.getRange();
      WorkspaceSemanticModel model = getWorkspaceSemanticModel(uri);
      return CompletableFuture.supplyAsync(() -> DrlxInlayHintHelper.inlayHints(text, range, model));
  }
  ```

### 3. `DrlxLspServer` Server Capabilities (`drlx-lsp-server`)
- Advertise capability in `initialize()`:
  ```java
  initializeResult.getCapabilities().setInlayHintProvider(true);
  ```

## Testing Strategy

1. **Unit tests in `DrlxInlayHintHelperTest` (`drlx-completion`)**:
   - Test OOPath pattern binding with `var` (`var $p = /persons`).
   - Test constraint bindings (`/persons[$a: age, $n: name]`).
   - Test accumulate binding (`var $cnt = count(/items)`).
   - Test consequence `var` declarations.
   - Test that explicit types (e.g. `Person $p = /persons`) do not produce hints.
   - Test range filtering.

2. **Integration tests in `DrlxLspDocumentServiceTest` (`drlx-lsp-server`)**:
   - Send `textDocument/inlayHint` request via `DrlxLspDocumentService` and verify returned list of `InlayHint`s.
   - Verify server capabilities report `inlayHintProvider`.

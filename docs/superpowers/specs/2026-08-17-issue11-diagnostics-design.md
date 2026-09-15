# Design: Diagnostics — Syntax Error Reporting (Issue #11, Item 1)

## Goal

Surface ANTLR syntax errors as LSP diagnostics so editors show red squiggly underlines as the user types. This is the first non-completion LSP feature for drlx-lsp.

```
rule R1 {
    var p : /persons[age > 18
}                              <- red underline: missing ']' or other syntax error
```

## Current state

`DrlxLspDocumentService.validate()` exists and is already called from `didOpen` and `didChange` via `publishDiagnostics`. It returns an empty list with a `// TODO` comment. The push notification plumbing is fully wired — only the validation logic is missing.

drlx-lsp uses `DrlxLexer`/`DrlxParser` from the `drlx-parser` sibling project (ANTLR-generated). The completion helper already creates these in `DrlxCompletionHelper.createDrlxParser(String text)`.

## Reference

drools-lsp's `DRLDiagnosticHelper` does exactly this for classic DRL using `DRL10Parser`. The approach translates directly to DRLX — the only differences are the parser/lexer classes and the entry rule name (`drlxStart` vs `compilationUnit`).

## Approach

Single new class in `drlx-completion` with a `BaseErrorListener` that collects ANTLR errors into LSP `Diagnostic` objects. Wire the existing `validate()` stub to call it.

No server capability registration needed — `publishDiagnostics` is a server-push notification (already wired), not a pull capability like `diagnosticProvider`.

## Changes

### 1. DrlxDiagnosticHelper — new class in drlx-completion

`drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDiagnosticHelper.java`

```java
public final class DrlxDiagnosticHelper {

    public static List<Diagnostic> validate(String text) { ... }
}
```

**`validate(String text)`:**
1. Return empty list for null/empty input
2. Create `DrlxLexer` + `DrlxParser` (same as `DrlxCompletionHelper.createDrlxParser`)
3. Remove default error listeners from both lexer and parser
4. Attach a `CollectingErrorListener` to both
5. Call `parser.drlxStart()` (the DRLX entry rule)
6. Return collected diagnostics

**`CollectingErrorListener`** (private inner class extending `BaseErrorListener`):
- Converts each `syntaxError` callback into an LSP `Diagnostic` with:
  - **Range**: token-aware positioning
    - Normal token: `(line-1, charPos)` to `(line-1, charPos + tokenText.length())`
    - EOF token: anchor on last real character (don't widen by the 5-char `<EOF>` placeholder)
    - Null/multiline token text: fall back to 1-char caret range
  - **Severity**: `DiagnosticSeverity.Error`
  - **Source**: `"drlx-parser"`
  - **Message**: ANTLR's error message verbatim

### 2. DrlxLspDocumentService — wire validate()

Replace the stub body:

```java
private List<Diagnostic> validate() {
    // TODO: Implement Drlx validation
    return Collections.emptyList();
}
```

With:

```java
private List<Diagnostic> validate(String uri) {
    String text = sourcesMap.get(uri);
    if (text == null) {
        return Collections.emptyList();
    }
    return DrlxDiagnosticHelper.validate(text);
}
```

Update the two call sites in `didOpen` and `didChange` to pass the URI.

### 3. Tests — DrlxDiagnosticHelperTest

`drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDiagnosticHelperTest.java`

| Test | Input | Assertion |
|------|-------|-----------|
| `cleanFileProducesNoDiagnostics` | Valid DRLX with rule | Empty list |
| `syntaxErrorProducesDiagnostic` | `rule R1 { var p : /persons[ }` (unclosed bracket) | Non-empty list |
| `diagnosticHasRangeSeverityAndSource` | Broken DRLX | Each diagnostic has: severity=Error, source="drlx-parser", non-blank message, valid range |
| `multipleBrokenRulesReportMultiple` | Two rules with errors | At least 2 diagnostics on distinct lines |
| `lexerErrorProducesDiagnostic` | Unterminated string literal | Non-empty list, range has non-negative start |
| `eofErrorRangeStaysWithinText` | Truncated input `rule R1 {` | Range end ≤ text length |
| `nullTextReturnsEmpty` | `null` | Empty list |
| `emptyTextReturnsEmpty` | `""` | Empty list |

Test inputs use DRLX syntax (not classic DRL) — e.g. `rule R1 { ... }` with braces, `var p : /persons`, etc.

## Files touched (source repo)

New:
- `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDiagnosticHelper.java`
- `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDiagnosticHelperTest.java`

Edit:
- `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java` — replace `validate()` body, add URI parameter to call sites

## Risk

Low. The ANTLR error listener mechanism is the same for all ANTLR grammars — the only DRLX-specific parts are the parser/lexer class names and the entry rule. The server-side push notification plumbing already works (just passes through empty lists today). The main risk is that DRLX's error recovery may produce noisy or unhelpful error messages for common incomplete-input patterns (e.g. typing mid-rule), but this is inherent to ANTLR and can be refined later without architectural changes.

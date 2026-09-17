# Lint Design: `DrlxLintHelper` — OOPath Filter Bracket Balance

**Date:** 2026-09-18  
**Issue:** [#11](https://github.com/tkobayas/drlx-lsp/issues/11) item #9  
**Status:** Approved

---

## Goal

Implement `DrlxLintHelper`, a heuristic lint pass that complements the ANTLR
syntax diagnostics from `DrlxDiagnosticHelper` with a friendlier, position-accurate
warning anchored at the offending `[` or `]` in an OOPath filter expression.

The ANTLR parser reports structural mistakes far from their cause (e.g. a missing
`]` surfaces as an error at `COMMA` or `RBRACE`). `DrlxLintHelper` detects the
same problem at the `[` itself, making it easier for the developer to locate the
error.

---

## Scope

**In scope:**
- `[` / `]` imbalance inside OOPath filter expressions within `rule { ... }` pattern
  items (the LHS, before `do`).

**Out of scope (explicitly excluded):**
- `do { ... }` consequence bodies — `[` appears in Java array access/creation and
  would cause false positives.
- Global scope (imports, unit declarations, class definitions).
- Unknown-type detection, missing semicolons, or any other lint pass — future items.

---

## Architecture

### Module placement

```
drlx-completion/src/main/java/org/drools/drlx/completion/DrlxLintHelper.java
drlx-completion/src/test/java/org/drools/drlx/completion/DrlxLintHelperTest.java
```

`DrlxLintHelper` is a stateless utility class (private constructor, static methods
only), consistent with `DrlxDiagnosticHelper` and all other helpers in the module.

### Public API

```java
public static List<Diagnostic> lint(String text)
```

Returns an empty list for null/empty input or when all passes are disabled.

---

## Implementation Detail

### sanitize(String text)

Blanks out comment and string-literal content while preserving line/column offsets,
so that `[` / `]` inside strings or comments cannot affect the bracket depth count.

- `//` line comments → blanked to end of line
- `/* ... */` block comments → blanked (newlines preserved)
- `"..."` string literals → interior blanked

### lintUnbalancedOopathBrackets(sanitized, severity)

**State machine** over sanitized lines:

| State | Transition trigger |
|---|---|
| `OUTSIDE_RULE` → `IN_PATTERN` | Line matches `rule` keyword pattern and `{` is consumed |
| `IN_PATTERN` → `IN_CONSEQUENCE` | `do` keyword detected at start of a rule item |
| `IN_PATTERN` → `OUTSIDE_RULE` | Closing `}` that balances the rule-open `{` |
| `IN_CONSEQUENCE` → `OUTSIDE_RULE` | Closing `}` that balances the rule-open `{` |

**Bracket tracking (IN_PATTERN only):**

For each character in the line (left to right):
- `[` → `depth++`; push `{line, col}` onto `openStack`
- `]` → if `depth > 0`: `depth--`; pop `openStack`. If `depth == 0`: emit
  *unmatched `]`* diagnostic at this column.

**End-of-pattern-item flush:**
`(` / `)` are **not** tracked — only `[` and `]`. A pattern item ends when a top-level
`,` is seen (i.e. bracket `depth == 0` at the `,`) or when the rule body `}` is
reached. At that point, any entries remaining in `openStack` are emitted as
*unclosed `[`* diagnostics. The depth and stack are reset for the next pattern item.

Note: `(` inside a filter expression (e.g. `age > (10 + 5)`) does not affect the
end-of-pattern detection because `,` between pattern items always appears outside
any `[...]` context when the file is syntactically valid; when `[` is unclosed the
flush fires at the `do` keyword or rule-closing `}` instead.

**Why reset per pattern item:** Each OOPath expression (`var $p = /persons[...],`)
is syntactically independent. An unclosed `[` in one item cannot carry over to the
next item.

**Rule keyword detection:** A line matches the rule start when it contains the
token `rule` followed (on the same or next non-blank character) by an identifier or
quoted string, per DRLX grammar (`rule identifier { ... }`). In the sanitized text
this is detected by the regex `^\s*rule\b`.

### Diagnostic shape

| Field | Value |
|---|---|
| `severity` | Resolved from system property (default `Warning`) |
| `source` | `"drlx-lint"` |
| `message` (unclosed) | `"Unclosed '[' in OOPath filter — missing ']'"` |
| `message` (unmatched) | `"Unmatched ']' — no corresponding '['"` |
| `range` | Single character at the offending `[` or `]` |

### Configuration

```
System property: drlx.lsp.lint.unbalancedOopathBrackets
Values: off | hint | info | warning | error
Default: warning
```

`off` disables the pass and returns no diagnostics. Unknown values fall back to
`warning`.

---

## Server Integration

`DrlxLspDocumentService.validate(String uri)` is extended to merge lint results
with ANTLR syntax diagnostics:

```java
private List<Diagnostic> validate(String uri) {
    String text = sourcesMap.get(uri);
    if (text == null) return Collections.emptyList();
    List<Diagnostic> result = new ArrayList<>(DrlxDiagnosticHelper.validate(text));
    result.addAll(DrlxLintHelper.lint(text));
    return result;
}
```

No new LSP capability needs to be advertised — lint diagnostics are delivered
through the existing `textDocument/publishDiagnostics` notification.

---

## Tests

### Unit tests — `DrlxLintHelperTest`

| Test | Expected |
|---|---|
| `cleanRuleIsClean` | Normal rules (with and without filters) → empty |
| `unclosedBracketIsReported` | `var $p = /persons[ age > 18,` → Warning at `[` |
| `unmatchedCloseBracketIsReported` | `var $p = /persons] age > 18,` → Warning at `]` |
| `multipleUnclosedBracketsReported` | Two unclosed `[` in same pattern line → two warnings |
| `bracketInStringIsIgnored` | `name == "["` inside filter → ignored |
| `bracketInCommentIsIgnored` | `// [` on a pattern line → ignored |
| `consequenceBracketsIgnored` | `do { int[] arr = new int[3]; }` → no warnings |
| `passDisabledBySystemProperty` | `drlx.lsp.lint.unbalancedOopathBrackets=off` → empty |
| `nullAndEmptyReturnEmpty` | `null` / `""` → empty |

### Integration test — `DrlxLspDocumentServiceTest`

`lint_reportsUnclosedOopathBracket`: open a document containing
`var $p = /persons[ age > 18,`; assert that the captured `publishDiagnostics`
list contains at least one entry with `source == "drlx-lint"`.

---

## Constraints

- No dependency on ANTLR parse tree (heuristic pass must work even when parsing
  fails partially).
- Max diagnostics per pass: 20 (consistent with drools-lsp pattern).
- `DrlxLintHelper` must not call `DrlxDiagnosticHelper` — callers merge results.

# Doc Comment Support — Spec

**Issue:** #11 item #16
**Date:** 2026-10-01
**Approach:** Direct regex-based port from drools-lsp, adapted for DRLX syntax

## Summary

Two static utility classes that parse Javadoc-style `/** ... */` block comments attached to DRLX top-level declarations and render them as markdown for LSP hover tooltips.

- `DrlxDocCommentParser` — extracts doc comments and maps each to its declaration name
- `DrlxDocFormatter` — expands inline Javadoc tags (`{@code}`, `{@literal}`, `{@link}`) into markdown

No consumers are wired in this task. `DrlxHoverHelper` integration is a separate task.

## DrlxDocCommentParser

### Supported declarations

The parser recognises `/** ... */` blocks immediately preceding these DRLX constructs:

| Declaration | Example | Captured name |
|---|---|---|
| `rule` | `rule R1 {` | `R1` |
| `unit` | `unit MyUnit;` | `MyUnit` |
| `window` | `window LastEvents {` | `LastEvents` |

Annotations and whitespace between the doc block and the declaration keyword are allowed (e.g. `/** doc */ @Salience(10) rule R1`).

### Regex design

Two patterns, mirroring drools-lsp:

- `DOC_BLOCK` — matches `/** ... */` (exactly two stars after `/`, lazy body). Unchanged from drools-lsp.
- `DECL_AFTER_DOC` — anchored at start-of-remaining-text after `*/`. Matches optional whitespace/line-comments/annotations, then one of:
  - `rule\s+(\w+)` — capture group 1: rule name
  - `unit\s+([\w.]+)` — capture group 2: qualified unit name (dots allowed)
  - `window\s+(\w+)` — capture group 3: window name

### API

```java
public final class DrlxDocCommentParser {
    // Full parse: returns declarationName → docBody map
    public static Map<String, String> parseDocs(String text)

    // Convenience: returns docBody for a single name, or null
    public static String docFor(String text, String name)

    // Package-private: strip leading * markers from doc body
    static String stripDocStars(String raw)
}
```

### Package

`org.drools.drlx.completion`

## DrlxDocFormatter

Straight port from drools-lsp — inline tag expansion is syntax-agnostic.

### Supported inline tags

| Tag | Output |
|---|---|
| `{@code text}` | `` `text` `` |
| `{@literal text}` | markdown-escaped text |
| `{@link Name}` | `[Name](href)` if resolved, else `` `Name` `` |
| `{@link Name Label}` | `[Label](href)` if resolved, else `` `Label` `` |
| `{@linkplain Name}` | `[Name](href)` if resolved, else `Name` (plain) |

Unsupported tags are left untouched.

### API

```java
public final class DrlxDocFormatter {
    public static String format(String body, Map<String, String> linkTargets)
}
```

### Package

`org.drools.drlx.completion`

## Testing

### DrlxDocCommentParserTest

Adapted for DRLX syntax:

1. `emptyOrNullReturnsEmpty` — null/empty input → empty map
2. `docOnRuleIsCaptured` — `/** doc */ rule R1 {`
3. `docOnUnitIsCaptured` — `/** doc */ unit MyUnit;`
4. `docOnWindowIsCaptured` — `/** doc */ window W1 {`
5. `docOnAnnotatedRuleIsCaptured` — `/** doc */ @Salience(10) rule R1 {`
6. `docNotFollowedByDeclIsIgnored` — floating `/** ... */` without declaration
7. `multipleDocsOnDifferentDeclsAreAllCaptured` — two rules with docs
8. `bannerCommentIsNotADoc` — `/**** banner ****/` is ignored
9. `docConvenienceLookup` — `docFor()` convenience method

### DrlxDocFormatterTest

Identical to drools-lsp (syntax-agnostic):

1. `inlineCodeBecomesBackticks`
2. `literalContentIsMarkdownEscaped`
3. `linkWithTargetRendersMarkdownLink`
4. `linkLabelIsUsedWhenPresent`
5. `memberReferenceLooksUpTheTypePart`
6. `unresolvedLinkFallsBackToCode`
7. `unresolvedLinkplainFallsBackToPlainText`
8. `unsupportedTagsAreLeftUntouched`
9. `nullAndEmptyPassThrough`

## Out of scope

- Wiring into `DrlxHoverHelper` — separate task
- Workspace-level doc lookup (cross-file) — depends on WorkspaceTypeIndex (#19)
- Block tags (`@param`, `@return`, `@see`) — not supported by drools-lsp either

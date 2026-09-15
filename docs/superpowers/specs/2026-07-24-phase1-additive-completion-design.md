# Phase 1: Additive Completion

Related: [#5 Review and consider incomplete code completion approach](https://github.com/tkobayas/drlx-lsp/issues/5)

Parent design: `Completion_Redesign.md`

## Goal

Fix the either/or completion gate so keyword completions and semantic completions are both surfaced when the grammar offers both at a given position. Keep the current semantic resolver (TolerantDrlxToJavaParserVisitor) unchanged.

## Problem

`DrlxCompletionHelper.getCompletionItems()` (line 70-79) uses `isMajorIdentifierRule` as an exclusive gate:

```java
if (isMajorIdentifierRule(candidates)) {
    return createSemanticCompletions(parser, parseTree, caretTokenIndex);
}
return /* keyword items only */;
```

When C3 reports both keyword tokens and `RULE_identifier` as candidates (e.g., at the start of a rule item where `not`, `exists`, `if`, `match`, `do` are valid alongside a `boundOopath` pattern), the semantic branch wins and keywords are discarded.

## Change

Modify `getCompletionItems` to always build keyword items first, then add semantic items when applicable.

### Modified method

```java
static List<CompletionItem> getCompletionItems(DrlxParser parser, int caretTokenIndex, ParseTree parseTree) {
    CodeCompletionCore core = new CodeCompletionCore(parser, PREFERRED_RULES, Tokens.IGNORED);
    CodeCompletionCore.CandidatesCollection candidates = core.collectCandidates(caretTokenIndex, null);

    logger.info("getCompletionItems: candidates = {}", candidates);

    List<CompletionItem> items = new ArrayList<>();

    // 1. Always: keyword completions from candidates.tokens
    candidates.tokens.keySet().stream()
            .filter(Objects::nonNull)
            .map(integer -> parser.getVocabulary().getDisplayName(integer).replace("'", ""))
            .map(String::toLowerCase)
            .map(k -> createCompletionItem(k, CompletionItemKind.Keyword))
            .forEach(items::add);

    // 2. Additionally: semantic completions when identifier rule applies
    if (isMajorIdentifierRule(candidates)) {
        items.addAll(createSemanticCompletions(parser, parseTree, caretTokenIndex));
    }

    // 3. Deduplicate by (insertText, kind)
    return deduplicateItems(items);
}
```

### Deduplication helper

`CompletionItem` does not implement `equals`/`hashCode`. Add a deduplication method keyed by `(insertText, kind)`:

```java
private static List<CompletionItem> deduplicateItems(List<CompletionItem> items) {
    Set<String> seen = new HashSet<>();
    List<CompletionItem> result = new ArrayList<>();
    for (CompletionItem item : items) {
        String key = item.getInsertText() + "::" + item.getKind();
        if (seen.add(key)) {
            result.add(item);
        }
    }
    return result;
}
```

## Scope

### What changes

- `DrlxCompletionHelper.getCompletionItems()` — logic change from either/or to additive
- New `deduplicateItems()` private method
- Test assertions updated

### What stays unchanged

- `createSemanticCompletions` — still uses TolerantDrlxToJavaParserVisitor, still adds `IDENTIFIER` fallback when semantic resolution produces nothing
- `isMajorIdentifierRule` / `MINOR_IDENTIFIER_RULES` — same logic, role shifts from exclusive gate to enrichment trigger
- `PREFERRED_RULES` — stays `{RULE_identifier}` only
- All downstream methods (`createTypeBasedCompletions`, `addDirectPropertyAccess`, etc.)
- `Tokens.IGNORED` — unchanged

### Files touched

- `drlx-lsp/drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java`
- `drlx-lsp/drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java`
- `drlx-lsp/drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperNewConstructsTest.java`
- `drlx-lsp/drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java`

## Test changes

### Tests that asserted `containsOnly("IDENTIFIER")`

These positions now also produce keyword tokens from C3. Update to `contains("IDENTIFIER")`:

- `DrlxCompletionHelperTest.testRuleDeclaration` — "after rule", "after /", "after var" positions
- `DrlxCompletionHelperTest.testClassDeclaration` — "after public class" position
- `DrlxCompletionHelperNewConstructsTest.ruleWithParameters_identifierExpectedForType`
- `DrlxCompletionHelperNewConstructsTest.oopathChained_identifierExpected`
- `DrlxCompletionHelperNewConstructsTest.windowFilter_identifierExpectedForBind`
- `DrlxCompletionHelperIncompleteCodeTest.incompleteRule_pattern`

### Tests that asserted keyword presence

Should continue to pass — keywords are now always included:

- `DrlxCompletionHelperTest.testRuleDeclaration` — `contains("rule")`, `contains("int", "var", "if")`
- `DrlxCompletionHelperTest.testClassDeclaration` — all keyword assertions
- `DrlxCompletionHelperNewConstructsTest.consequence_javaExpressionsOffered`

### Tests that asserted semantic completions

Should continue to pass — semantic completions are still added. Now keywords appear alongside:

- `DrlxCompletionHelperIncompleteCodeTest.incompleteRule_consequence_System` — `contains("out", "in", "gc")`
- `DrlxCompletionHelperIncompleteCodeTest.incompleteRule_consequence_SystemOut` — `contains("println")`
- `DrlxCompletionHelperIncompleteCodeTest.incompleteClass_inlineCast` — `contains("trimToSize")`
- `DrlxCompletionHelperIncompleteCodeTest.incompleteClass_BigDecimalLiteral` — `contains("precision")`
- `DrlxCompletionHelperIncompleteCodeTest.incompleteClass_PropertyAccessor` — `contains("city", "getCity", "setCity")`

### No-crash tests

Unchanged — they only assert `isNotNull()`.

## Verification

1. `mvn -pl drlx-completion -am install` (rebuild drlx-parser dependency)
2. `mvn -pl drlx-completion test` (all tests pass)

# Phase 2: CompletionSite Enum and Context Analyzer

Related: [#5 Review and consider incomplete code completion approach](https://github.com/tkobayas/drlx-lsp/issues/5)

Parent design: `Completion_Redesign.md`

## Goal

Introduce a DRLX-specific `CompletionSite` enum that classifies caret positions by grammatical context. Replace `isMajorIdentifierRule` with `CompletionSite`-based dispatch. Semantic providers that use the richer context come in Phase 3 (workspace semantic model) and Phase 4 (expression resolver).

## New files

- `drlx-completion/src/main/java/org/drools/drlx/completion/CompletionSite.java` — the enum
- `drlx-completion/src/main/java/org/drools/drlx/completion/CompletionContextAnalyzer.java` — derives `CompletionSite` from C3 candidates

## Modified files

- `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java` — replace `isMajorIdentifierRule` dispatch with `CompletionContextAnalyzer.analyze()`
- `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxC3CandidatesTest.java` — add `CompletionSite` assertions at each position

## CompletionSite enum

```java
public enum CompletionSite {
    COMPILATION_UNIT,        // top-level: package, import, unit, class, annotation before rule
    RULE_DECLARATION,        // after 'rule' keyword: rule name position
    RULE_ITEM,               // start of a rule item: CE keywords + pattern types
    BIND_NAME,               // bind name in boundOopath (second identifier)
    ENTRY_POINT,             // OOPath root identifier (after /)
    OOPATH_CHUNK,            // OOPath chunk identifier (after / in chained path)
    CONSTRAINT_EXPRESSION,   // inside [...] constraint bracket
    CONSEQUENCE_EXPRESSION,  // inside do { ... } block
    DOT_ACCESS,              // after . in member reference
    TEST_EXPRESSION,         // after 'test' keyword
    RULE_PARAMETER,          // parameter type in rule(Type name)
    UNKNOWN;                 // fallback — no RULE_identifier candidate

    public boolean needsSemanticCompletions() {
        return this != UNKNOWN;
    }
}
```

- `needsSemanticCompletions()` returns true for all sites except `UNKNOWN` — used by `DrlxCompletionHelper` to decide whether to call `createSemanticCompletions`.
- `UNKNOWN` is returned only when `RULE_identifier` is genuinely absent from `candidates.rules`.

## CompletionContextAnalyzer

```java
public class CompletionContextAnalyzer {

    public static CompletionSite analyze(
            CodeCompletionCore.CandidatesCollection candidates,
            DrlxParser parser,
            int caretTokenIndex) { ... }
}
```

### Derivation logic

The analyzer checks in priority order:

1. **DOT_ACCESS**: Previous token (at `caretTokenIndex - 1`) is DOT
2. **Has RULE_identifier**: Check `candidates.rules.get(DrlxParser.RULE_identifier)` for the call stack:
   - Stack contains `ruleConsequence` and `block` → `CONSEQUENCE_EXPRESSION`
   - Stack contains `testElement` → `TEST_EXPRESSION`
   - Stack contains `drlxExpression` → `CONSTRAINT_EXPRESSION`
   - Stack contains `oopathRoot` → `ENTRY_POINT`
   - Stack contains `oopathChunk` → `OOPATH_CHUNK`
   - Stack contains `ruleItem` and `boundOopath` → `RULE_ITEM`
   - Stack contains `boundOopath` (without `ruleItem`) → `BIND_NAME`
   - Stack contains `ruleParameter` and `typeType` → `RULE_PARAMETER`
   - Stack contains `ruleParameter` (without `typeType`) → `BIND_NAME`
   - Stack contains `ruleDeclaration` (without `ruleBody` and without `altAnnotationQualifiedName`) → `RULE_DECLARATION`
   - Stack contains `compilationUnit` or `drlxCompilationUnit` → `COMPILATION_UNIT`
   - Otherwise → `UNKNOWN`
3. **No RULE_identifier** → `UNKNOWN`

The order within step 2 matters — more specific contexts (consequence, constraint) are checked before broader ones (ruleItem, compilationUnit). A position inside a constraint is also inside an oopathRoot/oopathChunk, but `drlxExpression` in the stack distinguishes it.

### RULE_DECLARATION vs COMPILATION_UNIT

The `ruleDeclaration` check excludes positions where `altAnnotationQualifiedName` is in the stack — those are annotation identifier positions before a `rule` keyword (e.g., `@Salience`), which belong to the top-level `COMPILATION_UNIT` context. The `RULE_DECLARATION` site is reserved for the rule name position after the `rule` keyword.

### No altAnnotationQualifiedName filter

DRLX only uses annotations before rules (`@Salience`, `@ExistenceDriven`, `@Description`, etc.) — not as Java-style variable/type modifier annotations. The `altAnnotationQualifiedName` appearing in call stacks for consequence blocks and rule parameter types is an artifact of the DRLX grammar importing the full Java grammar. The analyzer does NOT filter based on `altAnnotationQualifiedName` at those positions — `CONSEQUENCE_EXPRESSION` and `RULE_PARAMETER` are returned correctly.

### DOT_ACCESS special case

`DOT_ACCESS` is detected via token inspection rather than C3 rule stacks because:
- After a dot, C3 reports `RULE_identifier` with a generic `expression` call stack
- The dot-access context is the trigger for the current semantic resolver (TolerantDrlxToJavaParserVisitor)
- Checking the previous token is simpler and more reliable than parsing the expression rule stack

## DrlxCompletionHelper changes

Replace the `isMajorIdentifierRule` dispatch:

```java
CompletionSite site = CompletionContextAnalyzer.analyze(candidates, parser, caretTokenIndex);
if (site.needsSemanticCompletions()) {
    items.addAll(createSemanticCompletions(parser, parseTree, caretTokenIndex));
}
```

Remove `isMajorIdentifierRule` and `MINOR_IDENTIFIER_RULES`.

Guard `createSemanticCompletions` against `caretTokenIndex < 1` (e.g., empty input where `COMPILATION_UNIT` now triggers semantic completions).

## Testing

### CompletionSite assertions in DrlxC3CandidatesTest

Expected site for each position:

| Test | Expected CompletionSite |
|------|------------------------|
| `compilationUnitStart` | `COMPILATION_UNIT` |
| `beforeRule` | `COMPILATION_UNIT` |
| `afterRuleKeyword` | `RULE_DECLARATION` |
| `boundType` | `RULE_ITEM` |
| `bindName` | `RULE_ITEM` |
| `afterSlash` | `ENTRY_POINT` |
| `constraintExpression` | `CONSTRAINT_EXPRESSION` |
| `oopathChunkIdentifier` | `OOPATH_CHUNK` |
| `constraintChunkExpression` | `CONSTRAINT_EXPRESSION` |
| `ruleItemStart_beforeNot` | `RULE_ITEM` |
| `ruleItemStart_beforeExists` | `RULE_ITEM` |
| `testElementExpression` | `TEST_EXPRESSION` |
| `consequenceBlock` | `CONSEQUENCE_EXPRESSION` |
| `afterDot` | `DOT_ACCESS` |
| `ruleParameterType` | `RULE_PARAMETER` |

### Existing completion tests

All existing completion tests pass.

## Verification

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test
```

All 51 tests pass.

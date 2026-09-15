# Watch List Property Completions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Offer property names of the root entry-point type (plus `*` wildcard) when the caret is inside a property-reactive watch list `[][prop, !prop]`.

**Architecture:** New `OOPATH_WATCH_LIST` completion site detected via `RULE_watchItem` in the antlr4-c3 identifier stack. Resolves the root entry-point type and enumerates its properties using existing infrastructure (`resolveEntryPointType` + `collectAllProperties`).

**Tech Stack:** Java 17, ANTLR4, antlr4-c3, Maven, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-08-14-issue9-watch-list-completions-design.md`

## Global Constraints

- All source changes in `/home/tkobayas/usr/work/mvel3-development/drlx-lsp/drlx-completion`
- After modifying source, run `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q` before running tests
- Run tests with `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test`
- Git operations target the source repo: `git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp`

---

### Task 1: Add `OOPATH_WATCH_LIST` site and analyzer detection + tests

This single task covers the enum value, analyzer check, helper switch case, context resolution method, and two tests. The feature is small enough that all pieces are needed together for any meaningful test.

**Files:**
- Modify: `src/main/java/org/drools/drlx/completion/CompletionSite.java:19` — add enum value before `UNKNOWN`
- Modify: `src/main/java/org/drools/drlx/completion/CompletionContextAnalyzer.java:67` — add `RULE_watchItem` check
- Modify: `src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java:93-105` — add switch case + new method
- Modify: `src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java:451` — add `resolveWatchListCompletions()` method
- Modify: `src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java:501` — add 2 tests

**Interfaces:**
- Consumes: `CompletionContext.resolveEntryPointType(String)` (existing, line 326), `CompletionContext.collectAllProperties(SemanticType)` (existing, line 587), `CompletionContext.findDrlxCompilationUnit()` (existing, line 899), `CompletionContext.findEnclosingRule(DrlxCompilationUnitContext)` (existing, line 153)
- Produces: `CompletionContext.resolveWatchListCompletions()` returning `List<String>` — property names of the root entry-point type

- [ ] **Step 1: Write the two failing tests**

Add to `DrlxCompletionHelperIncompleteCodeTest.java` after the last test (line 501, before the closing `}`):

```java
@Test
void incompleteRule_watchList_emptyConstraint() {
    String text = """
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;

            rule R1 {
                var p : /persons[][
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(4);
    caretPosition.setCharacter(27); // after second '['

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    assertThat(completionItemStrings(result)).contains("name", "age", "address", "previousAddresses");
}

@Test
void incompleteRule_watchList_withConstraint() {
    String text = """
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;

            rule R1 {
                var p : /persons[age > 18][
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(4);
    caretPosition.setCharacter(35); // after second '['

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    assertThat(completionItemStrings(result)).contains("name", "age", "address", "previousAddresses");
}
```

**Caret position calculation for `incompleteRule_watchList_emptyConstraint`:**
```
Line 0: import org.drools.drlx.domain.MyUnit;
Line 1: unit MyUnit;
Line 2:
Line 3: rule R1 {
Line 4:     var p : /persons[][
                               ^ char 27 (count: "    var p : /persons[][" = 4+3+1+1+1+8+3 = 27 after second '[')
```
Text block strips leading whitespace based on closing `"""` indentation (8 spaces). The stripped line is `    var p : /persons[][`. Count from 0: positions 0-3 are spaces, 4-6 `var`, 7 space, 8 `p`, 9 space, 10 `:`, 11 space, 12 `/`, 13-19 `persons`, 20 `[`, 21 `]`, 22 `[`, so character 23 is one past `[`. Wait — let me recount. The text block has 12 leading spaces in the source, and the closing `"""` is at 12 spaces indentation, so the stripping removes 12 spaces. The actual line content is `    var p : /persons[][`. That's 4 spaces + `var p : /persons[][`.

Actually, let me recount more carefully. The source text block is:
```
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;

            rule R1 {
                var p : /persons[][
            """;
```
The closing `"""` is at 12 spaces indent. So 12 leading spaces are stripped from each line. Line 4 raw is `                var p : /persons[][` (16 spaces + text). After stripping 12: `    var p : /persons[][`.

Character positions on line 4:
- 0-3: `    ` (4 spaces)
- 4-6: `var`
- 7: ` `
- 8: `p`
- 9: ` `
- 10: `:`
- 11: ` `
- 12: `/`
- 13-19: `persons`
- 20: `[`
- 21: `]`
- 22: `[`

Caret should be at character 23 (one past the second `[`).

**Corrected caret position for `incompleteRule_watchList_withConstraint`:**
Line 4: `    var p : /persons[age > 18][`
- 0-3: `    `
- 4-6: `var`
- 7: ` `
- 8: `p`
- 9: ` `
- 10: `:`
- 11: ` `
- 12: `/`
- 13-19: `persons`
- 20: `[`
- 21-23: `age`
- 24: ` `
- 25: `>`
- 26: ` `
- 27-28: `18`
- 29: `]`
- 30: `[`

Caret should be at character 31 (one past the second `[`).

**Use these corrected values in the test code** (replacing the placeholder values above). The final test code with correct positions:

```java
@Test
void incompleteRule_watchList_emptyConstraint() {
    String text = """
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;

            rule R1 {
                var p : /persons[][
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(4);
    caretPosition.setCharacter(23);

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    assertThat(completionItemStrings(result)).contains("name", "age", "address", "previousAddresses");
}

@Test
void incompleteRule_watchList_withConstraint() {
    String text = """
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;

            rule R1 {
                var p : /persons[age > 18][
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(4);
    caretPosition.setCharacter(31);

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    assertThat(completionItemStrings(result)).contains("name", "age", "address", "previousAddresses");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxCompletionHelperIncompleteCodeTest#incompleteRule_watchList_emptyConstraint+incompleteRule_watchList_withConstraint"
```

Expected: FAIL — the completions won't contain property names because no `OOPATH_WATCH_LIST` site exists yet.

- [ ] **Step 3: Add `OOPATH_WATCH_LIST` enum value**

In `CompletionSite.java`, add `OOPATH_WATCH_LIST` before `UNKNOWN`:

```java
    AFTER_NEW,
    OOPATH_WATCH_LIST,
    UNKNOWN;
```

- [ ] **Step 4: Add `RULE_watchItem` check in CompletionContextAnalyzer**

In `CompletionContextAnalyzer.java`, add this block between the `RULE_drlxExpression` check (line 61–66) and the `RULE_oopathRoot` check (line 68):

```java
        if (identifierStack.contains(DrlxParser.RULE_watchItem)) {
            return CompletionSite.OOPATH_WATCH_LIST;
        }
```

The result at lines 61–74 should read:

```java
        if (identifierStack.contains(DrlxParser.RULE_drlxExpression)) {
            if (isQueryInvocation(parseTree, caretTokenIndex)) {
                return CompletionSite.QUERY_PARAMETER;
            }
            return CompletionSite.CONSTRAINT_EXPRESSION;
        }

        if (identifierStack.contains(DrlxParser.RULE_watchItem)) {
            return CompletionSite.OOPATH_WATCH_LIST;
        }

        if (identifierStack.contains(DrlxParser.RULE_oopathRoot)) {
            return CompletionSite.ENTRY_POINT;
        }
```

- [ ] **Step 5: Add switch case and resolution method in DrlxCompletionHelper**

In `DrlxCompletionHelper.java`, add the `OOPATH_WATCH_LIST` case to the switch in `createSemanticCompletions` (line 93):

```java
    private List<CompletionItem> createSemanticCompletions(CompletionSite site, CompletionContext ctx) {
        return switch (site) {
            case DOT_ACCESS -> resolveDotAccess(ctx);
            case INLINE_CAST_TYPE, AFTER_NEW -> resolveImportedClassNames(ctx);
            case ACCUMULATE_FUNCTION -> resolveAccumulateFunctionNames();
            case RULE_ANNOTATION -> resolveRuleAnnotationNames();
            case ENTRY_POINT -> resolveEntryPointNames(ctx);
            case OOPATH_CHUNK -> resolveOopathChunkCompletions(ctx);
            case CONSTRAINT_EXPRESSION -> resolveConstraintExpressionCompletions(ctx);
            case QUERY_PARAMETER -> resolveQueryParameterCompletions(ctx);
            case OOPATH_WATCH_LIST -> resolveWatchListCompletions(ctx);
            default -> List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
        };
    }
```

Add the new method after `resolveQueryParameterCompletions` (after line 193):

```java
    private List<CompletionItem> resolveWatchListCompletions(CompletionContext ctx) {
        List<String> names = ctx.resolveWatchListCompletions();
        List<CompletionItem> items = new ArrayList<>(names.stream()
                .map(name -> createCompletionItem(name, CompletionItemKind.Property))
                .toList());
        items.add(createCompletionItem("*", CompletionItemKind.Keyword));
        return items;
    }
```

- [ ] **Step 6: Add `resolveWatchListCompletions()` in CompletionContext**

In `CompletionContext.java`, add after `resolveConstraintCompletions()` (after line 451):

```java
    public List<String> resolveWatchListCompletions() {
        DrlxCompilationUnitContext cu = findDrlxCompilationUnit();
        if (cu == null) return List.of();

        RuleDeclarationContext enclosingRule = findEnclosingRule(cu);
        if (enclosingRule == null || enclosingRule.ruleBody() == null) return List.of();

        return findWatchListCompletionsInTree(enclosingRule.ruleBody());
    }

    private List<String> findWatchListCompletionsInTree(ParseTree node) {
        if (node instanceof OopathExpressionContext oopathExpr) {
            List<String> result = resolveWatchListProperties(oopathExpr);
            if (result != null) return result;
            return List.of();
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            List<String> result = findWatchListCompletionsInTree(node.getChild(i));
            if (!result.isEmpty()) return result;
        }
        return List.of();
    }

    private List<String> resolveWatchListProperties(OopathExpressionContext oopathExpr) {
        OopathRootContext root = oopathExpr.oopathRoot();
        if (root == null || root.identifier(0) == null) return null;

        String rootName = root.identifier(0).getText();
        SemanticType rootType = resolveEntryPointType(rootName);
        if (rootType == null) return null;

        return collectAllProperties(rootType);
    }
```

- [ ] **Step 7: Install and run all tests**

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test
```

Expected: All tests pass (134 existing + 2 new = 136 total, 0 fail, 1 pre-existing skip).

**If the two new tests fail** (c3 doesn't produce `RULE_watchItem` for incomplete `[][` input): see Fallback section below.

- [ ] **Step 8: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add \
  drlx-completion/src/main/java/org/drools/drlx/completion/CompletionSite.java \
  drlx-completion/src/main/java/org/drools/drlx/completion/CompletionContextAnalyzer.java \
  drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java \
  drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java \
  drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java

git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: add watch list property completions (issue #9, item 19)"
```

---

## Fallback: Token-Level Detection

If Step 7 reveals that c3 does not put `RULE_watchItem` in the identifier stack for incomplete `[][` input, replace the identifier-stack check in Step 4 with a token-level detection method.

Add a Phase 1 token check in `CompletionContextAnalyzer.analyze()` (after the `isAfterNew` check, before the identifier-stack section):

```java
if (isInWatchList(parser, caretTokenIndex)) {
    return CompletionSite.OOPATH_WATCH_LIST;
}
```

New helper method:

```java
private static boolean isInWatchList(DrlxParser parser, int caretTokenIndex) {
    // Scan backward for '][' pattern — the boundary between constraint and watch-list brackets
    int bracketDepth = 0;
    for (int i = caretTokenIndex - 1; i >= 0; i--) {
        Token token = parser.getTokenStream().get(i);
        if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
        int type = token.getType();
        if (type == DrlxLexer.RBRACK) bracketDepth++;
        if (type == DrlxLexer.LBRACK) {
            if (bracketDepth > 0) {
                bracketDepth--;
            } else {
                // This is the opening '[' of our bracket. Check if preceded by ']'.
                for (int j = i - 1; j >= 0; j--) {
                    Token prev = parser.getTokenStream().get(j);
                    if (prev.getChannel() != Token.DEFAULT_CHANNEL) continue;
                    return prev.getType() == DrlxLexer.RBRACK;
                }
                return false;
            }
        }
    }
    return false;
}
```

If this fallback is used, also remove the `RULE_watchItem` identifier-stack check from Step 4 to avoid duplicate detection.

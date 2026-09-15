# Phase 1: Additive Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make keyword and semantic completions additive instead of either/or, so both surface when the grammar offers both at a given position.

**Architecture:** Change the orchestration in `DrlxCompletionHelper.getCompletionItems()` from an exclusive gate (`isMajorIdentifierRule` → return semantic OR keywords) to an additive flow (always keywords, then add semantic if applicable, then deduplicate). No changes to the semantic resolver or C3 configuration.

**Tech Stack:** Java 21, antlr4-c3, JavaParser, JUnit 5, AssertJ, Maven

## Global Constraints

- `PREFERRED_RULES` stays `{RULE_identifier}` — do not add other preferred rules.
- `createSemanticCompletions` stays unchanged — still uses `TolerantDrlxToJavaParserVisitor`.
- All changes are in the `drlx-completion` module under `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`.
- Must run `mvn -pl drlx-completion -am install` before running tests (CLAUDE.md mandatory Maven rule).

---

### Task 1: Add deduplication helper and make completion additive

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java`

**Interfaces:**
- Consumes: existing `isMajorIdentifierRule`, `createSemanticCompletions`, `createCompletionItem`, `Tokens.IGNORED`, `PREFERRED_RULES`
- Produces: modified `getCompletionItems` (same signature, additive behavior), new `deduplicateItems` (private)

- [ ] **Step 1: Add `HashSet` import**

Add to the import block after `import java.util.ArrayList;`:

```java
import java.util.HashSet;
```

- [ ] **Step 2: Add `deduplicateItems` method**

Add after the `isMajorIdentifierRule` method (after line 156):

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

- [ ] **Step 3: Replace `getCompletionItems` method body**

Replace the body of the package-private `getCompletionItems` method (lines 64-79) with:

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

- [ ] **Step 4: Build**

Run:
```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests
```
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: make keyword and semantic completions additive

Replace the either/or isMajorIdentifierRule gate with additive
completion: always build keyword items from candidates.tokens,
then add semantic items when applicable, then deduplicate.

Fixes the issue where CE keywords (not, exists, if, match, do)
were suppressed when RULE_identifier was also a candidate.

Related: #5"
```

---

### Task 2: Update tests for additive completion behavior

**Files:**
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperNewConstructsTest.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java`

**Interfaces:**
- Consumes: modified `getCompletionItems` from Task 1

- [ ] **Step 1: Update `DrlxCompletionHelperTest.java`**

Three `containsOnly("IDENTIFIER")` assertions need to change to `contains("IDENTIFIER")`.

Line 44 — "after rule" position:
```java
// Before:
assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER"); // rule name is IDENTIFIER
// After:
assertThat(completionItemStrings(result)).contains("IDENTIFIER"); // rule name is IDENTIFIER
```

Line 50 — "after /" position:
```java
// Before:
assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER"); // datasource name is IDENTIFIER
// After:
assertThat(completionItemStrings(result)).contains("IDENTIFIER"); // datasource name is IDENTIFIER
```

Line 56 — "after var" position:
```java
// Before:
assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER"); // variable name is IDENTIFIER
// After:
assertThat(completionItemStrings(result)).contains("IDENTIFIER"); // variable name is IDENTIFIER
```

Line 94 — "after public class" position:
```java
// Before:
assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER"); // class name
// After:
assertThat(completionItemStrings(result)).contains("IDENTIFIER"); // class name
```

- [ ] **Step 2: Update `DrlxCompletionHelperNewConstructsTest.java`**

Three `containsOnly("IDENTIFIER")` assertions need to change.

Line 98 — `ruleWithParameters_identifierExpectedForType`:
```java
// Before:
assertThat(items).containsOnly("IDENTIFIER");
// After:
assertThat(items).contains("IDENTIFIER");
```

Line 174 — `oopathChained_identifierExpected`:
```java
// Before:
assertThat(items).containsOnly("IDENTIFIER");
// After:
assertThat(items).contains("IDENTIFIER");
```

Line 193 — `windowFilter_identifierExpectedForBind`:
```java
// Before:
assertThat(items).containsOnly("IDENTIFIER");
// After:
assertThat(items).contains("IDENTIFIER");
```

- [ ] **Step 3: Update `DrlxCompletionHelperIncompleteCodeTest.java`**

One `containsOnly("IDENTIFIER")` assertion needs to change.

Line 36 — `incompleteRule_pattern`:
```java
// Before:
assertThat(completionItemStrings(result)).containsOnly("IDENTIFIER"); // datasource name is IDENTIFIER
// After:
assertThat(completionItemStrings(result)).contains("IDENTIFIER"); // datasource name is IDENTIFIER
```

- [ ] **Step 4: Run all tests**

Run:
```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test
```
Expected: All tests pass (BUILD SUCCESS)

- [ ] **Step 5: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperNewConstructsTest.java drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "test: update assertions for additive completion

Change containsOnly(IDENTIFIER) to contains(IDENTIFIER) at
positions where keywords now also appear alongside the identifier
candidate.

Related: #5"
```

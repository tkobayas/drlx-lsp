# Rule Annotation Completions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After `@` before a `rule` keyword, offer the 11 known DRLX rule annotation names.

**Architecture:** Static list approach mirroring the existing `ACCUMULATE_FUNCTION` pattern. Add a new `CompletionSite.RULE_ANNOTATION`, detect it via `RULE_annotation` in the c3 identifier stack, return hardcoded annotation names as `CompletionItemKind.Class`.

**Tech Stack:** Java 17, ANTLR4, antlr4-c3, LSP4J, JUnit 5, AssertJ

## Global Constraints

- Source repo: `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`
- Build before testing: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q`
- Run tests: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test`
- Commit changes in the source repo, not the docs repo

---

### Task 1: Add RULE_ANNOTATION site, analyzer detection, static list, and test

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/CompletionSite.java:14` — add enum value
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/CompletionContextAnalyzer.java:37-39` — add detection branch
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java:93-104,133-139` — add switch case, static list, resolver method
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java:385` — add test

- [ ] **Step 1: Write the failing test**

Add this test method at the end of `DrlxCompletionHelperIncompleteCodeTest` (before the closing `}`):

```java
@Test
void incompleteRule_annotationNames() {
    String text = """
            unit MyUnit;

            @
            rule R1 {
                var p : /persons,
                do { System.out.println(p); }
            }
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(2);
    caretPosition.setCharacter(1); // after '@'

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    assertThat(completionItemStrings(result)).contains(
            "ActivationGroup", "DataSource", "DateEffective", "DateExpires",
            "Description", "Disabled", "Duration", "LockOnActive",
            "NoLoop", "Salience", "Timer");
}
```

Position explanation: after text-block whitespace stripping, line 0 is `unit MyUnit;`, line 1 is blank, line 2 is `@`. Character 1 is one past the `@`.

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxCompletionHelperIncompleteCodeTest#incompleteRule_annotationNames"
```
Expected: FAIL — none of the annotation names appear in completions yet.

- [ ] **Step 3: Add `RULE_ANNOTATION` to `CompletionSite`**

In `CompletionSite.java`, add `RULE_ANNOTATION` after `QUERY_PARAMETER` (line 15):

```java
    ACCUMULATE_FUNCTION,
    QUERY_PARAMETER,
    RULE_ANNOTATION,
    TEST_EXPRESSION,
```

- [ ] **Step 4: Add detection in `CompletionContextAnalyzer`**

In `CompletionContextAnalyzer.java`, add this check after the `ACCUMULATE_FUNCTION` check (after line 39) and before the `ruleConsequence` check:

```java
        if (identifierStack.contains(DrlxParser.RULE_annotation)) {
            return CompletionSite.RULE_ANNOTATION;
        }
```

- [ ] **Step 5: Add static list and resolver in `DrlxCompletionHelper`**

In `DrlxCompletionHelper.java`:

5a. Add the static list after `ACCUMULATE_FUNCTIONS` (after line 134):

```java
    private static final List<String> RULE_ANNOTATIONS =
            List.of("ActivationGroup", "DataSource", "DateEffective", "DateExpires",
                    "Description", "Disabled", "Duration", "LockOnActive",
                    "NoLoop", "Salience", "Timer");
```

5b. Add the resolver method after `resolveAccumulateFunctionNames()` (after line 139):

```java
    private List<CompletionItem> resolveRuleAnnotationNames() {
        return RULE_ANNOTATIONS.stream()
                .map(name -> createCompletionItem(name, CompletionItemKind.Class))
                .toList();
    }
```

5c. Add the switch case in `createSemanticCompletions` (after the `ACCUMULATE_FUNCTION` line):

```java
            case ACCUMULATE_FUNCTION -> resolveAccumulateFunctionNames();
            case RULE_ANNOTATION -> resolveRuleAnnotationNames();
```

- [ ] **Step 6: Build and run all tests**

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test
```

Expected: All tests pass including the new `incompleteRule_annotationNames`.

- [ ] **Step 7: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add \
    drlx-completion/src/main/java/org/drools/drlx/completion/CompletionSite.java \
    drlx-completion/src/main/java/org/drools/drlx/completion/CompletionContextAnalyzer.java \
    drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java \
    drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat(completion): add rule annotation completions (Issue #9 item 10)"
```

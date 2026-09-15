# Phase 2: CompletionSite Enum and Context Analyzer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a `CompletionSite` enum and `CompletionContextAnalyzer` to classify caret positions by grammatical context, replacing `isMajorIdentifierRule` with richer dispatch.

**Architecture:** New `CompletionSite` enum classifies positions. New `CompletionContextAnalyzer` derives the site from C3 candidates and token context. `DrlxCompletionHelper` dispatches via `site.needsSemanticCompletions()` instead of `isMajorIdentifierRule`. No `altAnnotationQualifiedName` filter — DRLX only uses annotations before rules, so positions like `CONSEQUENCE_EXPRESSION` and `RULE_PARAMETER` classify correctly.

**Tech Stack:** Java 21, antlr4-c3 1.1, JUnit 5, AssertJ, Maven

## Global Constraints

- All changes in `drlx-completion` module under `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`
- Must run `mvn -pl drlx-completion -am install -DskipTests` before running tests
- `PREFERRED_RULES` stays `{RULE_identifier}` — do not modify
- Behavior parity — all existing completion tests must pass unchanged

---

### Task 1: Create CompletionSite enum and CompletionContextAnalyzer

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/CompletionSite.java`
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/CompletionContextAnalyzer.java`

**Interfaces:**
- Consumes: `CodeCompletionCore.CandidatesCollection`, `DrlxParser` (RULE_ constants, token stream)
- Produces: `CompletionSite CompletionContextAnalyzer.analyze(CandidatesCollection, DrlxParser, int)`, `boolean CompletionSite.isIdentifierExpected()`

- [ ] **Step 1: Create CompletionSite.java**

Create `drlx-completion/src/main/java/org/drools/drlx/completion/CompletionSite.java`:

```java
package org.drools.drlx.completion;

public enum CompletionSite {
    COMPILATION_UNIT,
    RULE_DECLARATION,
    RULE_ITEM,
    BIND_NAME,
    ENTRY_POINT,
    OOPATH_CHUNK,
    CONSTRAINT_EXPRESSION,
    CONSEQUENCE_EXPRESSION,
    DOT_ACCESS,
    TEST_EXPRESSION,
    RULE_PARAMETER,
    UNKNOWN;

    public boolean needsSemanticCompletions() {
        return this != UNKNOWN;
    }
}
```

- [ ] **Step 2: Create CompletionContextAnalyzer.java**

Create `drlx-completion/src/main/java/org/drools/drlx/completion/CompletionContextAnalyzer.java`:

```java
package org.drools.drlx.completion;

import java.util.List;

import com.vmware.antlr4c3.CodeCompletionCore;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;

public class CompletionContextAnalyzer {

    private CompletionContextAnalyzer() {
    }

    public static CompletionSite analyze(
            CodeCompletionCore.CandidatesCollection candidates,
            DrlxParser parser,
            int caretTokenIndex) {

        if (isDotAccess(parser, caretTokenIndex)) {
            return CompletionSite.DOT_ACCESS;
        }

        List<Integer> identifierStack = candidates.rules.get(DrlxParser.RULE_identifier);
        if (identifierStack == null) {
            return CompletionSite.UNKNOWN;
        }

        if (identifierStack.contains(DrlxParser.RULE_ruleConsequence)
                && identifierStack.contains(DrlxParser.RULE_block)) {
            return CompletionSite.CONSEQUENCE_EXPRESSION;
        }

        if (identifierStack.contains(DrlxParser.RULE_testElement)) {
            return CompletionSite.TEST_EXPRESSION;
        }

        if (identifierStack.contains(DrlxParser.RULE_drlxExpression)) {
            return CompletionSite.CONSTRAINT_EXPRESSION;
        }

        if (identifierStack.contains(DrlxParser.RULE_oopathRoot)) {
            return CompletionSite.ENTRY_POINT;
        }

        if (identifierStack.contains(DrlxParser.RULE_oopathChunk)) {
            return CompletionSite.OOPATH_CHUNK;
        }

        if (identifierStack.contains(DrlxParser.RULE_ruleItem)
                && identifierStack.contains(DrlxParser.RULE_boundOopath)) {
            return CompletionSite.RULE_ITEM;
        }

        if (identifierStack.contains(DrlxParser.RULE_boundOopath)) {
            return CompletionSite.BIND_NAME;
        }

        if (identifierStack.contains(DrlxParser.RULE_ruleParameter)
                && identifierStack.contains(DrlxParser.RULE_typeType)) {
            return CompletionSite.RULE_PARAMETER;
        }

        if (identifierStack.contains(DrlxParser.RULE_ruleParameter)) {
            return CompletionSite.BIND_NAME;
        }

        if (identifierStack.contains(DrlxParser.RULE_ruleDeclaration)
                && !identifierStack.contains(DrlxParser.RULE_ruleBody)
                && !identifierStack.contains(DrlxParser.RULE_altAnnotationQualifiedName)) {
            return CompletionSite.RULE_DECLARATION;
        }

        if (identifierStack.contains(DrlxParser.RULE_compilationUnit)
                || identifierStack.contains(DrlxParser.RULE_drlxCompilationUnit)) {
            return CompletionSite.COMPILATION_UNIT;
        }

        return CompletionSite.UNKNOWN;
    }

    private static boolean isDotAccess(DrlxParser parser, int caretTokenIndex) {
        if (caretTokenIndex < 1) {
            return false;
        }
        return parser.getTokenStream().get(caretTokenIndex - 1).getType() == DrlxLexer.DOT;
    }
}
```

- [ ] **Step 3: Build**

Run:
```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add drlx-completion/src/main/java/org/drools/drlx/completion/CompletionSite.java drlx-completion/src/main/java/org/drools/drlx/completion/CompletionContextAnalyzer.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: add CompletionSite enum and CompletionContextAnalyzer

Introduce DRLX-specific CompletionSite enum that classifies caret
positions by grammatical context. CompletionContextAnalyzer derives
the site from C3 candidates.rules call stacks and token context.

Related: #5"
```

---

### Task 2: Add CompletionSite assertions to characterization tests

**Files:**
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxC3CandidatesTest.java`

**Interfaces:**
- Consumes: `CompletionSite`, `CompletionContextAnalyzer.analyze()` from Task 1

- [ ] **Step 1: Update CandidateResult record to include tokenIndex**

Change the record at line 53 from:

```java
record CandidateResult(CodeCompletionCore.CandidatesCollection candidates, DrlxParser parser) {}
```

to:

```java
record CandidateResult(CodeCompletionCore.CandidatesCollection candidates, DrlxParser parser, int tokenIndex) {}
```

- [ ] **Step 2: Update collectAt to pass tokenIndex to the record**

In the `collectAt` method, change the return statement from:

```java
        return new CandidateResult(candidates, parser);
```

to:

```java
        return new CandidateResult(candidates, parser, tokenIndex);
```

- [ ] **Step 3: Add a helper method for CompletionSite assertion**

Add after the `ruleCallStack` method:

```java
    private static CompletionSite siteAt(CandidateResult r) {
        return CompletionContextAnalyzer.analyze(r.candidates(), r.parser(), r.tokenIndex());
    }
```

- [ ] **Step 4: Add CompletionSite assertions to each test**

Add one `assertThat(siteAt(r)).isEqualTo(...)` line to each test method. Add `import static` for `CompletionSite` values at the top of the file.

Add to imports:

```java
import static org.drools.drlx.completion.CompletionSite.*;
```

Add to each test (at the end of each method, before the closing `}`):

In `compilationUnitStart`:
```java
        assertThat(siteAt(r)).isEqualTo(COMPILATION_UNIT);
```

In `beforeRule`:
```java
        assertThat(siteAt(r)).isEqualTo(COMPILATION_UNIT);
```

In `afterRuleKeyword`:
```java
        assertThat(siteAt(r)).isEqualTo(RULE_DECLARATION);
```

In `boundType`:
```java
        assertThat(siteAt(r)).isEqualTo(RULE_ITEM);
```

In `bindName`:
```java
        assertThat(siteAt(r)).isEqualTo(RULE_ITEM);
```

In `afterSlash`:
```java
        assertThat(siteAt(r)).isEqualTo(ENTRY_POINT);
```

In `constraintExpression`:
```java
        assertThat(siteAt(r)).isEqualTo(CONSTRAINT_EXPRESSION);
```

In `oopathChunkIdentifier`:
```java
        assertThat(siteAt(r)).isEqualTo(OOPATH_CHUNK);
```

In `constraintChunkExpression`:
```java
        assertThat(siteAt(r)).isEqualTo(CONSTRAINT_EXPRESSION);
```

In `ruleItemStart_beforeNot`:
```java
        assertThat(siteAt(r)).isEqualTo(RULE_ITEM);
```

In `ruleItemStart_beforeExists`:
```java
        assertThat(siteAt(r)).isEqualTo(RULE_ITEM);
```

In `testElementExpression`:
```java
        assertThat(siteAt(r)).isEqualTo(TEST_EXPRESSION);
```

In `consequenceBlock`:
```java
        assertThat(siteAt(r)).isEqualTo(CONSEQUENCE_EXPRESSION);
```

In `afterDot`:
```java
        assertThat(siteAt(r)).isEqualTo(DOT_ACCESS);
```

In `ruleParameterType`:
```java
        assertThat(siteAt(r)).isEqualTo(RULE_PARAMETER);
```

- [ ] **Step 5: Run characterization tests**

Run:
```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest=DrlxC3CandidatesTest
```
Expected: All 15 tests pass

- [ ] **Step 6: Fix any mismatches**

If any assertions fail, check which site the analyzer actually produced and verify it against the C3 call stack data. Adjust the analyzer's derivation order if needed. The spec's priority order should match, but edge cases may surface.

- [ ] **Step 7: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add drlx-completion/src/test/java/org/drools/drlx/completion/DrlxC3CandidatesTest.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "test: add CompletionSite assertions to C3 characterization tests

Validate that CompletionContextAnalyzer produces the correct
CompletionSite at each of the 15 characterized caret positions.

Related: #5"
```

---

### Task 3: Wire CompletionContextAnalyzer into DrlxCompletionHelper

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java`

**Interfaces:**
- Consumes: `CompletionContextAnalyzer.analyze()`, `CompletionSite.needsSemanticCompletions()` from Task 1

- [ ] **Step 1: Replace isMajorIdentifierRule dispatch**

In `getCompletionItems`, change:

```java
        // 2. Additionally: semantic completions when identifier rule applies
        if (isMajorIdentifierRule(candidates)) {
            items.addAll(createSemanticCompletions(parser, parseTree, caretTokenIndex));
        }
```

to:

```java
        // 2. Additionally: semantic completions when identifier rule applies
        CompletionSite site = CompletionContextAnalyzer.analyze(candidates, parser, caretTokenIndex);
        if (site.needsSemanticCompletions()) {
            items.addAll(createSemanticCompletions(parser, parseTree, caretTokenIndex));
        }
```

- [ ] **Step 2: Guard createSemanticCompletions against caretTokenIndex < 1**

At the top of `createSemanticCompletions`, add a guard before `parser.getTokenStream().get(previousTokenIndex)`:

```java
        int previousTokenIndex = caretTokenIndex - 1;
        if (previousTokenIndex < 0) {
            semanticItems.add(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
            return semanticItems;
        }
```

This handles `COMPILATION_UNIT` at position 0 (e.g., empty input).

- [ ] **Step 3: Remove isMajorIdentifierRule and MINOR_IDENTIFIER_RULES**

Delete the `MINOR_IDENTIFIER_RULES` field and the `isMajorIdentifierRule` method.

- [ ] **Step 4: Build and run all tests**

Run:
```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test
```
Expected: All 51 tests pass

- [ ] **Step 5: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "refactor: replace isMajorIdentifierRule with CompletionSite dispatch

Wire CompletionContextAnalyzer into DrlxCompletionHelper. The
site.needsSemanticCompletions() check replaces isMajorIdentifierRule.
Remove isMajorIdentifierRule and MINOR_IDENTIFIER_RULES.

No altAnnotationQualifiedName filter — DRLX only uses annotations
before rules, so positions classify correctly without it.

Related: #5"
```

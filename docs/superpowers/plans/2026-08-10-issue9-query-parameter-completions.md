# Query Parameter Completions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the caret is inside `[...]` brackets of a query invocation (`/personsByAge[|`), offer the query's parameter names as completions.

**Architecture:** Add a new `QUERY_PARAMETER` CompletionSite. Detect it in `CompletionContextAnalyzer` by checking whether the oopathRoot identifier matches a rule with parameters. Resolve it in `CompletionContext` by extracting parameter names from the matching query's `ruleParameterList`.

**Tech Stack:** Java 17, ANTLR4 (DrlxParser), antlr4-c3, JUnit 5, AssertJ

## Global Constraints

- Source repo: `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`
- Build before test: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q`
- Run tests: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test`
- `CompletionContextAnalyzer.analyze()` has two callers: `DrlxCompletionHelper` and `DrlxC3CandidatesTest.siteAt()` — both must be updated when the signature changes.

---

### Task 1: Add `QUERY_PARAMETER` site, detection logic, resolution, and test

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/CompletionSite.java:14` — add enum value
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/CompletionContextAnalyzer.java` — add `parseTree` param, add `isQueryInvocation` helper, insert check before `CONSTRAINT_EXPRESSION`
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java:80` — pass `parseTree` to `analyze()`; add `QUERY_PARAMETER` case in `createSemanticCompletions`
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java` — add `resolveQueryParameterNames()`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxC3CandidatesTest.java:88-89` — update `siteAt()` to pass parse tree
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java` — add test

**Interfaces:**
- Consumes: existing `CompletionContextAnalyzer.analyze()`, `CompletionContext`, `DrlxCompletionHelper.createSemanticCompletions()`
- Produces: `CompletionSite.QUERY_PARAMETER`, `CompletionContextAnalyzer.analyze(candidates, parser, caretTokenIndex, parseTree)`, `CompletionContext.resolveQueryParameterNames() → List<String>`

- [ ] **Step 1: Write the failing test**

Add to `DrlxCompletionHelperIncompleteCodeTest.java`:

```java
@Test
void incompleteRule_queryParameterNames() {
    String text = """
            import org.drools.drlx.domain.Person;
            import org.drools.drlx.domain.MyUnit;

            unit MyUnit;

            rule personsByAge(int minAge, Person result) {
                Person p : /persons[age >= minAge],
                do { result = p; }
            }

            rule R1 {
                /personsByAge[
            }
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(11);
    caretPosition.setCharacter(18); // after '['

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    assertThat(completionItemStrings(result)).contains("minAge", "result");
}
```

Line count verification (0-based, after text block whitespace stripping):
- Line 0: `import org.drools.drlx.domain.Person;`
- Line 1: `import org.drools.drlx.domain.MyUnit;`
- Line 2: (blank)
- Line 3: `unit MyUnit;`
- Line 4: (blank)
- Line 5: `rule personsByAge(int minAge, Person result) {`
- Line 6: `    Person p : /persons[age >= minAge],`
- Line 7: `    do { result = p; }`
- Line 8: `}`
- Line 9: (blank)
- Line 10: `rule R1 {`
- Line 11: `    /personsByAge[`
- Line 12: `}`
- Line 13: (blank — trailing)

Wait — the caret is on line 11 (the `/personsByAge[` line), not line 13. But actually after stripping, the text block closing `"""` indentation determines the strip. Let me recalculate. The text block has 12-space indent (matching `            `). After stripping:

```
import org.drools.drlx.domain.Person;    ← line 0
import org.drools.drlx.domain.MyUnit;    ← line 1
                                          ← line 2
unit MyUnit;                              ← line 3
                                          ← line 4
rule personsByAge(int minAge, Person result) {  ← line 5
    Person p : /persons[age >= minAge],   ← line 6
    do { result = p; }                    ← line 7
}                                         ← line 8
                                          ← line 9
rule R1 {                                 ← line 10
    /personsByAge[                        ← line 11
}                                         ← line 12
```

Line 11: `    /personsByAge[` — character count: 4 spaces + `/personsByAge[` = `/` at 4, `personsByAge` is 5–16, `[` is at 17, so caret after `[` is at character 18.

So: `setLine(11)`, `setCharacter(18)`.

The test code above should use line 11, not 13. Correcting in the plan.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxCompletionHelperIncompleteCodeTest#incompleteRule_queryParameterNames"`
Expected: FAIL — completions won't include `minAge` or `result` (no `QUERY_PARAMETER` site exists yet).

- [ ] **Step 3: Add `QUERY_PARAMETER` to CompletionSite enum**

In `CompletionSite.java`, add `QUERY_PARAMETER` after `ACCUMULATE_FUNCTION`:

```java
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
    INLINE_CAST_TYPE,
    ACCUMULATE_FUNCTION,
    QUERY_PARAMETER,
    TEST_EXPRESSION,
    RULE_PARAMETER,
    UNKNOWN;

    public boolean needsSemanticCompletions() {
        return this != UNKNOWN;
    }
}
```

- [ ] **Step 4: Update `CompletionContextAnalyzer.analyze()` signature and detection**

Change `analyze()` to accept `ParseTree parseTree` as fourth parameter. Add `isQueryInvocation` helper. Insert the query check before the existing `CONSTRAINT_EXPRESSION` return:

```java
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxParser.DrlxCompilationUnitContext;
import org.drools.drlx.parser.DrlxParser.OopathRootContext;
import org.drools.drlx.parser.DrlxParser.RuleDeclarationContext;

public class CompletionContextAnalyzer {

    private CompletionContextAnalyzer() {
    }

    public static CompletionSite analyze(
            CodeCompletionCore.CandidatesCollection candidates,
            DrlxParser parser,
            int caretTokenIndex,
            ParseTree parseTree) {

        if (isDotAccess(parser, caretTokenIndex)) {
            return CompletionSite.DOT_ACCESS;
        }

        if (isHashAccess(parser, caretTokenIndex)) {
            return CompletionSite.INLINE_CAST_TYPE;
        }

        List<Integer> identifierStack = candidates.rules.get(DrlxParser.RULE_identifier);
        if (identifierStack == null) {
            return CompletionSite.UNKNOWN;
        }

        if (identifierStack.contains(DrlxParser.RULE_accumulateCall)) {
            return CompletionSite.ACCUMULATE_FUNCTION;
        }

        if (identifierStack.contains(DrlxParser.RULE_ruleConsequence)
                && identifierStack.contains(DrlxParser.RULE_block)) {
            return CompletionSite.CONSEQUENCE_EXPRESSION;
        }

        if (identifierStack.contains(DrlxParser.RULE_testElement)) {
            return CompletionSite.TEST_EXPRESSION;
        }

        if (identifierStack.contains(DrlxParser.RULE_drlxExpression)) {
            if (isQueryInvocation(parseTree, parser, caretTokenIndex)) {
                return CompletionSite.QUERY_PARAMETER;
            }
            return CompletionSite.CONSTRAINT_EXPRESSION;
        }

        // ... rest unchanged ...
    }

    private static boolean isQueryInvocation(ParseTree parseTree, DrlxParser parser, int caretTokenIndex) {
        String rootName = findEnclosingOopathRootName(parseTree, caretTokenIndex);
        if (rootName == null) return false;
        return findQueryByName(parseTree, rootName) != null;
    }

    private static String findEnclosingOopathRootName(ParseTree node, int caretTokenIndex) {
        if (node instanceof OopathRootContext root) {
            if (root.getStart() != null && root.identifier(0) != null) {
                int rootStart = root.getStart().getTokenIndex();
                int rootStop = root.getStop() != null ? root.getStop().getTokenIndex() : Integer.MAX_VALUE;
                if (rootStart <= caretTokenIndex && rootStop >= caretTokenIndex) {
                    return root.identifier(0).getText();
                }
            }
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String found = findEnclosingOopathRootName(node.getChild(i), caretTokenIndex);
            if (found != null) return found;
        }
        return null;
    }

    static RuleDeclarationContext findQueryByName(ParseTree node, String name) {
        if (node instanceof DrlxCompilationUnitContext cu) {
            for (RuleDeclarationContext rule : cu.ruleDeclaration()) {
                if (rule.ruleParameterList() != null
                        && rule.identifier() != null
                        && name.equals(rule.identifier().getText())) {
                    return rule;
                }
            }
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            RuleDeclarationContext found = findQueryByName(node.getChild(i), name);
            if (found != null) return found;
        }
        return null;
    }

    // isDotAccess and isHashAccess unchanged
}
```

- [ ] **Step 5: Update both callers of `analyze()`**

In `DrlxCompletionHelper.java` line 80, pass `parseTree`:

```java
CompletionSite site = CompletionContextAnalyzer.analyze(candidates, parser, caretTokenIndex, parseTree);
```

In `DrlxC3CandidatesTest.java`, update `siteAt()` and `collectAt()` to retain the parse tree:

```java
record CandidateResult(CodeCompletionCore.CandidatesCollection candidates, DrlxParser parser, int tokenIndex, ParseTree parseTree) {}

private static CandidateResult collectAt(String text, int line, int col) {
    // ... existing setup ...
    ParseTree parseTree = parser.drlxStart();
    // ... existing token index computation ...
    return new CandidateResult(candidates, parser, tokenIndex, parseTree);
}

private static CompletionSite siteAt(CandidateResult r) {
    return CompletionContextAnalyzer.analyze(r.candidates(), r.parser(), r.tokenIndex(), r.parseTree());
}
```

Add import in the test file:

```java
import org.antlr.v4.runtime.tree.ParseTree;
```

- [ ] **Step 6: Add `resolveQueryParameterNames()` to CompletionContext**

```java
public List<String> resolveQueryParameterNames() {
    DrlxCompilationUnitContext cu = findDrlxCompilationUnit();
    if (cu == null) return List.of();

    String rootName = findOopathRootNameAtCaret(cu);
    if (rootName == null) return List.of();

    for (RuleDeclarationContext rule : cu.ruleDeclaration()) {
        if (rule.ruleParameterList() != null
                && rule.identifier() != null
                && rootName.equals(rule.identifier().getText())) {
            return rule.ruleParameterList().ruleParameter().stream()
                    .map(p -> p.identifier().getText())
                    .toList();
        }
    }
    return List.of();
}

private String findOopathRootNameAtCaret(DrlxCompilationUnitContext cu) {
    RuleDeclarationContext enclosingRule = findEnclosingRule(cu);
    if (enclosingRule == null || enclosingRule.ruleBody() == null) return null;
    return findOopathRootNameInTree(enclosingRule.ruleBody());
}

private String findOopathRootNameInTree(ParseTree node) {
    if (node instanceof OopathRootContext root) {
        if (root.getStart() != null && root.identifier(0) != null) {
            int rootStart = root.getStart().getTokenIndex();
            int rootStop = root.getStop() != null ? root.getStop().getTokenIndex() : Integer.MAX_VALUE;
            if (rootStart <= caretTokenIndex && rootStop >= caretTokenIndex) {
                return root.identifier(0).getText();
            }
        }
        return null;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
        String found = findOopathRootNameInTree(node.getChild(i));
        if (found != null) return found;
    }
    return null;
}
```

- [ ] **Step 7: Add `QUERY_PARAMETER` case to `DrlxCompletionHelper.createSemanticCompletions()`**

```java
private List<CompletionItem> createSemanticCompletions(CompletionSite site, CompletionContext ctx) {
    return switch (site) {
        case DOT_ACCESS -> resolveDotAccess(ctx);
        case INLINE_CAST_TYPE -> resolveInlineCastTypeNames(ctx);
        case ACCUMULATE_FUNCTION -> resolveAccumulateFunctionNames();
        case ENTRY_POINT -> resolveEntryPointNames(ctx);
        case OOPATH_CHUNK -> resolveOopathChunkCompletions(ctx);
        case CONSTRAINT_EXPRESSION -> resolveConstraintExpressionCompletions(ctx);
        case QUERY_PARAMETER -> resolveQueryParameterCompletions(ctx);
        default -> List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
    };
}

private List<CompletionItem> resolveQueryParameterCompletions(CompletionContext ctx) {
    List<String> names = ctx.resolveQueryParameterNames();
    if (names.isEmpty()) {
        return List.of();
    }
    return names.stream()
            .map(name -> createCompletionItem(name, CompletionItemKind.Property))
            .toList();
}
```

- [ ] **Step 8: Build and run all tests**

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test
```

Expected: All tests pass including the new `incompleteRule_queryParameterNames`.

- [ ] **Step 9: Run only the new test to confirm**

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxCompletionHelperIncompleteCodeTest#incompleteRule_queryParameterNames"
```

Expected: PASS — completions include `minAge` and `result`.

- [ ] **Step 10: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add drlx-completion/src/main/java/org/drools/drlx/completion/CompletionSite.java \
    drlx-completion/src/main/java/org/drools/drlx/completion/CompletionContextAnalyzer.java \
    drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java \
    drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java \
    drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java \
    drlx-completion/src/test/java/org/drools/drlx/completion/DrlxC3CandidatesTest.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: add query parameter completions (Issue #9 item 9)"
```

# Issue #8: Improvement Targets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable 2 passing characterization tests, re-categorize 3 as #7, and add null-safe `!.` normalization to the sentinel resolver.

**Architecture:** Normalize DRLX-specific `!.` tokens to `.` before MVEL transpile (type-safe since null-safe doesn't change the resolved type). Add `NullSafeFieldAccessExpr` handling to `findSentinelScope` as safety net.

**Tech Stack:** Java 17, MVEL3, JavaParser (mvel fork), JUnit 5, AssertJ

## Global Constraints

- Tests run with `MvelParser.Factory.USE_ANTLR = true` and `MVELTranspiler.ENABLE_REWRITE = false`
- `NullSafeFieldAccessExpr` is NOT a subclass of `FieldAccessExpr` — requires separate `instanceof` check
- Import: `org.mvel3.parser.ast.expr.NullSafeFieldAccessExpr` (from javaparser-mvel fork)

---

### Task 1: Enable 2 passing tests and re-categorize 3

**Files:**
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java`

**Interfaces:**
- Consumes: nothing new
- Produces: 2 enabled tests, 3 re-labeled tests

- [ ] **Step 1: Remove @Disabled from caretInMiddleOfDocument**

In `ExpressionTypeResolverCharacterizationTest.java`, remove the `@Disabled` annotation from `caretInMiddleOfDocument` (line 176):

```java
    @Test
    // was: @Disabled("Improvement target — caret in middle of document — see #8")
    void caretInMiddleOfDocument() {
```

- [ ] **Step 2: Remove @Disabled from inlineCastQualifiedType**

Remove the `@Disabled` annotation from `inlineCastQualifiedType` (line 195):

```java
    @Test
    // was: @Disabled("Improvement target — inline cast with qualified type — see #8")
    void inlineCastQualifiedType() {
```

- [ ] **Step 3: Re-categorize methodReturnType from #8 to #7**

Change annotation reason (line 125):

```java
    @Disabled("Requires VisibleSymbols to declare 'list' as List — see #7")
```

- [ ] **Step 4: Re-categorize nullSafeAccess from #8 to #7**

Change annotation reason (line 143):

```java
    @Disabled("Requires VisibleSymbols to declare 'p' as Person + null-safe normalization — see #7")
```

- [ ] **Step 5: Re-categorize arrayIndexedAccess from #8 to #7**

Change annotation reason (line 161):

```java
    @Disabled("Requires VisibleSymbols to declare 'arr' as String[] — see #7")
```

- [ ] **Step 6: Run tests to verify the 2 enabled tests pass**

Run: `mvn -pl drlx-completion test -Dtest="ExpressionTypeResolverCharacterizationTest" -Dsurefire.useFile=false`

Expected: 6 pass (4 existing + 2 newly enabled), 11 skipped.

- [ ] **Step 7: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "test: enable caretInMiddleOfDocument and inlineCastQualifiedType, re-categorize 3 tests as #7"
```

---

### Task 2: Normalize EXCL_DOT in repaired expression

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/SentinelExpressionTypeResolver.java`

**Interfaces:**
- Consumes: `DrlxLexer.EXCL_DOT` token type constant
- Produces: repaired expression with `!.` normalized to `.`

- [ ] **Step 1: Add EXCL_DOT normalization in token loop**

In `SentinelExpressionTypeResolver.resolve()`, modify the token concatenation loop (currently lines 44-49) to replace EXCL_DOT with DOT:

```java
        StringBuilder sb = new StringBuilder();
        for (int i = boundaryIndex; i <= dotTokenIndex; i++) {
            int tokenType = tokens.get(i).getType();
            if (tokenType == DrlxLexer.EXCL_DOT) {
                sb.append(".");
            } else {
                sb.append(tokens.get(i).getText());
            }
        }
        sb.append(SENTINEL);
```

This requires importing `org.drools.drlx.parser.DrlxLexer` (already imported by `TokenWalker` in the same package — check if `SentinelExpressionTypeResolver` imports it; if not, add it).

- [ ] **Step 2: Run tests to verify no regression**

Run: `mvn -pl drlx-completion test -Dtest="ExpressionTypeResolverCharacterizationTest" -Dsurefire.useFile=false`

Expected: same 6 pass, 11 skipped (normalization doesn't affect existing passing tests since none use `!.`).

- [ ] **Step 3: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/SentinelExpressionTypeResolver.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: normalize EXCL_DOT to DOT in sentinel expression resolver"
```

---

### Task 3: Handle NullSafeFieldAccessExpr in findSentinelScope

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/SentinelExpressionTypeResolver.java`

**Interfaces:**
- Consumes: `org.mvel3.parser.ast.expr.NullSafeFieldAccessExpr` — has `getNameAsString()` and `getScope()` (same shape as `FieldAccessExpr`)
- Produces: `findSentinelScope` that finds sentinel in both regular and null-safe field access nodes

- [ ] **Step 1: Add NullSafeFieldAccessExpr import**

Add to imports:

```java
import org.mvel3.parser.ast.expr.NullSafeFieldAccessExpr;
```

- [ ] **Step 2: Extend findSentinelScope to handle NullSafeFieldAccessExpr**

Modify `findSentinelScope` (currently lines 81-88) to also check for `NullSafeFieldAccessExpr`:

```java
    private Expression findSentinelScope(CompilationUnit unit) {
        for (Node node : unit.findAll(Node.class)) {
            if (node instanceof FieldAccessExpr fae && SENTINEL.equals(fae.getNameAsString())) {
                return fae.getScope();
            }
            if (node instanceof NullSafeFieldAccessExpr nsfe && SENTINEL.equals(nsfe.getNameAsString())) {
                return nsfe.getScope();
            }
        }
        return null;
    }
```

- [ ] **Step 3: Run full test suite**

Run: `mvn -pl drlx-completion test -Dsurefire.useFile=false`

Expected: all non-disabled tests pass. The NullSafeFieldAccessExpr handling is a safety net — it won't be exercised until #7 provides variable types, but should not break existing tests.

- [ ] **Step 4: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/SentinelExpressionTypeResolver.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: handle NullSafeFieldAccessExpr in sentinel scope finder"
```

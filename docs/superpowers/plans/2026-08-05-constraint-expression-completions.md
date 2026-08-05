# Constraint Expression Property Completions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the caret is inside an OOPath constraint bracket (`[...]`), offer all properties of the enclosing type plus `this` as completions.

**Architecture:** Add `resolveConstraintCompletions()` to `CompletionContext` (mirrors existing `resolveOopathChunkCompletions()` pattern), and wire a `case CONSTRAINT_EXPRESSION` in `DrlxCompletionHelper.createSemanticCompletions()`. The new method walks the OOPath parse tree to find the enclosing type at the caret, then collects all properties (no navigability filter) plus `this`.

**Tech Stack:** Java 21, ANTLR4 (DrlxParser grammar), JavaParser (type resolution), JUnit 5, AssertJ

## Global Constraints

- Source repo: `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`
- Module under change: `drlx-completion`
- After modifying the module, run `mvn -pl drlx-completion -am install` before running tests
- Domain test classes: `org.drools.drlx.domain.{Person, Address, Country, MyUnit}` — already in test scope
- `Person` has fields: `name` (String), `age` (int), `address` (Address), `previousAddresses` (List\<Address\>)
- `Address` has fields: `city` (String), `country` (Country)
- Develop on `main` branch directly

---

### Task 1: Add `collectAllProperties()` and `resolveConstraintCompletions()` to CompletionContext

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java`
- Test: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java`

**Interfaces:**
- Consumes: Existing `resolveEntryPointType()`, `resolvePropertyType()`, `unwrapCollectionElementType()`, `findEnclosingRule()`, `findDrlxCompilationUnit()` — all on `CompletionContext`
- Produces: `public List<String> resolveConstraintCompletions()` — returns property names + `"this"`, or empty list

- [ ] **Step 1: Write the first failing test — root constraint**

Add to `DrlxCompletionHelperTest.java` after the existing `oopathChunkCompletion_unknownEntryPoint_returnsEmpty` test:

```java
@Test
void constraintCompletion_rootConstraint_offersAllProperties() {
    String text = """
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;

            rule R1 {
                var p : /persons[
            }
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(4);
    caretPosition.setCharacter(21); // after '['

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    List<String> labels = completionItemStrings(result);
    assertThat(labels).contains("name", "age", "address", "previousAddresses", "this");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl drlx-completion -am test -Dtest="DrlxCompletionHelperTest#constraintCompletion_rootConstraint_offersAllProperties" -Dsurefire.useFile=false`

Expected: FAIL — currently returns `IDENTIFIER` placeholder instead of property names.

- [ ] **Step 3: Add `collectAllProperties()` private method to CompletionContext**

Add after the existing `collectNavigableProperties()` method (after line 364). This is like `collectNavigableProperties()` but without the `isNavigableType()` filter:

```java
private List<String> collectAllProperties(SemanticType ownerType) {
    if (!ownerType.isReferenceType()) return List.of();
    List<String> result = new ArrayList<>();
    try {
        var refType = ownerType.resolvedType().asReferenceType();
        var typeDecl = refType.getTypeDeclaration().orElse(null);
        if (typeDecl == null) return List.of();

        Set<String> seen = new LinkedHashSet<>();
        for (var method : typeDecl.getDeclaredMethods()) {
            if (method.getNumberOfParams() != 0) continue;
            String methodName = method.getName();
            String propName = null;
            if (methodName.startsWith("get") && methodName.length() > 3) {
                propName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            } else if (methodName.startsWith("is") && methodName.length() > 2) {
                propName = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
            }
            if (propName != null) {
                seen.add(propName);
                result.add(propName);
            }
        }
        for (var field : typeDecl.getAllFields()) {
            if (seen.add(field.getName())) {
                result.add(field.getName());
            }
        }
    } catch (Exception e) {
        logger.debug("Cannot collect properties: {}", e.getMessage());
    }
    return result;
}
```

- [ ] **Step 4: Add `resolveConstraintCompletions()` public method to CompletionContext**

Add after `resolveOopathChunkCompletions()` (after line 277):

```java
public List<String> resolveConstraintCompletions() {
    DrlxCompilationUnitContext cu = findDrlxCompilationUnit();
    if (cu == null) return List.of();

    RuleDeclarationContext enclosingRule = findEnclosingRule(cu);
    if (enclosingRule == null || enclosingRule.ruleBody() == null) return List.of();

    return findConstraintCompletionsInTree(enclosingRule.ruleBody());
}

private List<String> findConstraintCompletionsInTree(ParseTree node) {
    if (node instanceof OopathExpressionContext oopathExpr) {
        List<String> result = resolveConstraintProperties(oopathExpr);
        if (result != null) return result;
        return List.of();
    }
    for (int i = 0; i < node.getChildCount(); i++) {
        List<String> result = findConstraintCompletionsInTree(node.getChild(i));
        if (!result.isEmpty()) return result;
    }
    return List.of();
}

private List<String> resolveConstraintProperties(OopathExpressionContext oopathExpr) {
    OopathRootContext root = oopathExpr.oopathRoot();
    if (root == null || root.identifier(0) == null) return null;

    String rootName = root.identifier(0).getText();
    SemanticType currentType = resolveEntryPointType(rootName);
    if (currentType == null) return null;

    // Check if caret is inside root's [...] bracket
    int rootStart = root.getStart().getTokenIndex();
    int rootStop = root.getStop() != null ? root.getStop().getTokenIndex() : Integer.MAX_VALUE;
    List<OopathChunkContext> chunks = oopathExpr.oopathChunk();
    boolean caretInRoot = rootStart <= caretTokenIndex && rootStop >= caretTokenIndex
            && (chunks.isEmpty() || chunks.get(0).getStart().getTokenIndex() > caretTokenIndex);

    if (caretInRoot) {
        List<String> props = collectAllProperties(currentType);
        props.add("this");
        return props;
    }

    // Walk chunks to find which chunk's [...] contains the caret
    for (OopathChunkContext chunk : chunks) {
        String chunkName = chunk.identifier(0).getText();
        SemanticType chunkType = resolvePropertyType(currentType, chunkName);
        if (chunkType == null) return null;
        SemanticType unwrapped = unwrapCollectionElementType(chunkType);
        if (unwrapped != null) {
            chunkType = unwrapped;
        }

        int chunkStart = chunk.getStart().getTokenIndex();
        int chunkStop = chunk.getStop() != null ? chunk.getStop().getTokenIndex() : Integer.MAX_VALUE;
        if (chunkStart <= caretTokenIndex && chunkStop >= caretTokenIndex) {
            List<String> props = collectAllProperties(chunkType);
            props.add("this");
            return props;
        }
        currentType = chunkType;
    }
    return null;
}
```

- [ ] **Step 5: Wire `case CONSTRAINT_EXPRESSION` in DrlxCompletionHelper**

In `DrlxCompletionHelper.java`, modify the `createSemanticCompletions` switch (line 93-100) to add the new case:

```java
private List<CompletionItem> createSemanticCompletions(CompletionSite site, CompletionContext ctx) {
    return switch (site) {
        case DOT_ACCESS -> resolveDotAccess(ctx);
        case ENTRY_POINT -> resolveEntryPointNames(ctx);
        case OOPATH_CHUNK -> resolveOopathChunkCompletions(ctx);
        case CONSTRAINT_EXPRESSION -> resolveConstraintExpressionCompletions(ctx);
        default -> List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
    };
}
```

Add the new private method after `resolveOopathChunkCompletions()` (after line 137):

```java
private List<CompletionItem> resolveConstraintExpressionCompletions(CompletionContext ctx) {
    List<String> names = ctx.resolveConstraintCompletions();
    if (names.isEmpty()) {
        return List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
    }
    return names.stream()
            .map(name -> "this".equals(name)
                    ? createCompletionItem(name, CompletionItemKind.Keyword)
                    : createCompletionItem(name, CompletionItemKind.Property))
            .toList();
}
```

- [ ] **Step 6: Run the first test to verify it passes**

Run: `mvn -pl drlx-completion -am test -Dtest="DrlxCompletionHelperTest#constraintCompletion_rootConstraint_offersAllProperties" -Dsurefire.useFile=false`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add \
  drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java \
  drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java \
  drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: constraint expression completions for OOPath root brackets (Issue #9 item 3)"
```

---

### Task 2: Add remaining constraint completion tests

**Files:**
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java`

**Interfaces:**
- Consumes: `resolveConstraintCompletions()` from Task 1, `resolveConstraintExpressionCompletions()` from Task 1
- Produces: Test coverage for chunk constraints, collection unwrapping, and edge cases

- [ ] **Step 1: Write chunk constraint test**

Add to `DrlxCompletionHelperTest.java`:

```java
@Test
void constraintCompletion_chunkConstraint_offersChunkTypeProperties() {
    String text = """
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;

            rule R1 {
                var a : /persons/address[
            }
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(4);
    caretPosition.setCharacter(29); // after '/persons/address['

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    List<String> labels = completionItemStrings(result);
    assertThat(labels).contains("city", "country", "this");
    assertThat(labels).doesNotContain("name", "age");
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn -pl drlx-completion -am test -Dtest="DrlxCompletionHelperTest#constraintCompletion_chunkConstraint_offersChunkTypeProperties" -Dsurefire.useFile=false`

Expected: PASS

- [ ] **Step 3: Write collection unwrap test**

```java
@Test
void constraintCompletion_collectionChunk_unwrapsElementType() {
    String text = """
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;

            rule R1 {
                var a : /persons/previousAddresses[
            }
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(4);
    caretPosition.setCharacter(39); // after '/persons/previousAddresses['

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    List<String> labels = completionItemStrings(result);
    assertThat(labels).contains("city", "country", "this");
    assertThat(labels).doesNotContain("name", "age");
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl drlx-completion -am test -Dtest="DrlxCompletionHelperTest#constraintCompletion_collectionChunk_unwrapsElementType" -Dsurefire.useFile=false`

Expected: PASS

- [ ] **Step 5: Write no-unit-declaration edge case test**

```java
@Test
void constraintCompletion_noUnitDeclaration_returnsEmpty() {
    String text = """
            rule R1 {
                var p : /persons[
            }
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(1);
    caretPosition.setCharacter(21); // after '['

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    List<String> labels = completionItemStrings(result);
    assertThat(labels).doesNotContain("name", "age", "address", "this");
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -pl drlx-completion -am test -Dtest="DrlxCompletionHelperTest#constraintCompletion_noUnitDeclaration_returnsEmpty" -Dsurefire.useFile=false`

Expected: PASS

- [ ] **Step 7: Write unknown-entry-point edge case test**

```java
@Test
void constraintCompletion_unknownEntryPoint_returnsEmpty() {
    String text = """
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;

            rule R1 {
                var p : /unknown[
            }
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(4);
    caretPosition.setCharacter(21); // after '/unknown['

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    List<String> labels = completionItemStrings(result);
    assertThat(labels).doesNotContain("name", "age", "address", "this");
}
```

- [ ] **Step 8: Run all constraint completion tests together**

Run: `mvn -pl drlx-completion -am test -Dtest="DrlxCompletionHelperTest#constraintCompletion*" -Dsurefire.useFile=false`

Expected: All 5 tests PASS

- [ ] **Step 9: Run full test suite to check for regressions**

Run: `mvn -pl drlx-completion -am test -Dsurefire.useFile=false`

Expected: All 113+ tests PASS (no regressions)

- [ ] **Step 10: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add \
  drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "test: constraint completion tests for chunk, collection, and edge cases (Issue #9 item 3)"
```

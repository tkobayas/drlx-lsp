# OOPath Navigation Chunk Completions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When typing `/persons/|` in a DRL rule, offer navigable (entity/collection) properties of the resolved type as completions.

**Architecture:** Add `resolveOopathChunkCompletions()` to `CompletionContext` that walks the OOPath parse tree to resolve the type at the caret, then enumerates navigable properties. Wire `case OOPATH_CHUNK` in `DrlxCompletionHelper.createSemanticCompletions()`.

**Tech Stack:** Java 17, ANTLR4 (drlx-parser), JavaParser symbol-solver, JUnit 5, AssertJ

## Global Constraints

- Source repo: `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`
- Module: `drlx-completion`
- Build: `mvn -pl drlx-completion -am install` from the repo root before running tests
- Tests: `mvn -pl drlx-completion test` or specific test with `-Dtest=DrlxCompletionHelperTest`
- All existing 121 tests must continue to pass
- Commit directly to `main` (no feature branches)

---

### Task 1: Test Domain Extension

**Files:**
- Modify: `drlx-completion/src/test/java/org/drools/drlx/domain/Address.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/domain/Person.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/domain/Country.java`

**Interfaces:**
- Consumes: nothing
- Produces: `Country` class with `String name` + getter/setter; `Address.getCountry()` returning `Country`; `Person.getPreviousAddresses()` returning `List<Address>`

- [ ] **Step 1: Create `Country.java`**

```java
package org.drools.drlx.domain;

public class Country {
    private String name;

    public Country() {
    }

    public Country(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
```

- [ ] **Step 2: Add `country` field to `Address.java`**

Add after the `city` field:

```java
private Country country;
```

Add after `setCity`:

```java
public Country getCountry() {
    return country;
}

public void setCountry(Country country) {
    this.country = country;
}
```

- [ ] **Step 3: Add `previousAddresses` field to `Person.java`**

Add import at top:

```java
import java.util.List;
```

Add after the `address` field:

```java
private List<Address> previousAddresses;
```

Add after `setAddress`:

```java
public List<Address> getPreviousAddresses() {
    return previousAddresses;
}

public void setPreviousAddresses(List<Address> previousAddresses) {
    this.previousAddresses = previousAddresses;
}
```

- [ ] **Step 4: Verify existing tests still pass**

Run from `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`:

```bash
mvn -pl drlx-completion test
```

Expected: all 121 tests pass (domain changes are additive).

- [ ] **Step 5: Commit**

```bash
git add drlx-completion/src/test/java/org/drools/drlx/domain/Country.java \
       drlx-completion/src/test/java/org/drools/drlx/domain/Address.java \
       drlx-completion/src/test/java/org/drools/drlx/domain/Person.java
git commit -m "test: extend domain model for OOPath chunk completion tests"
```

---

### Task 2: Failing Tests for OOPath Chunk Completions

**Files:**
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java`

**Interfaces:**
- Consumes: `Country`, `Address.country`, `Person.previousAddresses` from Task 1
- Produces: 5 failing test methods that will pass after Task 3

- [ ] **Step 1: Write the 5 test methods**

Add to `DrlxCompletionHelperTest.java` after the `entryPointCompletion_noUnitDeclaration_returnsEmpty` test:

```java
@Test
void oopathChunkCompletion_singleSegment_offersNavigableProperties() {
    String text = """
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;

            rule R1 {
                var a : /persons/
            }
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(4);
    caretPosition.setCharacter(21); // after '/persons/'

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    List<String> labels = completionItemStrings(result);
    assertThat(labels).contains("address", "previousAddresses");
    assertThat(labels).doesNotContain("name", "age");
}

@Test
void oopathChunkCompletion_multiSegment_offersNextLevelProperties() {
    String text = """
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;

            rule R1 {
                var c : /persons/address/
            }
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(4);
    caretPosition.setCharacter(29); // after '/persons/address/'

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    List<String> labels = completionItemStrings(result);
    assertThat(labels).contains("country");
    assertThat(labels).doesNotContain("city");
}

@Test
void oopathChunkCompletion_collectionNavigation_unwrapsElementType() {
    String text = """
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;

            rule R1 {
                var c : /persons/previousAddresses/
            }
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(4);
    caretPosition.setCharacter(39); // after '/persons/previousAddresses/'

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    List<String> labels = completionItemStrings(result);
    assertThat(labels).contains("country");
    assertThat(labels).doesNotContain("city");
}

@Test
void oopathChunkCompletion_noUnitDeclaration_returnsEmpty() {
    String text = """
            rule R1 {
                var a : /persons/
            }
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(1);
    caretPosition.setCharacter(21);

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    List<String> labels = completionItemStrings(result);
    assertThat(labels).doesNotContain("address", "previousAddresses");
}

@Test
void oopathChunkCompletion_unknownEntryPoint_returnsEmpty() {
    String text = """
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;

            rule R1 {
                var a : /unknown/
            }
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(4);
    caretPosition.setCharacter(21); // after '/unknown/'

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    List<String> labels = completionItemStrings(result);
    assertThat(labels).doesNotContain("address", "previousAddresses", "country");
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn -pl drlx-completion test -Dtest=DrlxCompletionHelperTest
```

Expected: the 5 new tests fail (the `contains("address", ...)` assertions fail because `OOPATH_CHUNK` returns `IDENTIFIER` placeholder). Existing tests still pass.

- [ ] **Step 3: Commit**

```bash
git add drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java
git commit -m "test: add failing tests for OOPath chunk completions (Issue #9 item 2)"
```

---

### Task 3: Implement OOPath Chunk Completions

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java`
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java`

**Interfaces:**
- Consumes: existing `resolveEntryPointType(String)`, `resolvePropertyType(SemanticType, String)` from `CompletionContext`; `ResolvedReferenceType.getQualifiedName()`, `ResolvedReferenceType.typeParametersValues()` from JavaParser
- Produces: `CompletionContext.resolveOopathChunkCompletions()` returning `List<String>`; `DrlxCompletionHelper` handling `case OOPATH_CHUNK`

- [ ] **Step 1: Add `resolveOopathChunkCompletions()` to `CompletionContext.java`**

Add this method after `resolveEntryPointNames()` (after line 267):

```java
public List<String> resolveOopathChunkCompletions() {
    DrlxCompilationUnitContext cu = findDrlxCompilationUnit();
    if (cu == null) return List.of();

    RuleDeclarationContext enclosingRule = findEnclosingRule(cu);
    if (enclosingRule == null || enclosingRule.ruleBody() == null) return List.of();

    return findOopathChunkCompletionsInTree(enclosingRule.ruleBody());
}

private List<String> findOopathChunkCompletionsInTree(ParseTree node) {
    if (node instanceof OopathExpressionContext oopathExpr) {
        List<String> result = resolveOopathChunkProperties(oopathExpr);
        if (result != null) return result;
        return List.of();
    }
    for (int i = 0; i < node.getChildCount(); i++) {
        List<String> result = findOopathChunkCompletionsInTree(node.getChild(i));
        if (!result.isEmpty()) return result;
    }
    return List.of();
}

private List<String> resolveOopathChunkProperties(OopathExpressionContext oopathExpr) {
    OopathRootContext root = oopathExpr.oopathRoot();
    if (root == null || root.identifier(0) == null) return null;

    String rootName = root.identifier(0).getText();
    SemanticType currentType = resolveEntryPointType(rootName);
    if (currentType == null) return null;

    List<OopathChunkContext> chunks = oopathExpr.oopathChunk();
    for (OopathChunkContext chunk : chunks) {
        int chunkStart = chunk.getStart().getTokenIndex();
        if (chunkStart >= caretTokenIndex) break;

        String chunkName = chunk.identifier(0).getText();
        SemanticType chunkType = resolvePropertyType(currentType, chunkName);
        if (chunkType == null) return null;
        currentType = unwrapCollectionElementType(chunkType);
        if (currentType == null) currentType = chunkType;
    }

    return collectNavigableProperties(currentType);
}

private List<String> collectNavigableProperties(SemanticType ownerType) {
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
            if (propName != null && seen.add(propName)) {
                try {
                    SemanticType propType = SemanticType.value(method.getReturnType());
                    if (isNavigableType(propType)) {
                        result.add(propName);
                    }
                } catch (Exception e) {
                    logger.debug("Cannot resolve method return type '{}': {}", methodName, e.getMessage());
                }
            }
        }
        for (var field : typeDecl.getAllFields()) {
            if (seen.add(field.getName())) {
                try {
                    SemanticType fieldType = SemanticType.value(field.getType());
                    if (isNavigableType(fieldType)) {
                        result.add(field.getName());
                    }
                } catch (Exception e) {
                    logger.debug("Cannot resolve field type '{}': {}", field.getName(), e.getMessage());
                }
            }
        }
    } catch (Exception e) {
        logger.debug("Cannot collect navigable properties: {}", e.getMessage());
    }
    return result;
}

private boolean isNavigableType(SemanticType type) {
    if (!type.isReferenceType()) return false;
    var refType = type.resolvedType().asReferenceType();
    String qname = refType.getQualifiedName();
    if (qname.startsWith("java.lang.")) return false;
    if (isCollectionType(qname)) {
        var typeArgs = refType.typeParametersValues();
        if (!typeArgs.isEmpty() && typeArgs.get(0).isReferenceType()) {
            String elementQname = typeArgs.get(0).asReferenceType().getQualifiedName();
            return !elementQname.startsWith("java.lang.");
        }
        return false;
    }
    return true;
}

private SemanticType unwrapCollectionElementType(SemanticType type) {
    if (!type.isReferenceType()) return null;
    var refType = type.resolvedType().asReferenceType();
    if (!isCollectionType(refType.getQualifiedName())) return null;
    var typeArgs = refType.typeParametersValues();
    if (typeArgs.isEmpty()) return null;
    var elementType = typeArgs.get(0);
    if (elementType.isReferenceType()) {
        return SemanticType.value(elementType);
    }
    return null;
}

private static boolean isCollectionType(String qname) {
    return qname.equals("java.util.List")
            || qname.equals("java.util.Set")
            || qname.equals("java.util.Collection")
            || qname.equals("java.lang.Iterable");
}
```

- [ ] **Step 2: Wire `case OOPATH_CHUNK` in `DrlxCompletionHelper.java`**

Change the `createSemanticCompletions` method (line 93-99) from:

```java
private List<CompletionItem> createSemanticCompletions(CompletionSite site, CompletionContext ctx) {
    return switch (site) {
        case DOT_ACCESS -> resolveDotAccess(ctx);
        case ENTRY_POINT -> resolveEntryPointNames(ctx);
        default -> List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
    };
}
```

to:

```java
private List<CompletionItem> createSemanticCompletions(CompletionSite site, CompletionContext ctx) {
    return switch (site) {
        case DOT_ACCESS -> resolveDotAccess(ctx);
        case ENTRY_POINT -> resolveEntryPointNames(ctx);
        case OOPATH_CHUNK -> resolveOopathChunkCompletions(ctx);
        default -> List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
    };
}
```

Add the new private method after `resolveEntryPointNames` (after line 126):

```java
private List<CompletionItem> resolveOopathChunkCompletions(CompletionContext ctx) {
    List<String> names = ctx.resolveOopathChunkCompletions();
    if (names.isEmpty()) {
        return List.of();
    }
    return names.stream()
            .map(name -> createCompletionItem(name, CompletionItemKind.Property))
            .toList();
}
```

- [ ] **Step 3: Run all tests**

```bash
mvn -pl drlx-completion test
```

Expected: all tests pass, including the 5 new ones from Task 2.

- [ ] **Step 4: Verify caret positions are correct**

If any of the 5 new tests fail, the most likely cause is wrong caret column positions. Debug by adding a temporary print of `completionItemStrings(result)` and adjusting the `setCharacter()` value. The caret should be positioned right after the trailing `/`.

- [ ] **Step 5: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java \
       drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java
git commit -m "feat: OOPath navigation chunk completions after / (Issue #9 item 2)"
```

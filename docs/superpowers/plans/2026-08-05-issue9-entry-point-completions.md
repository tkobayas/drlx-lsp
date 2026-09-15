# Entry-Point Name Completions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When typing `var p : /|` in a rule with a `unit` declaration, offer the unit class's DataStore/DataSource field names as completions.

**Architecture:** Add `resolveEntryPointNames()` to `CompletionContext` (reflection-based enumeration of unit class fields). Wire it into `DrlxCompletionHelper.createSemanticCompletions` via a new `case ENTRY_POINT` branch.

**Tech Stack:** Java 17, ANTLR4, LSP4J, JUnit 5, AssertJ

## Global Constraints

- Source code is at `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`
- Module: `drlx-completion`
- Test command: `mvn -pl drlx-completion test` (run from `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`)
- Tests use `CurrentClassloaderProvider` which means test domain classes (`MyUnit`, `Person`, `Address`) are on the test classpath automatically
- Existing test at `DrlxCompletionHelperTest.java:57-60` asserts `IDENTIFIER` for the `var a : /` position — this must be updated

---

### Task 1: Extend MyUnit, add resolveEntryPointNames(), wire dispatch, test

**Files:**
- Modify: `drlx-completion/src/test/java/org/drools/drlx/domain/MyUnit.java`
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java`
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java`

- [ ] **Step 1: Add `addresses` field to MyUnit**

In `drlx-completion/src/test/java/org/drools/drlx/domain/MyUnit.java`, add a second DataStore field:

```java
package org.drools.drlx.domain;

import org.drools.ruleunits.api.DataSource;
import org.drools.ruleunits.api.DataStore;

public class MyUnit {

    public DataStore<Person> persons = DataSource.createStore();
    public DataStore<Address> addresses = DataSource.createStore();
}
```

- [ ] **Step 2: Write failing tests in DrlxCompletionHelperTest**

Add two test methods to `DrlxCompletionHelperTest.java`:

```java
@Test
void entryPointCompletion_offersUnitFieldNames() {
    String text = """
            unit MyUnit;
            import org.drools.drlx.domain.MyUnit;

            rule R1 {
                var p : /
            }
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(4);
    caretPosition.setCharacter(12); // after '/'

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    List<String> labels = completionItemStrings(result);
    assertThat(labels).contains("persons", "addresses");
    assertThat(labels).doesNotContain("IDENTIFIER");
}

@Test
void entryPointCompletion_noUnitDeclaration_returnsEmpty() {
    String text = """
            rule R1 {
                var p : /
            }
            """;

    Position caretPosition = new Position();
    caretPosition.setLine(1);
    caretPosition.setCharacter(12);

    List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
    List<String> labels = completionItemStrings(result);
    assertThat(labels).doesNotContain("persons", "addresses");
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -pl drlx-completion test -Dtest=DrlxCompletionHelperTest#entryPointCompletion_offersUnitFieldNames+entryPointCompletion_noUnitDeclaration_returnsEmpty`

Expected: FAIL — both tests should fail because `createSemanticCompletions` returns `IDENTIFIER` for `ENTRY_POINT`.

- [ ] **Step 4: Add `resolveEntryPointNames()` to CompletionContext**

Add this method to `CompletionContext.java`, after the existing `resolveEntryPointType()` method (after line 242):

```java
List<String> resolveEntryPointNames() {
    String unitClass = unitClassName();
    if (unitClass == null) return List.of();
    String unitFqcn = resolveToFqcn(unitClass);
    if (unitFqcn == null) unitFqcn = unitClass;
    try {
        Class<?> clazz = Class.forName(unitFqcn, false, model.projectClassLoader());
        List<String> names = new ArrayList<>();
        for (Field field : clazz.getDeclaredFields()) {
            java.lang.reflect.Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType) {
                names.add(field.getName());
            }
        }
        return names;
    } catch (ClassNotFoundException e) {
        logger.debug("Cannot load unit class '{}': {}", unitFqcn, e.getMessage());
        diagnostics.add("Unit class '" + unitFqcn + "' not found on classpath. Entry-point completions are not available.");
    } catch (NoClassDefFoundError e) {
        logger.debug("Cannot load unit class '{}' — missing dependency: {}", unitFqcn, e.getMessage());
        diagnostics.add("Unit class '" + unitFqcn + "' found but a dependency is missing: " + e.getMessage());
    }
    return List.of();
}
```

- [ ] **Step 5: Wire `ENTRY_POINT` case in DrlxCompletionHelper**

In `DrlxCompletionHelper.java`, change the `createSemanticCompletions` switch (line 93-98) to:

```java
private List<CompletionItem> createSemanticCompletions(CompletionSite site, CompletionContext ctx) {
    return switch (site) {
        case DOT_ACCESS -> resolveDotAccess(ctx);
        case ENTRY_POINT -> resolveEntryPointNames(ctx);
        default -> List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
    };
}
```

Add the new private method after `resolveDotAccess()`:

```java
private List<CompletionItem> resolveEntryPointNames(CompletionContext ctx) {
    List<String> names = ctx.resolveEntryPointNames();
    if (names.isEmpty()) {
        return List.of();
    }
    return names.stream()
            .map(name -> createCompletionItem(name, CompletionItemKind.Field))
            .toList();
}
```

- [ ] **Step 6: Update existing test assertion**

In `DrlxCompletionHelperTest.java`, update the existing assertion at line 57-60. The test text uses `unit MyUnit;` without an import for `MyUnit`, so the unit class won't be found — entry-point completions will be empty. The `IDENTIFIER` placeholder is also no longer returned for `ENTRY_POINT`. Update:

```java
// Test completion in the middle of pattern - position after 'var a : /'
caretPosition.setLine(3);
caretPosition.setCharacter(14);
result = helper.getCompletionItems(text, caretPosition);
assertThat(completionItemStrings(result)).doesNotContain("IDENTIFIER");
```

Note: this existing test has `unit MyUnit;` but no import. The `resolveToFqcn("MyUnit")` will try the type solver which has `MyUnit` on the test classpath via `CurrentClassloaderProvider`, so it may actually resolve. Check the actual behavior — if it resolves, assert `contains("persons")` instead. If not, assert empty entry-point names.

- [ ] **Step 7: Run all tests**

Run: `mvn -pl drlx-completion test`

Expected: All tests pass (including the two new ones and the updated existing one).

- [ ] **Step 8: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java \
       drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java \
       drlx-completion/src/test/java/org/drools/drlx/domain/MyUnit.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java
git commit -m "feat: entry-point name completions after / (Issue #9 item 1)"
```

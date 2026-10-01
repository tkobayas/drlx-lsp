# AccumulateFunctionTypes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract duplicated accumulate function type inference into a single `AccumulateFunctionTypes` utility class, eliminating three maintenance points in favor of one.

**Architecture:** A final utility class with an unmodifiable `Map<String, String>` mapping 7 built-in accumulate function names to their return type names. Two methods expose the map: `resultType(name)` for type lookup and `functionNames()` for completion suggestions. Three existing call sites are rewired; two `inferAccumulateResultType` methods and one `ACCUMULATE_FUNCTIONS` list are deleted.

**Tech Stack:** Java 21, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-10-01-issue11-accumulatefunctiontypes-spec.md`

## Global Constraints

- Java 21 language features (records, switch expressions, `Map.of`)
- No new dependencies
- Package: `org.drools.drlx.completion.semantic`
- Build: `mvn -pl drlx-completion -am install -DskipTests -q` before running tests

## Review Focus

1. **Null function name** — `accumulateCall().qualifiedName()` can be null for incomplete parses; `resultType(null)` must return empty, not NPE.
2. **Unknown function name** — a user typing a custom accumulate function (e.g. `myFunc`) must get `Optional.empty()`, not a crash or `Object`.
3. **`Comparable` resolution** — `resolveTypeToSemanticType("Comparable")` might fail if JavaParser can't resolve the bare name; if so, the completion test for `min`/`max` bindings will break silently (no completions instead of crash). The existing tests cover this path.
4. **Completion item order** — the existing test (`incompleteRule_accumulateFunctionName`) uses `contains()` not `containsExactly()`, so `Set` iteration order from `functionNames()` is acceptable.
5. **Inlay hint type label change** — `testAccumulateVarHint` asserts `": Long"` for `count`, which is unchanged. But if a future test asserts `": Number"` for `sum`, it would need updating to `": Double"`.

---

### Task 1: Create AccumulateFunctionTypes class with unit tests

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/AccumulateFunctionTypes.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/AccumulateFunctionTypesTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `AccumulateFunctionTypes.resultType(String) → Optional<String>`, `AccumulateFunctionTypes.functionNames() → Set<String>`

- [ ] **Step 1: Write the failing tests**

Create `drlx-completion/src/test/java/org/drools/drlx/completion/AccumulateFunctionTypesTest.java`:

```java
package org.drools.drlx.completion;

import java.util.Optional;
import java.util.Set;

import org.drools.drlx.completion.semantic.AccumulateFunctionTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccumulateFunctionTypesTest {

    @Test
    void resultType_sum() {
        assertThat(AccumulateFunctionTypes.resultType("sum")).isEqualTo(Optional.of("Double"));
    }

    @Test
    void resultType_avg() {
        assertThat(AccumulateFunctionTypes.resultType("avg")).isEqualTo(Optional.of("Double"));
    }

    @Test
    void resultType_min() {
        assertThat(AccumulateFunctionTypes.resultType("min")).isEqualTo(Optional.of("Comparable"));
    }

    @Test
    void resultType_max() {
        assertThat(AccumulateFunctionTypes.resultType("max")).isEqualTo(Optional.of("Comparable"));
    }

    @Test
    void resultType_count() {
        assertThat(AccumulateFunctionTypes.resultType("count")).isEqualTo(Optional.of("Long"));
    }

    @Test
    void resultType_collectList() {
        assertThat(AccumulateFunctionTypes.resultType("collectList")).isEqualTo(Optional.of("java.util.List"));
    }

    @Test
    void resultType_collectSet() {
        assertThat(AccumulateFunctionTypes.resultType("collectSet")).isEqualTo(Optional.of("java.util.Set"));
    }

    @Test
    void resultType_unknown() {
        assertThat(AccumulateFunctionTypes.resultType("unknown")).isEmpty();
    }

    @Test
    void resultType_null() {
        assertThat(AccumulateFunctionTypes.resultType(null)).isEmpty();
    }

    @Test
    void functionNames_returnsAll7() {
        Set<String> names = AccumulateFunctionTypes.functionNames();
        assertThat(names).containsExactlyInAnyOrder(
                "sum", "avg", "min", "max", "count", "collectList", "collectSet");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl drlx-completion test -Dtest="AccumulateFunctionTypesTest" -q`

Expected: compilation error — `AccumulateFunctionTypes` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/AccumulateFunctionTypes.java`:

```java
package org.drools.drlx.completion.semantic;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class AccumulateFunctionTypes {

    private static final Map<String, String> FUNCTION_TYPES = Map.of(
            "sum",         "Double",
            "avg",         "Double",
            "min",         "Comparable",
            "max",         "Comparable",
            "count",       "Long",
            "collectList", "java.util.List",
            "collectSet",  "java.util.Set"
    );

    private AccumulateFunctionTypes() {}

    public static Optional<String> resultType(String functionName) {
        if (functionName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(FUNCTION_TYPES.get(functionName));
    }

    public static Set<String> functionNames() {
        return FUNCTION_TYPES.keySet();
    }
}
```

- [ ] **Step 4: Build and run tests**

Run: `mvn -pl drlx-completion -am install -DskipTests -q`
Run: `mvn -pl drlx-completion test -Dtest="AccumulateFunctionTypesTest" -q`

Expected: all 10 tests pass.

- [ ] **Step 5: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/AccumulateFunctionTypes.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/AccumulateFunctionTypesTest.java
git commit -m "feat: add AccumulateFunctionTypes for accumulate result type lookup (#11)"
```

---

### Task 2: Wire AccumulateFunctionTypes into CompletionContext, DrlxInlayHintHelper, and DrlxCompletionHelper

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java` — lines 225-226 (call site), lines 245-257 (delete method)
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxInlayHintHelper.java` — lines 123 (call site), lines 130-142 (delete method)
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java` — lines 101, 139-146 (delete list, update method)

**Interfaces:**
- Consumes: `AccumulateFunctionTypes.resultType(String) → Optional<String>`, `AccumulateFunctionTypes.functionNames() → Set<String>` (from Task 1)
- Produces: no new interfaces (existing call sites unchanged)

- [ ] **Step 1: Modify CompletionContext.java**

In `collectBoundOopathFromTree`, replace lines 225-227:

```java
// Before (lines 225-227):
} else if (accItem.VAR() != null) {
    SemanticType inferred = inferAccumulateResultType(accItem);
    if (inferred != null) builder.add(bindName, inferred, accRange);
}
```

```java
// After:
} else if (accItem.VAR() != null) {
    var accCall = accItem.accumulateCall();
    if (accCall != null && accCall.qualifiedName() != null) {
        String funcName = accCall.qualifiedName().getText();
        SemanticType inferred = AccumulateFunctionTypes.resultType(funcName)
                .map(this::resolveTypeToSemanticType)
                .orElse(null);
        if (inferred != null) builder.add(bindName, inferred, accRange);
    }
}
```

Delete the `inferAccumulateResultType` method (lines 245-257).

Add import: `import java.util.Optional;` (if not already present — check first).

- [ ] **Step 2: Modify DrlxInlayHintHelper.java**

In `processAccumulateItem`, replace lines 122-125:

```java
// Before (lines 122-125):
Token bindToken = accItem.identifier().getStart();
SemanticType inferred = inferAccumulateResultType(accItem, ctx);
if (inferred != null) {
    addTypeHint(bindToken, inferred, hints);
}
```

```java
// After:
Token bindToken = accItem.identifier().getStart();
var accCall = accItem.accumulateCall();
if (accCall != null && accCall.qualifiedName() != null) {
    String funcName = accCall.qualifiedName().getText();
    SemanticType inferred = AccumulateFunctionTypes.resultType(funcName)
            .map(ctx::resolveTypeToSemanticType)
            .orElse(null);
    if (inferred != null) {
        addTypeHint(bindToken, inferred, hints);
    }
}
```

Delete the `inferAccumulateResultType` method (lines 130-142).

Add import: `import org.drools.drlx.completion.semantic.AccumulateFunctionTypes;`

- [ ] **Step 3: Modify DrlxCompletionHelper.java**

Delete `ACCUMULATE_FUNCTIONS` list (lines 139-140).

Replace `resolveAccumulateFunctionNames` method (lines 142-146):

```java
// Before:
private List<CompletionItem> resolveAccumulateFunctionNames() {
    return ACCUMULATE_FUNCTIONS.stream()
            .map(name -> createCompletionItem(name, CompletionItemKind.Function))
            .toList();
}
```

```java
// After:
private List<CompletionItem> resolveAccumulateFunctionNames() {
    return AccumulateFunctionTypes.functionNames().stream()
            .map(name -> createCompletionItem(name, CompletionItemKind.Function))
            .toList();
}
```

Add import: `import org.drools.drlx.completion.semantic.AccumulateFunctionTypes;`

- [ ] **Step 4: Build and run all existing accumulate tests**

Run: `mvn -pl drlx-completion -am install -DskipTests -q`
Run: `mvn -pl drlx-completion test -Dtest="DrlxCompletionHelperIncompleteCodeTest#incompleteRule_accumulateResultBinding_var+incompleteRule_accumulateResultBinding_explicitType+incompleteRule_accumulateResultBinding_count+incompleteRule_accumulateFunctionName+incompleteRule_accKeyword_sourceBinding_dotAccess+accKeyword_initVar_dotAccess" -q`

Expected: all 6 tests pass.

Run: `mvn -pl drlx-completion test -Dtest="DrlxInlayHintHelperTest#testAccumulateVarHint" -q`

Expected: pass — `count` still resolves to `Long`.

If any test fails because `Comparable` doesn't resolve for `min`/`max`, change the map entries to `"Number"` for those two functions and re-run.

- [ ] **Step 5: Run full test suite**

Run: `mvn -pl drlx-completion test -q`

Expected: all tests pass — no regressions.

- [ ] **Step 6: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java \
       drlx-completion/src/main/java/org/drools/drlx/completion/DrlxInlayHintHelper.java \
       drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java
git commit -m "refactor: wire AccumulateFunctionTypes into completion and inlay hints (#11)"
```

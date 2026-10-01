# AccumulateFunctionTypes — Spec (issue #11, item #20)

## Problem

Accumulate result type inference is duplicated in two places:

- `CompletionContext.inferAccumulateResultType()` (line 245)
- `DrlxInlayHintHelper.inferAccumulateResultType()` (line 130)

A third location — `DrlxCompletionHelper.ACCUMULATE_FUNCTIONS` (line 139) — maintains a separate list of function names for completion suggestions. Adding a function requires editing three places.

The inline switch statements also use imprecise types (`sum`/`avg`/`min`/`max` all map to `Number`), whereas the Drools engine defines more specific return types.

## Solution

Extract a single `AccumulateFunctionTypes` utility class that is the sole source of truth for accumulate function names and their return types.

## Class design

**Package:** `org.drools.drlx.completion.semantic`

**Structure:** Final utility class, no instantiation.

```java
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

    public static Optional<String> resultType(String functionName) { ... }
    public static Set<String> functionNames() { ... }
}
```

### API

| Method | Returns | Purpose |
|---|---|---|
| `resultType(String functionName)` | `Optional<String>` | Return type name for a known function; empty for unknown |
| `functionNames()` | `Set<String>` | All known function names (for completion item generation) |

## Wiring changes

### CompletionContext.java

Delete `inferAccumulateResultType(AccumulateItemContext)` method. In `collectBoundOopathFromTree`, replace the `var` branch:

```java
// Before
SemanticType inferred = inferAccumulateResultType(accItem);

// After
String funcName = accItem.accumulateCall().qualifiedName().getText();
SemanticType inferred = AccumulateFunctionTypes.resultType(funcName)
    .map(this::resolveTypeToSemanticType)
    .orElse(null);
```

### DrlxInlayHintHelper.java

Delete the duplicated `inferAccumulateResultType` method. Replace its call site with the same `AccumulateFunctionTypes.resultType()` lookup pattern.

### DrlxCompletionHelper.java

Delete `ACCUMULATE_FUNCTIONS` list. In the `ACCUMULATE_FUNCTION` site handler, use `AccumulateFunctionTypes.functionNames()` to build completion items.

## Type precision change

| Function | Before | After |
|---|---|---|
| `sum` | `Number` | `Double` |
| `avg` | `Number` | `Double` |
| `min` | `Number` | `Comparable` |
| `max` | `Number` | `Comparable` |
| `count` | `Long` | `Long` (unchanged) |
| `collectList` | `java.util.List` | `java.util.List` (unchanged) |
| `collectSet` | `java.util.Set` | `java.util.Set` (unchanged) |

Existing tests assert members like `intValue`/`doubleValue` which are present on `Double` and `Comparable` (via `Number` subtype resolution), so tests should continue to pass. If `Comparable` lacks the expected members, fall back to `Number` for min/max.

## Testing

### Unit tests: `AccumulateFunctionTypesTest`

- `resultType` returns correct type for each of the 7 functions
- `resultType` returns empty for an unknown function name
- `functionNames` returns all 7 names

### Integration verification

Existing tests cover the wiring:

- `DrlxCompletionHelperIncompleteCodeTest`: accumulate result binding dot-access (lines 197, 216, 235), accumulate function name completion (line 410)
- `DrlxInlayHintHelperTest`: accumulate var hint (line 49)

No new integration tests needed — the existing ones verify the end-to-end behavior.

## Scope

- No changes to ANTLR grammar or parse tree handling
- No new dependencies
- No LSP protocol changes

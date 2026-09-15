# Issue #9 Item 1: Entry-Point Name Completions After `/`

## Problem

When typing `var p : /|`, the LSP should offer entry-point names from the unit class (e.g., `persons`, `addresses`). Currently `CompletionSite.ENTRY_POINT` is correctly detected by `CompletionContextAnalyzer` but falls through to the `default` branch in `createSemanticCompletions`, returning a placeholder `IDENTIFIER`.

## Approach

Add a `resolveEntryPointNames()` method to `CompletionContext` that enumerates DataStore/DataSource field names from the unit class via reflection. Wire it into the completion dispatch.

## Design

### CompletionContext.resolveEntryPointNames()

New method returning `List<String>`. Uses the existing unit class resolution path:

1. `unitClassName()` to get the declared unit name from the parse tree
2. `resolveToFqcn()` to resolve imports
3. `Class.forName(fqcn, false, model.projectClassLoader())` to load the class
4. Iterate `clazz.getDeclaredFields()`, collect field names where `field.getGenericType()` is a `ParameterizedType` with at least one type argument

This matches the existing `resolveEntryPointType()` field-matching pattern but collects all qualifying field names instead of resolving a single known name.

Returns empty list with diagnostic if the unit class can't be loaded or isn't declared.

### DrlxCompletionHelper dispatch

Add to `createSemanticCompletions`:

```java
case ENTRY_POINT -> resolveEntryPointNames(ctx);
```

New private method `resolveEntryPointNames(CompletionContext ctx)` calls `ctx.resolveEntryPointNames()` and maps each name to a `CompletionItem` with `CompletionItemKind.Field`.

### Test domain change

Extend `MyUnit` with: `public DataStore<Address> addresses = DataSource.createStore();`

### Tests

In `DrlxCompletionHelperTest`:

- `var p : /|` with `unit MyUnit;` — completions include `persons` and `addresses`
- No `unit` declaration — no entry-point completions (empty, no crash)

## Files changed

| File | Change |
|------|--------|
| `CompletionContext.java` | Add `resolveEntryPointNames()` |
| `DrlxCompletionHelper.java` | Add `case ENTRY_POINT` and `resolveEntryPointNames()` method |
| `MyUnit.java` | Add `addresses` field |
| `DrlxCompletionHelperTest.java` | Add entry-point completion tests |

## Out of scope

- Named window completions
- OOPath navigation chunk completions (item #2)
- Prefix filtering (handled client-side per LSP protocol)

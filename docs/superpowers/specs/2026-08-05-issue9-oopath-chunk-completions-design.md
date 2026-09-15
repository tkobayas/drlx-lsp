# Issue #9 Item 2: OOPath Navigation Chunk Completions After `/`

## Problem

When typing `var a : /persons/|`, the LSP should offer navigable properties of `Person` (e.g., `address`, `previousAddresses`). Currently `CompletionSite.OOPATH_CHUNK` is correctly detected by `CompletionContextAnalyzer` but falls through to the `default` branch in `createSemanticCompletions`, returning a placeholder `IDENTIFIER`.

## Approach

Add a `resolveOopathChunkCompletions()` method to `CompletionContext` that walks the OOPath parse tree, resolves the type at the caret position, and enumerates navigable properties. Wire it into the completion dispatch via `case OOPATH_CHUNK`.

## Design

### CompletionContext.resolveOopathChunkCompletions()

New method returning `List<String>`. Steps:

1. Find the `OopathExpressionContext` enclosing the caret (walk from the rule body, same pattern as `findOopathConstraintAtCaret`)
2. Resolve root type: `resolveEntryPointType(root.identifier(0).getText())`
3. Walk preceding chunks (those whose token range is fully before the caret): for each, call existing `resolvePropertyType(currentType, chunkName)` to advance the type. When the resolved type is a collection, unwrap to the element type.
4. Enumerate navigable properties of the resolved type: iterate getters and fields (same logic as `addPropertiesAsSymbols`), but filter to only navigable types

### Navigable type filter — `isNavigableType()`

A property is navigable if:
- Its resolved type is a `ResolvedReferenceType` whose qualified name does NOT start with `java.lang.` (excludes String, Integer, Boolean, etc.), OR
- Its resolved type is a collection (`Collection`, `List`, `Set`, `Iterable`) with a type argument that is itself navigable

Primitives are already excluded since they are not reference types.

### Collection unwrapping — `unwrapCollectionElementType()`

When `resolvePropertyType()` returns a collection type (e.g., `List<Address>`), extract the first type argument (`Address`) and use that as the current type for the next navigation chunk. This enables completions like `/persons/previousAddresses/|` → properties of `Address`.

### DrlxCompletionHelper dispatch

Add to `createSemanticCompletions`:

```java
case OOPATH_CHUNK -> resolveOopathChunkCompletions(ctx);
```

New private method `resolveOopathChunkCompletions(CompletionContext ctx)` calls `ctx.resolveOopathChunkCompletions()` and maps each name to a `CompletionItem` with `CompletionItemKind.Property`.

### Test domain changes

- `Address.java`: add `Country country` field with getter/setter
- `Person.java`: add `List<Address> previousAddresses` field with getter/setter
- `Country.java` (new): test domain class with `String name`

### Tests

In `DrlxCompletionHelperTest`:

1. **Single-segment chunk** — `/persons/` with unit + import → includes `address`, `previousAddresses`; excludes `name` (String), `age` (primitive)
2. **Multi-segment chunk** — `/persons/address/` → includes `country`
3. **Collection navigation** — `/persons/previousAddresses/` → includes `country` (unwrapped from `List<Address>` to `Address`)
4. **No unit declaration** — empty completions, no crash
5. **Unknown entry-point** — empty completions

## Files changed

| File | Change |
|------|--------|
| `CompletionContext.java` | Add `resolveOopathChunkCompletions()`, `isNavigableType()`, `unwrapCollectionElementType()` |
| `DrlxCompletionHelper.java` | Add `case OOPATH_CHUNK` and `resolveOopathChunkCompletions()` method |
| `Address.java` | Add `Country country` field with getter/setter |
| `Person.java` | Add `List<Address> previousAddresses` field with getter/setter |
| `Country.java` (new) | New test domain class with `String name` |
| `DrlxCompletionHelperTest.java` | Add 5 OOPath chunk completion tests |

## Out of scope

- Runtime support for multi-segment OOPath execution
- Constraint property completions inside `[...]` (item #3)
- Inline cast type completions after `#` in chunks

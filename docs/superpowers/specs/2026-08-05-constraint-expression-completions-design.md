# Constraint Expression Property Completions (Issue #9 Item #3)

## Problem

When the caret is inside an OOPath constraint bracket (`[...]`), no type-aware completions are offered. The `CompletionSite.CONSTRAINT_EXPRESSION` is detected correctly, but `createSemanticCompletions()` has no handler for it — it falls to `default`, returning a generic `IDENTIFIER` placeholder.

## Solution

Add a dedicated `resolveConstraintCompletions()` method to `CompletionContext` and wire it via a new `case CONSTRAINT_EXPRESSION` in `DrlxCompletionHelper.createSemanticCompletions()`.

### Behavior

| Input | Completions |
|-------|------------|
| `/persons[\|` | `name`, `age`, `address`, `previousAddresses`, `this` |
| `/persons/address[\|` | `city`, `country`, `this` |
| `/persons/previousAddresses[\|` | `city`, `country`, `this` (unwraps `List<Address>`) |

All properties (fields + getter-derived) are offered — no navigability filter. `this` is always included.

### Approach: New method (Approach A)

Follows the established pattern of `resolveOopathChunkCompletions()` — a self-contained public method in `CompletionContext` that walks the OOPath tree, resolves the constraint's enclosing type, and returns property names. Slight duplication of OOPath-walking logic is acceptable for clean separation.

## Changes

### CompletionContext.java

New public method `resolveConstraintCompletions()`:
1. Find enclosing rule, walk parse tree to find `OopathExpressionContext` containing the caret
2. Resolve root entry-point type via `resolveEntryPointType()`
3. Walk `oopathChunk` nodes before the caret, resolving each property type (with collection unwrapping via `unwrapCollectionElementType()`)
4. Determine which chunk/root contains the caret's `[...]` bracket
5. Collect all properties via `collectAllProperties()` (new private method — like `collectNavigableProperties()` but without the navigability filter)
6. Add `this` to the result
7. Return as `List<String>`

New private method `collectAllProperties(SemanticType)`:
- Iterates declared methods for getter-derived names and all fields
- No `isNavigableType()` filter — includes primitives, String, etc.

### DrlxCompletionHelper.java

- Add `case CONSTRAINT_EXPRESSION` to the switch in `createSemanticCompletions()`
- New private method `resolveConstraintExpressionCompletions(CompletionContext)`:
  - Calls `ctx.resolveConstraintCompletions()`
  - Maps property names to `CompletionItem` with `CompletionItemKind.Property`
  - Maps `this` to `CompletionItemKind.Keyword`
  - Returns empty list if no completions resolved

## Tests (in DrlxCompletionHelperTest.java)

1. `constraintCompletion_rootConstraint_offersAllProperties` — `/persons[|` contains `name`, `age`, `address`, `previousAddresses`, `this`
2. `constraintCompletion_chunkConstraint_offersChunkTypeProperties` — `/persons/address[|` contains `city`, `country`, `this`; does not contain `name`, `age`
3. `constraintCompletion_collectionChunk_unwrapsElementType` — `/persons/previousAddresses[|` contains `city`, `country`, `this`
4. `constraintCompletion_noUnitDeclaration_returnsEmpty` — no unit → no constraint completions
5. `constraintCompletion_unknownEntryPoint_returnsEmpty` — unknown entry point → empty

## Key difference from OOPATH_CHUNK

| Aspect | OOPATH_CHUNK | CONSTRAINT_EXPRESSION |
|--------|-------------|----------------------|
| Properties offered | Navigable only (entities/collections) | All properties + `this` |
| Caret position | After `/` in navigation path | Inside `[...]` brackets |
| Purpose | Next path segment | Constraint expression start |

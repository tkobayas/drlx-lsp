# Design: Watch List Property Completions (Issue #9, Item 19)

## Goal

Inside the property-reactive watch list `[][prop, !prop]`, offer property names of the root entry-point type plus the `*` wildcard.

```
var p : /persons[][|        <- offer: name, age, address, previousAddresses, *
var p : /persons[age > 18][|  <- same offers (constraint present, caret in watch list)
```

## Grammar context

Watch lists are defined only on `oopathRoot` (the first OOPath segment), never on `oopathChunk`:

```
oopathRoot
    : identifier (HASH identifier)?
      ('(' positionalArg (',' positionalArg)* ')')?
      ('[' (drlxExpression (',' drlxExpression)*)? ']')?   // constraints (first [])
      ('[' watchItem (',' watchItem)* ']')?                // watch list (second [])
    ;

watchItem
    : '!'? ('*' | identifier)
    ;
```

A `watchItem` is an optional `!` prefix followed by either `*` (wildcard) or an `identifier` (property name). The grammar is unambiguous: the first `[...]` is constraints, the second `[...]` is the watch list. The generated parser has `RULE_watchItem = 38`.

## Approach

Same pattern as existing sites: c3 identifier-stack detection + type-based property resolution.

When the caret is inside the second bracket pair, antlr4-c3 predicts `identifier` as a candidate and includes `RULE_watchItem` in its rule call stack (since `watchItem -> identifier`). We check for this rule in the analyzer, then resolve the root entry-point type and offer all its properties.

No grammar changes needed.

## Changes

### 1. CompletionSite — add `OOPATH_WATCH_LIST`

New enum value in `CompletionSite.java`. Gets `needsSemanticCompletions() == true` automatically.

### 2. CompletionContextAnalyzer — detect watch-list context

Add an identifier-stack check for `RULE_watchItem` **before** the existing `RULE_oopathRoot` check (line 68). Since `watchItem` is nested inside `oopathRoot`, both rules appear in the stack — the more specific check must come first. Without this ordering, watch-list positions would fall through to `ENTRY_POINT`.

```java
if (identifierStack.contains(DrlxParser.RULE_watchItem)) {
    return CompletionSite.OOPATH_WATCH_LIST;
}
```

Placement: after the `RULE_drlxExpression` block (line 61–66) and before the `RULE_oopathRoot` check (line 68). This ensures constraint expressions inside the first `[...]` still resolve to `CONSTRAINT_EXPRESSION`, not `OOPATH_WATCH_LIST`.

### 3. DrlxCompletionHelper — switch case + resolution method

Add a new case in `createSemanticCompletions`:

```java
case OOPATH_WATCH_LIST -> resolveWatchListCompletions(ctx);
```

New method `resolveWatchListCompletions(CompletionContext ctx)`:
- Calls `ctx.resolveWatchListCompletions()` to get property names
- Maps each to `CompletionItemKind.Property`
- Adds `*` as `CompletionItemKind.Keyword` (the watch-all wildcard)

### 4. CompletionContext — resolveWatchListCompletions()

New method following the same pattern as `resolveConstraintProperties`:

1. Find the `DrlxCompilationUnitContext`, then the enclosing rule
2. Walk the rule body tree for `OopathExpressionContext` nodes that contain the caret
3. Get the `oopathRoot` identifier (entry-point name, e.g. `persons`)
4. Resolve entry-point type via existing `resolveEntryPointType()` (e.g. `Person`)
5. Call existing `collectAllProperties(rootType)` to enumerate getter/field-derived property names
6. Return the property list

Differences from `resolveConstraintProperties`:
- No chunk-walking needed — watch lists exist only on the root
- No `this` added — watch lists name reactive properties, not constraint expressions
- Simpler: just root type -> properties

The method reuses `findDrlxCompilationUnit()`, `findEnclosingRule()`, `resolveEntryPointType()`, and `collectAllProperties()` — all existing infrastructure.

### 5. Tests

Two tests in `DrlxCompletionHelperIncompleteCodeTest`:

**`incompleteRule_watchList_emptyConstraint`** — empty constraint with watch list:
```java
String text = """
        import org.drools.drlx.domain.MyUnit;
        unit MyUnit;

        rule R1 {
            var p : /persons[][
        """;
// caret after second '['
```
Assert: offers `name`, `age`, `address`, `previousAddresses`.

**`incompleteRule_watchList_withConstraint`** — constraint present, caret in watch list:
```java
String text = """
        import org.drools.drlx.domain.MyUnit;
        unit MyUnit;

        rule R1 {
            var p : /persons[age > 18][
        """;
// caret after second '['
```
Assert: same property offers.

## Files touched (source repo)

All in `drlx-completion/src/main/java/org/drools/drlx/completion/`:

- `CompletionSite.java` — add enum value
- `CompletionContextAnalyzer.java` — add `RULE_watchItem` check before `RULE_oopathRoot`
- `DrlxCompletionHelper.java` — add switch case + `resolveWatchListCompletions` method
- `semantic/CompletionContext.java` — add `resolveWatchListCompletions()` method

Test file:
- `DrlxCompletionHelperIncompleteCodeTest.java` — add 2 tests

## Risk

If antlr4-c3 does not produce `RULE_watchItem` in the identifier stack for incomplete `[][` input (e.g. due to ANTLR error recovery confusing the two bracket pairs), the identifier-stack check won't fire. Fallback: add a token-level detection method (like `isAfterNew`) that scans backward for the `][` pattern to identify we're in the second bracket pair. This would be placed in the Phase 1 token checks in the analyzer. Verify during implementation by running the tests first.

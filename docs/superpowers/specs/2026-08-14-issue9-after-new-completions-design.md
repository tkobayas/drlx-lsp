# Design: Class Names After `new` Keyword (Issue #9, Item 15)

## Goal

After typing `new ` in a rule's consequence (RHS `do {}` block), offer class names from the file's import declarations.

```
do { Person p = new |  <- offer: Person, Address, ...
```

## Approach

Token-before-caret detection, mirroring the `INLINE_CAST_TYPE` (after `#`) pattern. The `new` keyword is followed by a class name, and the available class names are exactly the file's imports — the same set `INLINE_CAST_TYPE` already uses via `ctx.resolveImportedTypeNames()`.

No grammar changes needed. The grammar already has `NEW creator -> createdName -> identifier`, so ANTLR c3 recognizes `identifier` as a candidate after `new`. We only need to classify the site and produce the right semantic completions.

Only explicitly imported types are offered (no implicit `java.lang` types), consistent with `INLINE_CAST_TYPE`.

## Changes

### 1. CompletionSite — add `AFTER_NEW`

New enum value in `CompletionSite.java`. Gets `needsSemanticCompletions() == true` automatically (only `UNKNOWN` returns false).

### 2. CompletionContextAnalyzer — detect after-new context

Add Phase 1 token check: if the token before the caret is `DrlxLexer.NEW`, return `CompletionSite.AFTER_NEW`. Placed after the existing `isHashAccess` check, following the same `isAfterNew` helper pattern as `isDotAccess`/`isHashAccess`/`isAtAccess`.

### 3. DrlxCompletionHelper — return imported type names

Add `AFTER_NEW` to the `createSemanticCompletions` switch. Since the behavior is identical to `INLINE_CAST_TYPE` (call `ctx.resolveImportedTypeNames()`, return as `CompletionItemKind.Class`), combine both cases in the switch:

```java
case INLINE_CAST_TYPE, AFTER_NEW -> resolveInlineCastTypeNames(ctx);
```

No new method needed.

### 4. Test

One test in `DrlxCompletionHelperIncompleteCodeTest`: a rule with `do { Person p = new ` in the consequence block, caret positioned after `new `, asserting that `Person` and `Address` appear as completions.

## Files touched (source repo)

- `CompletionSite.java` — add enum value
- `CompletionContextAnalyzer.java` — add `isAfterNew` helper + Phase 1 check
- `DrlxCompletionHelper.java` — add `AFTER_NEW` to existing switch case
- `DrlxCompletionHelperIncompleteCodeTest.java` — add test

# Issue #9 Item 9: Query Parameter Completions

## Problem

When typing `/personsByAge[|` inside a rule, the completion engine should offer the query's parameter names (`minAge`, `result`). Currently the `CONSTRAINT_EXPRESSION` site fires, tries to resolve `personsByAge` as a unit DataStore field, fails, and returns nothing useful.

## Approach

Create a new `QUERY_PARAMETER` CompletionSite. Detect it in `CompletionContextAnalyzer` by checking whether the oopathRoot identifier matches a query (a `ruleDeclaration` with a `ruleParameterList`). Resolve it in `CompletionContext` by extracting parameter names from the matching query's parameter list.

## Design

### 1. CompletionSite enum

Add `QUERY_PARAMETER` to `CompletionSite`. It represents the caret inside `[...]` brackets of an oopathRoot whose identifier matches a query name.

### 2. CompletionContextAnalyzer changes

**Signature change:** `analyze()` gains a `ParseTree parseTree` parameter (the caller already has it).

**Detection logic:** When the c3 call stack contains `RULE_drlxExpression` (which currently returns `CONSTRAINT_EXPRESSION`), insert a check before returning: walk backward from the caret to find the enclosing `oopathRoot`, extract its identifier, then scan all `ruleDeclaration` nodes in the `drlxCompilationUnit` for one with a matching name and a non-null `ruleParameterList`. If found, return `QUERY_PARAMETER`; otherwise fall through to `CONSTRAINT_EXPRESSION` as before.

Helper method on the analyzer:
- `isQueryInvocation(parseTree, parser, caretTokenIndex)` — walk the parse tree for `OopathRootContext` nodes near the caret, extract the root identifier, then scan `ruleDeclaration` nodes for one with a matching name and `ruleParameterList`

### 3. CompletionContext changes

New method `resolveQueryParameterNames()`:
- Find the `drlxCompilationUnit`
- Find the `oopathExpression` containing the caret, extract root identifier
- Scan `ruleDeclaration` nodes for one with matching name and `ruleParameterList`
- Return parameter names from `ruleParameter.identifier().getText()`

### 4. DrlxCompletionHelper changes

New case in `createSemanticCompletions`:
- `QUERY_PARAMETER` → call `ctx.resolveQueryParameterNames()`, map each to `CompletionItemKind.Property`

New method `resolveQueryParameterCompletions(ctx)` following the existing pattern of `resolveEntryPointNames(ctx)`.

### 5. Test

One new test in `DrlxCompletionHelperIncompleteCodeTest`:

```java
// DRLX text with a query definition and a rule invoking it
import org.drools.drlx.domain.Person;
import org.drools.drlx.domain.MyUnit;

unit MyUnit;

rule personsByAge(int minAge, Person result) {
    Person p : /persons[age >= minAge],
    do { result = p; }
}

rule R1 {
    /personsByAge[
}
```

Caret positioned after `[` on the `/personsByAge[` line. Assert completions include `minAge` and `result`.

## Files changed

| File | Change |
|------|--------|
| `CompletionSite.java` | Add `QUERY_PARAMETER` |
| `CompletionContextAnalyzer.java` | Add `parseTree` param; detect query invocation before `CONSTRAINT_EXPRESSION` |
| `CompletionContext.java` | Add `resolveQueryParameterNames()` |
| `DrlxCompletionHelper.java` | Add `QUERY_PARAMETER` case + `resolveQueryParameterCompletions()` |
| `DrlxCompletionHelperIncompleteCodeTest.java` | 1 new test |

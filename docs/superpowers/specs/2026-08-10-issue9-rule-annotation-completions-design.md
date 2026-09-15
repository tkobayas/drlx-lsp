# Design: Rule Annotation Completions (Issue #9, Item 10)

## Goal

After `@` before a `rule` keyword, offer known DRLX rule annotation names.

```
@|                      <- offer: Salience, NoLoop, Timer, ...
rule R1 {
```

## Approach

Static list, mirroring the `ACCUMULATE_FUNCTION` pattern. The 11 annotation types defined in `org.drools.drlx.annotations` are a closed set maintained by the framework.

## Changes

### 1. CompletionSite — add `RULE_ANNOTATION`

New enum value in `CompletionSite.java`.

### 2. CompletionContextAnalyzer — detect annotation context

When the c3 identifier stack contains `RULE_annotation` (rule 121), return `CompletionSite.RULE_ANNOTATION`. Placed after the `ACCUMULATE_FUNCTION` check and before `RULE_ruleConsequence` checks.

### 3. DrlxCompletionHelper — return annotation names

Static list of 11 names: `ActivationGroup`, `DataSource`, `DateEffective`, `DateExpires`, `Description`, `Disabled`, `Duration`, `LockOnActive`, `NoLoop`, `Salience`, `Timer`.

Returned as `CompletionItemKind.Class` (standard LSP kind for annotation types). Wired into the `createSemanticCompletions` switch.

### 4. Test

One test in `DrlxCompletionHelperIncompleteCodeTest`: text with `@` before a rule, caret positioned after `@`, asserting all 11 annotation names appear in completions.

## Files touched (source repo)

- `CompletionSite.java` — add enum value
- `CompletionContextAnalyzer.java` — add detection branch
- `DrlxCompletionHelper.java` — add static list, resolver method, switch case
- `DrlxCompletionHelperIncompleteCodeTest.java` — add test

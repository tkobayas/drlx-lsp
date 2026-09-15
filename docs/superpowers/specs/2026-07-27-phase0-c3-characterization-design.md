# Phase 0: C3 Characterization Tests

Related: [#5 Review and consider incomplete code completion approach](https://github.com/tkobayas/drlx-lsp/issues/5)

Parent design: `Completion_Redesign.md`

## Goal

Characterize antlr4-c3 behavior by recording `candidates.tokens`, `candidates.rules`, and rule call stacks at many caret positions. This establishes a baseline before making any changes to `PREFERRED_RULES` or completion context detection.

## Motivation

The `Completion_Redesign_Review01.md` recommends: "Before changing PREFERRED_RULES, add table-driven tests which record candidates.tokens, candidates.rules, and the call stack for every preferred-rule candidate." These tests serve as documentation of C3's behavior and as regression guards — if a future grammar change alters the candidates, the test failure pinpoints exactly which position changed and how.

## New test class

`DrlxC3CandidatesTest.java` in `drlx-completion/src/test/java/org/drools/drlx/completion/`

### Shared fixture text

A single comprehensive DRLX text block exercising all positions of interest:

```drlx
unit MyUnit;

rule R1 {
    Person p1 : /persons[age > 18],
    var a : /persons/address#Address#[city == "Tokyo"],
    not /orders,
    exists /orders,
    test p1.age > 18,
    do {
        System.out.println(p1);
    }
}

rule R2(String name) {
    var p : /persons[name == name],
    do { System.out.println(p); }
}
```

### Shared helpers

```java
// Run C3 at a position and return raw CandidatesCollection + parser
private static CandidateResult collectAt(String text, int line, int col)

// Convert token IDs in candidates.tokens to lowercase display names
private static Set<String> tokenNames(CandidateResult r)

// Get the rule call stack for a given rule candidate, translated to RULE_xxx names
private static List<String> ruleCallStack(CandidateResult r, int ruleIndex)

// Check whether a rule is present in candidates.rules
private static boolean hasRule(CandidateResult r, int ruleIndex)
```

`CandidateResult` is a simple record holding `CandidatesCollection` and `DrlxParser` (needed for vocabulary lookup).

Rule names are translated using `DrlxParser.ruleNames[index]` for human-readable output.

Token display names are translated using `parser.getVocabulary().getDisplayName(tokenType)`, lowercased, with quotes stripped — same logic as `DrlxCompletionHelper`.

### Test methods

One `@Test` per caret position. Each asserts on tokens, rule presence, and call stack.

| Test name | Caret position | What it characterizes |
|-----------|---------------|----------------------|
| `compilationUnitStart` | Before `unit` (line 0, col 0) | Top-level keywords (package, import, unit, class, etc.) |
| `beforeRule` | Before `rule R1` | `rule`, `window` keywords |
| `afterRuleKeyword` | After `rule ` | Rule name — IDENTIFIER only |
| `boundType` | At `Person` in `Person p1 :` | Explicit type position |
| `bindName` | At `p1` in `Person p1 :` | Bind name position |
| `afterSlash` | After `/` in `/persons` | OOPath root identifier |
| `oopathChunkIdentifier` | At `address` in `/persons/address` | OOPath chunk identifier |
| `inlineCastAfterHash` | After `#` in `address#Address#` | Cast type position |
| `constraintExpression` | At `age` in `[age > 18]` | Inside constraint bracket |
| `constraintChunkExpression` | At `city` in `[city == "Tokyo"]` | Constraint in chained OOPath chunk |
| `ruleItemStart` | Before `not /orders,` | CE keywords + identifier |
| `consequenceBlock` | Inside `do { ... }` | Java expression starters |
| `afterDot` | After `.` in `System.out.` or similar | Dot-access position |
| `testElementExpression` | After `test ` | Expression context |
| `ruleParameterType` | At `String` in `R2(String name)` | Parameter type position |

### Assertion style

Each test asserts three aspects:

1. **Token names** — which keyword tokens C3 offers:
   ```java
   assertThat(tokenNames(r)).contains("not", "exists", "do", "var");
   assertThat(tokenNames(r)).doesNotContain("rule", "window");
   ```

2. **Rule candidates** — which preferred rules are present:
   ```java
   assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
   ```

3. **Rule call stack** — the ancestor path for the preferred rule, showing grammatical context:
   ```java
   assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
       .contains("ruleItem", "boundOopath");
   ```

### What these tests are NOT

- Not behavior tests — they don't test what completion items the user sees
- Not prescriptive — they record what C3 produces today, not what it should produce
- Changes to these tests are expected when the grammar evolves or `PREFERRED_RULES` changes

## Files

- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxC3CandidatesTest.java`

## Verification

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test
```

All tests pass including the new characterization tests.

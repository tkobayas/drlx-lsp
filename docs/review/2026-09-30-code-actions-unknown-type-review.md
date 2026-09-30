# Review: Code Actions (Unknown Type Quick Fix) & ClassIndex

**Date:** 2026-09-30

**Documents reviewed:**

- [Design spec](../superpowers/specs/2026-09-30-code-actions-unknown-type-spec.md)
- [Implementation plan](../superpowers/plans/2026-09-30-code-actions-unknown-type-plan.md)

Reviewed against the current drlx-lsp source and the drools-lsp source in `/Users/toshiyakobayashi/usr/work/drlx-dev/drools-lsp`.

## Findings

### 1. [P1] OOPath roots are not Java type references

**Location:** Spec line 92; plan lines 37–40.

`/persons` refers to a rule-unit data source; roots can also reference queries. The proposed check would report valid rules as unknown types and suggest inappropriate class-name replacements. Existing `CompletionContext.resolveEntryPointType()` resolves these through unit fields.

Restrict this lint to actual type positions, and add negative tests for data-source and query roots. Also correct the binding examples to DRLX syntax: `Person p : /persons`.

### 2. [P1] Classpath readiness and diagnostic refresh are missing

**Location:** Plan lines 74–81; spec sections 3.2 and 3.5.

The server initially loads only `target/classes`, then resolves Maven dependencies asynchronously. Opening a document during that interval would flag valid dependency types. Completing resolution currently does not republish diagnostics, so warnings would persist until another edit.

Specify readiness gating and revalidation of open documents after resolution; test both delayed and failed resolution. A nonempty index alone does not establish readiness.

### 3. [P2] Prefix matching cannot establish that a type exists

**Location:** Spec line 96.

`getMatching()` is explicitly a prefix search. With only `Person` indexed, `new Perso()` would have a match and could escape diagnosis.

Require exact-name checks, including the qualification for fully qualified references. The drools-lsp resolver explicitly filters prefix results for exact simple-name matches. Add a regression test for this case.

### 4. [P2] Suggested replacements are not guaranteed to resolve in the document

**Location:** Spec lines 97–104 and 128.

Candidates come from every indexed class, but the fix inserts only a simple name. With `com.example.Person` indexed but not imported, replacing `Preson` with `Person` still leaves an unresolved reference under the current semantic resolver.

For this replacement-only scope, restrict suggestions to names resolvable at the use site, or specify qualification/import edits. Test by applying the edit and verifying resolution, rather than checking only the action title and payload.

## Build procedure

Replace the plan's repeated `mvn clean test -pl drlx-completion` commands with the required `mvn -pl drlx-completion -am install` workflow.

## Validation

This was a document and source review. No implementation changes were made and no tests were run.

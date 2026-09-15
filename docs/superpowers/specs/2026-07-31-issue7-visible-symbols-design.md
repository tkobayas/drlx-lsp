# Issue #7: CompletionSite + VisibleSymbols

## Context

The `SentinelExpressionTypeResolver` resolves types for dot-access completions by transpiling expressions through MVEL. Currently it ignores the `VisibleSymbols` parameter (always empty), so any expression referencing a variable (`p.address.`, `list.get(0).`, `arr[0].`) fails with "Unsolved symbol". This issue populates `VisibleSymbols` from three sources — RHS local variable declarations, OOPath pattern bindings, and rule parameters — and wires them into the resolver via the MVEL Declaration API.

12 `@Disabled` tests depend on this work.

## Architecture

```
DrlxCompletionHelper.resolveDotAccess()
    │
    ├── CompletionContext.buildVisibleSymbols()   ← NEW: parse tree walk
    │       ├── extractLocalVariables()           ← do { } block declarations
    │       ├── extractOopathBindings()           ← boundOopath patterns  
    │       ├── extractRuleParameters()           ← query parameters
    │       └── resolveEntryPointType()           ← unit class DataStore<T> field inspection
    │
    └── resolver.resolve(expr, symbols, model)
            │
            └── SentinelExpressionTypeResolver    ← converts VisibleSymbols → Declaration[]
                    └── MVEL.map(declarations...) ← variables now known to transpiler
```

## Changes

### 1. VisibleSymbols — from empty shell to populated map

Add:
- `Map<String, SemanticType>` internal storage
- `Builder` inner class with `add(String name, SemanticType type)` and `build()`
- `Iterable<Map.Entry<String, SemanticType>> entries()` for the resolver to iterate
- Keep `VisibleSymbols.empty()` for backward compatibility

### 2. SentinelExpressionTypeResolver — consume VisibleSymbols via Declaration API

In `resolve()`:
- Iterate `symbols.entries()`
- For each entry, extract `Class<?>` from `SemanticType.resolvedType()` using the type solver
- Create `Declaration.of(name, clazz)`
- Pass `Declaration[]` to `MVEL.map(declarations...)` instead of bare `MVEL.map()`

Import: `org.mvel3.transpiler.context.Declaration`

### 3. CompletionContext.buildVisibleSymbols() — parse tree extraction

New method that walks the DRLX parse tree and collects visible symbols from three sources:

**a. RHS local variable declarations**

Walk the `ruleConsequence → DO statement → block → blockStatement*` tree for the rule containing the caret. For each `localVariableDeclaration` before the caret:
- `typeType variableDeclarators` → extract type name and first variable name
- `VAR identifier '=' expression` → infer type from expression (or use `Object` as fallback)
- Resolve type name to `SemanticType` using imports and the type solver

**b. OOPath pattern bindings**

Walk `boundOopath` nodes in the current rule: `identifier identifier (':' | '=') oopathExpression`.
- First identifier = type name (or `var` for inferred)
- Second identifier = bind name
- For explicit types (`Person p : /persons`): resolve type name via imports
- For `var` bindings: use entry-point type inference (see section 6)

**c. Rule parameters** (query parameters)

Walk `ruleParameter` nodes: `typeType identifier`.
- Extract type name and parameter name
- Resolve type via imports

### 4. Scope rules

- **Current rule only**: Walk only the `ruleDeclaration` containing the caret token. Determine the enclosing rule by checking which `ruleDeclaration` span includes `caretTokenIndex`.
- **Before caret only**: Skip declarations whose start token index is >= `caretTokenIndex`.
- **Shadowing**: RHS local variables shadow pattern bindings shadow rule parameters. Add in order: rule parameters → OOPath bindings → RHS locals. Later `add()` calls overwrite earlier ones.

### 5. Entry-point type inference

For `var p : /persons` where the entry point `/persons` corresponds to a `DataStore<Person>` field in the unit class:

1. Resolve the unit class name from `unitDeclaration` (already implemented in `CompletionContext.unitClassName()`)
2. Look up the class via imports + type solver (try `Class.forName(unitClassName, false, classLoader)`)
3. Find a field whose name matches the entry-point identifier (e.g., `persons`)
4. If the field type is `DataSource<T>` or `DataStore<T>`, extract the generic type parameter `T` via `java.lang.reflect.ParameterizedType`
5. Map the bind variable to `SemanticType.value(resolvedType)` for `T`

### 6. OOPath chunk type tracking (nestedOopathChunkConstraint)

For `/persons/address[city.]`:
- `persons` → root chunk, type `Person` (from entry-point or explicit type)
- `address` → navigation chunk, type = return type of `Person.getAddress()` → `Address`
- Inside `[city.]` constraint, `city` is a property of `Address`

The resolver needs the enclosing OOPath chunk's type as context. This requires:
- Walking the OOPath expression to determine which chunk the caret is in
- Tracking the type through each navigation step
- Providing the chunk type as the scope for constraint expression completions

### 7. DrlxCompletionHelper integration

In `resolveDotAccess()` (line 100), replace:
```java
resolver.resolve(expression, VisibleSymbols.empty(), model)
```
with:
```java
VisibleSymbols symbols = ctx.buildVisibleSymbols();
resolver.resolve(expression, symbols, model)
```

### 8. Test infrastructure

**New test domain classes** (in `drlx-completion/src/test/java/org/drools/drlx/domain/`):
- `MyUnit.java` — unit class with `public DataStore<Person> persons = DataSource.createStore();`

**New test dependency** (in `drlx-completion/pom.xml`):
- `org.drools:drools-ruleunits-api:10.2.0` (test scope) — provides `DataSource<T>`, `DataStore<T>`

**Write test bodies** for 5 currently-empty test stubs:
- `entryPointTypeInference` — `import MyUnit; unit MyUnit; rule R1 { var p : /persons, do { p. }`
- `bindingsFromEarlierPatterns` — `Person p : /persons, do { p. }`
- `noLeakageFromLaterPatterns` — two rules, assert R2's binding not visible in R1
- `shadowedLocalVariables` — pattern binding `p` shadowed by RHS `Address p = ...`
- `nestedOopathChunkConstraint` — `/persons/address[city.]`

**Update characterization tests** to pass populated `VisibleSymbols` where the resolver tests call `resolver.resolve(expr, VisibleSymbols.empty(), model)` and the variable comes from outside the code text (e.g., `inlineCastSimple` where `list` is undeclared).

**Fix `Person` constructor calls** in existing tests: `new Person("John", new Address("Tokyo"))` is invalid — `Person` has constructors `()`, `(String, int)`, `(String, int, Address)`. Fix to `new Person("John", 0, new Address("Tokyo"))`.

## Files

**Modify:**
- `VisibleSymbols.java` — builder, map, entries()
- `SentinelExpressionTypeResolver.java` — convert VisibleSymbols → Declaration[]
- `CompletionContext.java` — buildVisibleSymbols() with parse tree extraction
- `DrlxCompletionHelper.java` — wire VisibleSymbols into resolveDotAccess()
- `ExpressionTypeResolverCharacterizationTest.java` — write test bodies, remove @Disabled
- `DrlxCompletionHelperIncompleteCodeTest.java` — remove @Disabled
- `drlx-completion/pom.xml` — add drools-ruleunits-api test dependency

**Create:**
- `MyUnit.java` (test domain) — unit class with DataStore<Person> field

## Verification

```
mvn -pl drlx-completion -am install
mvn -pl drlx-completion test
```

Expected: all 12 formerly-disabled #7 tests pass. Only `realMavenWorkspaceClasses` (#6) remains disabled.

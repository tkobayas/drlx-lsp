# Issue #7: CompletionSite + VisibleSymbols — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Populate `VisibleSymbols` from the DRLX parse tree (RHS local variables, OOPath bindings, rule parameters, entry-point type inference) and wire them into `SentinelExpressionTypeResolver` via MVEL's Declaration API. Enable 12 `@Disabled` tests.

**Architecture:** `CompletionContext.buildVisibleSymbols()` walks the parse tree to extract typed variable bindings from three sources (local declarations, OOPath patterns, rule parameters). `SentinelExpressionTypeResolver` converts `VisibleSymbols` entries to `Declaration[]` and passes them to `MVEL.map(declarations...)`, making variables known to the MVEL transpiler. `DrlxCompletionHelper.resolveDotAccess()` calls `ctx.buildVisibleSymbols()` instead of `VisibleSymbols.empty()`.

**Tech Stack:** Java 17, MVEL3 (`Declaration`, `MVEL.map()`), JavaParser (type solver, `ReferenceTypeImpl`), ANTLR4 (generated parser context classes), JUnit 5, AssertJ

## Global Constraints

- All existing passing tests must continue to pass
- Code repo: `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`
- Source root: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/`
- Test root: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/`
- Domain test classes: `drlx-completion/src/test/java/org/drools/drlx/domain/`
- Build/install: `mvn -pl drlx-completion -am install`
- Test single: `mvn -pl drlx-completion test -Dtest="TestClassName#testMethod" -Dsurefire.useFile=false`
- Test all: `mvn -pl drlx-completion test -Dsurefire.useFile=false`
- Before tests, set `MvelParser.Factory.USE_ANTLR = true` and `MVELTranspiler.ENABLE_REWRITE = false`

## Key API Reference

**MVEL Declaration API:**
```java
import org.mvel3.transpiler.context.Declaration;
// Declaration.of("p", Person.class) — creates a Declaration
// MVEL.map(declarations...) — makes variables known to transpiler
// MVEL.map() — no variables (current behavior)
```

**TypeSolver API (JavaParser):**
```java
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
// typeSolver.solveType("org.drools.drlx.domain.Person") → ResolvedReferenceTypeDeclaration
// new ReferenceTypeImpl(decl) → ResolvedReferenceType (a ResolvedType)
// SemanticType.value(resolvedType) → SemanticType
```

**Parse tree grammar (from DrlxParser.g4):**
```
localVariableDeclaration: variableModifier* (VAR identifier '=' expression | typeType variableDeclarators)
variableDeclarators: variableDeclarator (',' variableDeclarator)*
variableDeclarator: variableDeclaratorId ('=' variableInitializer)?
variableDeclaratorId: identifier ('[' ']')*
blockStatement: localVariableDeclaration ';' | localTypeDeclaration | statement
block: '{' blockStatement* '}'
ruleConsequence: DO statement
statement: block | IF ... | FOR ... | ...
boundOopath: identifier identifier (':' | '=') oopathExpression windowFilter?
ruleParameter: typeType identifier
ruleParameterList: '(' ruleParameter (',' ruleParameter)* ')'
oopathRoot: identifier (HASH identifier)? ('(' positionalArg ... ')')? ('[' drlxExpression ... ']')?
oopathChunk: identifier (HASH identifier)? ('[' drlxExpression ... ']')?
oopathExpression: QUESTION? '/' oopathRoot ('/' oopathChunk)*
```

---

### Task 1: VisibleSymbols builder + resolver wiring

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/VisibleSymbols.java`
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/SentinelExpressionTypeResolver.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java`

**Interfaces:**
- Consumes: `SemanticType`, `Declaration.of(name, clazz)`, `MVEL.map(declarations...)`
- Produces: `VisibleSymbols.Builder`, `VisibleSymbols.entries()`, resolver that uses declarations

- [ ] **Step 1: Write a failing test — resolver with VisibleSymbols resolves variable**

In `ExpressionTypeResolverCharacterizationTest.java`, add a new test method after the existing passing tests (before the `@Disabled` section). This test manually creates a `VisibleSymbols` with `("p", Person)` and verifies the resolver resolves `p.address.` to `Address`. Since `VisibleSymbols.Builder` does not exist yet, this test will not compile.

```java
    @Test
    void resolverWithVisibleSymbolsResolvesVariable() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { p.address.
                """;

        ResolvedReferenceTypeDeclaration personDecl =
                model.typeSolver().solveType("org.drools.drlx.domain.Person");
        SemanticType personType = SemanticType.value(new ReferenceTypeImpl(personDecl));

        VisibleSymbols symbols = new VisibleSymbols.Builder()
                .add("p", personType)
                .build();

        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        ParseTree tree = parser.drlxStart();
        int caretTokenIndex = computeTokenIndex(parser, 9 + 1, 22);
        CompletionExpression expr = CompletionExpression.fromCaretPosition(parser, tree, caretTokenIndex);

        Optional<SemanticType> result = resolver.resolve(expr, symbols, model);
        assertThat(result).isPresent();
        assertThat(result.get().resolvedType().describe()).isEqualTo("org.drools.drlx.domain.Address");
    }
```

Add required imports:

```java
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
```

Expected: **Compilation error** — `VisibleSymbols.Builder` does not exist.

- [ ] **Step 2: Add Builder and entries() to VisibleSymbols**

Replace the contents of `VisibleSymbols.java`:

```java
package org.drools.drlx.completion.semantic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class VisibleSymbols {

    private static final VisibleSymbols EMPTY = new VisibleSymbols(Collections.emptyMap());

    private final Map<String, SemanticType> symbols;

    private VisibleSymbols(Map<String, SemanticType> symbols) {
        this.symbols = symbols;
    }

    public static VisibleSymbols empty() {
        return EMPTY;
    }

    public Optional<SemanticType> lookup(String name) {
        return Optional.ofNullable(symbols.get(name));
    }

    public Iterable<Map.Entry<String, SemanticType>> entries() {
        return symbols.entrySet();
    }

    public boolean isEmpty() {
        return symbols.isEmpty();
    }

    public static class Builder {
        private final Map<String, SemanticType> map = new LinkedHashMap<>();

        public Builder add(String name, SemanticType type) {
            map.put(name, type);
            return this;
        }

        public VisibleSymbols build() {
            if (map.isEmpty()) {
                return EMPTY;
            }
            return new VisibleSymbols(new LinkedHashMap<>(map));
        }
    }
}
```

Expected: Test compiles but **fails** — resolver still ignores `symbols`.

- [ ] **Step 3: Wire VisibleSymbols into SentinelExpressionTypeResolver**

Add import:

```java
import org.mvel3.transpiler.context.Declaration;
```

In `resolve()`, replace `MVEL.map()` with `MVEL.map(declarations)`:

```java
Declaration<?>[] declarations = toDeclarations(symbols);

var builder = MVEL.map(declarations).<Object>out(Type.OBJECT)
        .expression(repairedText)
        .classManager(new ClassManager())
        .classLoader(ClassLoader.getSystemClassLoader());
```

Add helper method:

```java
    @SuppressWarnings("unchecked")
    private Declaration<?>[] toDeclarations(VisibleSymbols symbols) {
        if (symbols.isEmpty()) {
            return new Declaration<?>[0];
        }
        var list = new java.util.ArrayList<Declaration<?>>();
        for (var entry : symbols.entries()) {
            try {
                String fqcn = entry.getValue().resolvedType().describe();
                Class<?> clazz = Class.forName(fqcn, false, ClassLoader.getSystemClassLoader());
                list.add(Declaration.of(entry.getKey(), clazz));
            } catch (ClassNotFoundException e) {
                logger.info("Cannot load class for declaration '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        return list.toArray(new Declaration<?>[0]);
    }
```

- [ ] **Step 4: Run the new test and verify it passes**

Run: `mvn -pl drlx-completion test -Dtest="ExpressionTypeResolverCharacterizationTest#resolverWithVisibleSymbolsResolvesVariable" -Dsurefire.useFile=false`

Expected: **Passes**

- [ ] **Step 5: Run full test suite to verify no regressions**

Run: `mvn -pl drlx-completion test -Dsurefire.useFile=false`

Expected: All previously passing tests still pass.

- [ ] **Step 6: Commit**

```
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/VisibleSymbols.java \
       drlx-completion/src/main/java/org/drools/drlx/completion/semantic/SentinelExpressionTypeResolver.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java
git commit -m "feat: VisibleSymbols builder and SentinelExpressionTypeResolver declaration wiring (#7)"
```

---

### Task 2: RHS local variable extraction (enables 5 characterization tests)

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/domain/Address.java`

**Interfaces:**
- Consumes: `VisibleSymbols.Builder`, `TypeSolver.solveType()`, `ReferenceTypeImpl`, `SemanticType.value()`
- Produces: `CompletionContext.buildVisibleSymbols()` — returns `VisibleSymbols` populated from RHS local variable declarations

**Dependencies:** Task 1 must be complete

- [ ] **Step 1: Add Address(String) constructor to test domain class**

In `Address.java`, add constructor:

```java
    public Address() {
    }

    public Address(String city) {
        this.city = city;
    }
```

- [ ] **Step 2: Fix Person constructor calls in test texts**

In `ExpressionTypeResolverCharacterizationTest.java`, fix `rhsLocalPropertyChain` and `nullSafeAccess`:
Change `new Person("John", new Address("Tokyo"))` to `new Person("John", 0, new Address("Tokyo"))`.

Also fix in `DrlxCompletionHelperIncompleteCodeTest.java` line 161: same change.

- [ ] **Step 3: Write resolveAt helper that builds VisibleSymbols from CompletionContext**

In `ExpressionTypeResolverCharacterizationTest.java`, add:

```java
    private Optional<SemanticType> resolveAtWithVisibleSymbols(String text, int line, int col) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        ParseTree tree = parser.drlxStart();

        int caretTokenIndex = computeTokenIndex(parser, line + 1, col);
        CompletionContext ctx = model.createContext(parser, tree, caretTokenIndex);
        VisibleSymbols symbols = ctx.buildVisibleSymbols();
        CompletionExpression expr = CompletionExpression.fromCaretPosition(parser, tree, caretTokenIndex);

        return resolver.resolve(expr, symbols, model);
    }

    private void assertResolvesToWithSymbols(String text, int line, int col, String expectedFqcn) {
        Optional<SemanticType> result = resolveAtWithVisibleSymbols(text, line, col);
        assertThat(result)
                .as("Expected type %s at line %d col %d", expectedFqcn, line, col)
                .isPresent();
        assertThat(result.get().resolvedType().describe()).isEqualTo(expectedFqcn);
    }
```

- [ ] **Step 4: Update 5 tests to use assertResolvesToWithSymbols and remove @Disabled**

Remove `@Disabled`, change `assertResolvesTo` to `assertResolvesToWithSymbols` for:
- `rhsLocalPropertyChain` (fix Person constructor)
- `methodReturnType`
- `nullSafeAccess` (fix Person constructor)
- `arrayIndexedAccess`
- `inlineCastSimple` (update test text: add `Object list = new Object();` in do block, adjust line/col)

For `inlineCastSimple`, the updated test text:
```java
    @Test
    void inlineCastSimple() {
        String text = """
                import java.util.ArrayList;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        Object list = new Object();
                        list#ArrayList#.
                """;
        assertResolvesToWithSymbols(text, 8, 25, "java.util.ArrayList");
    }
```

- [ ] **Step 5: Implement CompletionContext.buildVisibleSymbols()**

Add `buildVisibleSymbols()` method to `CompletionContext.java` that:
1. Finds the enclosing rule via `findEnclosingRule(cu)` — checks which `RuleDeclarationContext` span contains `caretTokenIndex`
2. Calls `extractRuleParameters(rule, builder)` — walks `ruleParameterList`
3. Calls `extractOopathBindings(rule, builder)` — walks `boundOopath` nodes for explicit types
4. Calls `extractLocalVariables(rule, builder)` — walks `ruleConsequence → statement → block → blockStatement` for `localVariableDeclaration` nodes before caret

Type resolution via `resolveTypeToSemanticType(typeName)`:
- Check `java.lang.X`, then imports for matching suffix, then try as FQCN
- Use `typeSolver.solveType(fqcn)` → `new ReferenceTypeImpl(decl)` → `SemanticType.value()`
- Handle array types (`String[]`) via `ResolvedArrayType`

Add imports: `ResolvedReferenceTypeDeclaration`, `ReferenceTypeImpl`, `ResolvedArrayType`, plus all needed parser context classes (`BlockContext`, `BlockStatementContext`, `LocalVariableDeclarationContext`, `RuleConsequenceContext`, `StatementContext`, etc.).

- [ ] **Step 6: Run the 5 enabled tests**

Run: `mvn -pl drlx-completion test -Dtest="ExpressionTypeResolverCharacterizationTest#rhsLocalPropertyChain+methodReturnType+nullSafeAccess+arrayIndexedAccess+inlineCastSimple" -Dsurefire.useFile=false`

Expected: All 5 pass.

- [ ] **Step 7: Run full test suite**

Run: `mvn -pl drlx-completion test -Dsurefire.useFile=false`

Expected: No regressions.

- [ ] **Step 8: Commit**

```
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java \
       drlx-completion/src/test/java/org/drools/drlx/domain/Address.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java
git commit -m "feat: buildVisibleSymbols extracts RHS local variables, enable 5 tests (#7)"
```

---

### Task 3: OOPath binding extraction + scope rules (enables 3 tests)

**Files:**
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java`

**Interfaces:**
- Consumes: `CompletionContext.buildVisibleSymbols()` (from Task 2)
- Produces: 3 tests exercising OOPath binding extraction and scope rules

**Dependencies:** Task 2 must be complete (OOPath extraction already implemented in `extractOopathBindings`)

- [ ] **Step 1: Write and enable bindingsFromEarlierPatterns test**

```java
    @Test
    void bindingsFromEarlierPatterns() {
        String text = """
                import org.drools.drlx.domain.Person;

                unit MyUnit;

                rule R1 {
                    Person p : /persons,
                    do { p.
                """;
        assertResolvesToWithSymbols(text, 6, 11, "org.drools.drlx.domain.Person");
    }
```

- [ ] **Step 2: Write and enable noLeakageFromLaterPatterns test**

```java
    @Test
    void noLeakageFromLaterPatterns() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;

                unit MyUnit;

                rule R1 {
                    Person p : /persons,
                    do { p.
                }

                rule R2 {
                    Address a : /addresses,
                    do { a.getCity(); }
                }
                """;
        Optional<SemanticType> result = resolveAtWithVisibleSymbols(text, 7, 11);
        assertThat(result).isPresent();
        assertThat(result.get().resolvedType().describe()).isEqualTo("org.drools.drlx.domain.Person");
    }
```

- [ ] **Step 3: Write and enable shadowedLocalVariables test**

```java
    @Test
    void shadowedLocalVariables() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;

                unit MyUnit;

                rule R1 {
                    Person p : /persons,
                    do {
                        Address p = new Address();
                        p.
                """;
        assertResolvesToWithSymbols(text, 9, 10, "org.drools.drlx.domain.Address");
    }
```

- [ ] **Step 4: Run the 3 tests**

Run: `mvn -pl drlx-completion test -Dtest="ExpressionTypeResolverCharacterizationTest#bindingsFromEarlierPatterns+noLeakageFromLaterPatterns+shadowedLocalVariables" -Dsurefire.useFile=false`

Expected: All 3 pass.

- [ ] **Step 5: Run full test suite and commit**

Run: `mvn -pl drlx-completion test -Dsurefire.useFile=false`

```
git add drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java
git commit -m "test: enable OOPath binding extraction and scope rule tests (#7)"
```

---

### Task 4: Entry-point type inference (enables 1 test)

**Files:**
- Modify: `drlx-completion/pom.xml`
- Create: `drlx-completion/src/test/java/org/drools/drlx/domain/MyUnit.java`
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java`

**Interfaces:**
- Consumes: `unitClassName()`, `imports()`, `java.lang.reflect.ParameterizedType`
- Produces: `var p : /persons` resolves `p` to `Person` when unit class has `DataStore<Person> persons`

**Dependencies:** Task 2 must be complete

- [ ] **Step 1: Add drools-ruleunits-api test dependency**

In `drlx-completion/pom.xml`, add:

```xml
        <dependency>
            <groupId>org.drools</groupId>
            <artifactId>drools-ruleunits-api</artifactId>
            <version>10.2.0</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Create MyUnit.java**

```java
package org.drools.drlx.domain;

import org.drools.ruleunits.api.DataSource;
import org.drools.ruleunits.api.DataStore;

public class MyUnit {

    public DataStore<Person> persons = DataSource.createStore();
}
```

- [ ] **Step 3: Add entry-point type inference to CompletionContext**

In the `collectBoundOopathFromTree` method, handle `var` bindings by adding `inferVarBindingType()` and `resolveEntryPointType()`:
- Get entry-point name from `oopathRoot.identifier(0)`
- Resolve unit class via `unitClassName()` + `resolveToFqcn()`
- Load class, find field matching entry-point name
- Extract `DataSource<T>` / `DataStore<T>` generic type arg via `ParameterizedType`

Add imports: `java.lang.reflect.Field`, `java.lang.reflect.ParameterizedType`

- [ ] **Step 4: Write and enable entryPointTypeInference test**

```java
    @Test
    void entryPointTypeInference() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p.
                """;
        assertResolvesToWithSymbols(text, 7, 11, "org.drools.drlx.domain.Person");
    }
```

- [ ] **Step 5: Install and run**

Run: `mvn -pl drlx-completion -am install -DskipTests`
Run: `mvn -pl drlx-completion test -Dtest="ExpressionTypeResolverCharacterizationTest#entryPointTypeInference" -Dsurefire.useFile=false`

Expected: Passes.

- [ ] **Step 6: Run full test suite and commit**

Run: `mvn -pl drlx-completion test -Dsurefire.useFile=false`

```
git add drlx-completion/pom.xml \
       drlx-completion/src/test/java/org/drools/drlx/domain/MyUnit.java \
       drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java
git commit -m "feat: entry-point type inference via unit class DataStore<T> fields (#7)"
```

---

### Task 5: OOPath chunk type tracking (enables 1 test)

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java`

**Interfaces:**
- Consumes: `resolveEntryPointType()`, OOPath parse tree nodes, `TypeSolver`
- Produces: For `/persons/address[city.]`, adds `Address` properties as visible symbols

**Dependencies:** Task 4 must be complete

For `/persons/address[city.]`:
- Root `persons` → type `Person` (from entry-point inference)
- Chunk `address` → type `Address` (from `Person.getAddress()` return type)
- Caret inside `[city.]` → add `Address` properties (`city` → String) as visible symbols

- [ ] **Step 1: Write and enable nestedOopathChunkConstraint test**

```java
    @Test
    void nestedOopathChunkConstraint() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons/address[city.
                """;
        assertResolvesToWithSymbols(text, 7, 37, "java.lang.String");
    }
```

Expected: **Fails** initially.

- [ ] **Step 2: Add OOPath constraint property extraction to CompletionContext**

Add `extractOopathConstraintProperties(rule, builder)` as the 4th step in `buildVisibleSymbols()`.

Implementation:
- Walk parse tree to find `OopathExpressionContext` containing the caret
- Track type through root + chunks using `resolveEntryPointType()` and `resolvePropertyType()`
- When caret is inside a chunk's constraint bracket, call `addPropertiesAsSymbols()` to add the chunk type's fields and bean properties

Key helpers:
- `resolvePropertyType(ownerType, propertyName)` — finds getter return type or field type
- `addPropertiesAsSymbols(ownerType, builder)` — adds all fields and bean properties of the type

- [ ] **Step 3: Run the test**

Run: `mvn -pl drlx-completion test -Dtest="ExpressionTypeResolverCharacterizationTest#nestedOopathChunkConstraint" -Dsurefire.useFile=false`

Expected: Passes.

- [ ] **Step 4: Run full test suite and commit**

Run: `mvn -pl drlx-completion test -Dsurefire.useFile=false`

```
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java
git commit -m "feat: OOPath chunk type tracking for constraint expression completions (#7)"
```

---

### Task 6: DrlxCompletionHelper integration (enables 2 tests)

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java`

**Interfaces:**
- Consumes: `CompletionContext.buildVisibleSymbols()` (from Tasks 2-5)
- Produces: End-to-end completion integration with populated VisibleSymbols

**Dependencies:** Tasks 1-5 must be complete

- [ ] **Step 1: Wire buildVisibleSymbols into DrlxCompletionHelper.resolveDotAccess()**

Replace line 100:
```java
VisibleSymbols symbols = ctx.buildVisibleSymbols();
Optional<SemanticType> resolved = resolver.resolve(expression, symbols, model);
```

- [ ] **Step 2: Update incompleteRule_inlineCast test**

Remove `@Disabled`. Add `Object list = new Object();` declaration in test text so `list` is a declared variable. Adjust line/col for caret position.

- [ ] **Step 3: Remove @Disabled from incompleteRule_PropertyAccessor**

Person constructor already fixed in Task 2.

- [ ] **Step 4: Run the 2 tests**

Run: `mvn -pl drlx-completion test -Dtest="DrlxCompletionHelperIncompleteCodeTest#incompleteRule_inlineCast+incompleteRule_PropertyAccessor" -Dsurefire.useFile=false`

Expected: Both pass.

- [ ] **Step 5: Verify remaining disabled test count**

```
grep -c "@Disabled" drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java
grep -c "@Disabled" drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java
```

Expected: 1 (realMavenWorkspaceClasses #6), 0.

- [ ] **Step 6: Run full test suite and commit**

Run: `mvn -pl drlx-completion test -Dsurefire.useFile=false`

```
git add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java
git commit -m "feat: wire VisibleSymbols into DrlxCompletionHelper, enable 2 integration tests (#7)"
```

---

## Summary of Test Impact

| Test | Enabled In | Category |
|---|---|---|
| rhsLocalPropertyChain | Task 2 | RHS local vars |
| methodReturnType | Task 2 | RHS local vars |
| nullSafeAccess | Task 2 | RHS local vars |
| arrayIndexedAccess | Task 2 | RHS local vars |
| inlineCastSimple | Task 2 | RHS local vars |
| bindingsFromEarlierPatterns | Task 3 | OOPath bindings |
| noLeakageFromLaterPatterns | Task 3 | Scope rules |
| shadowedLocalVariables | Task 3 | Scope rules |
| entryPointTypeInference | Task 4 | Entry-point types |
| nestedOopathChunkConstraint | Task 5 | OOPath chunks |
| incompleteRule_inlineCast | Task 6 | Integration |
| incompleteRule_PropertyAccessor | Task 6 | Integration |

**After all tasks:** Only `realMavenWorkspaceClasses` (Issue #6) remains disabled.

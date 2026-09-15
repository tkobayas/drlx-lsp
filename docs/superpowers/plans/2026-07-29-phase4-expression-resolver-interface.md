# Phase 4: Expression Resolver Interface — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `ExpressionTypeResolver` interface with four domain types, extract `MemberCompletionProvider`, and wrap the existing tolerant visitor as a baseline adapter — zero behavior change.

**Architecture:** Define `SemanticType`, `CompletionExpression`, `VisibleSymbols`, `WorkspaceTypes` in the `semantic` package. Create `TolerantVisitorTypeResolver` that adapts the existing `resolveDotAccess()` logic behind the `ExpressionTypeResolver` interface. Extract type-to-completion-item conversion into `MemberCompletionProvider`. Rewire `DrlxCompletionHelper` to use the new components.

**Tech Stack:** Java 21, JavaParser (symbol resolution), ANTLR4-C3, JUnit 5, AssertJ

## Global Constraints

- All 67 existing tests must pass at every commit — zero behavior change
- All new types in package `org.drools.drlx.completion.semantic`
- Source root: `drlx-completion/src/main/java/`
- Test root: `drlx-completion/src/test/java/`
- Build/test command: `mvn -pl drlx-completion test` (requires `drlx-parser` installed)
- `createCompletionItem()` remains accessible in `DrlxCompletionHelper` (used by keyword completion and tests)

---

### Task 1: SemanticType and WorkspaceTypes

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/SemanticType.java`
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceTypes.java`
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModel.java`
- Test: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/SemanticTypeTest.java`

**Interfaces:**
- Consumes: JavaParser's `com.github.javaparser.resolution.types.ResolvedType`, `com.github.javaparser.resolution.TypeSolver`
- Produces: `SemanticType` (used by Task 3 `TolerantVisitorTypeResolver`, Task 4 `MemberCompletionProvider`), `WorkspaceTypes` (used by Task 2 `ExpressionTypeResolver` interface, Task 3 `TolerantVisitorTypeResolver`)

- [ ] **Step 1: Write the failing tests for SemanticType**

```java
package org.drools.drlx.completion.semantic;

import com.github.javaparser.resolution.types.ResolvedPrimitiveType;
import com.github.javaparser.resolution.types.ResolvedArrayType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticTypeTest {

    private final ReflectionTypeSolver solver = new ReflectionTypeSolver(false);

    @Test
    void valueFromReferenceType() {
        var resolved = solver.solveType("java.lang.String").asReferenceType();
        SemanticType type = SemanticType.value(resolved);

        assertThat(type.category()).isEqualTo(SemanticType.Category.VALUE);
        assertThat(type.resolvedType()).isSameAs(resolved);
        assertThat(type.isReferenceType()).isTrue();
        assertThat(type.isArray()).isFalse();
    }

    @Test
    void valueFromArrayType() {
        var elementType = solver.solveType("java.lang.String").asReferenceType();
        var arrayType = new ResolvedArrayType(elementType);
        SemanticType type = SemanticType.value(arrayType);

        assertThat(type.category()).isEqualTo(SemanticType.Category.ARRAY);
        assertThat(type.isArray()).isTrue();
        assertThat(type.isReferenceType()).isFalse();
    }

    @Test
    void valueFromPrimitiveType() {
        SemanticType type = SemanticType.value(ResolvedPrimitiveType.INT);

        assertThat(type.category()).isEqualTo(SemanticType.Category.PRIMITIVE);
        assertThat(type.isReferenceType()).isFalse();
        assertThat(type.isArray()).isFalse();
    }

    @Test
    void typeRef() {
        var resolved = solver.solveType("java.lang.System").asReferenceType();
        SemanticType type = SemanticType.typeRef(resolved);

        assertThat(type.category()).isEqualTo(SemanticType.Category.TYPE);
        assertThat(type.resolvedType()).isSameAs(resolved);
    }

    @Test
    void unresolved() {
        SemanticType type = SemanticType.unresolved();

        assertThat(type.category()).isEqualTo(SemanticType.Category.UNRESOLVED);
        assertThat(type.resolvedType()).isNull();
        assertThat(type.isReferenceType()).isFalse();
        assertThat(type.isArray()).isFalse();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl drlx-completion test -Dtest=SemanticTypeTest -DfailIfNoTests=false`
Expected: Compilation failure — `SemanticType` class does not exist

- [ ] **Step 3: Implement SemanticType**

```java
package org.drools.drlx.completion.semantic;

import com.github.javaparser.resolution.types.ResolvedType;

/**
 * A resolved type that preserves generic type arguments and expression category.
 *
 * <p>Unlike {@code Class<?>}, which erases generics, this type retains the
 * information needed to resolve {@code DataSource<Person>} fields and generic
 * method return types. It also distinguishes whether the resolved expression
 * represents a type (where only static members apply), a value (where instance
 * members apply), an array, a primitive, or an unresolved/ambiguous reference.
 *
 * <p>Wraps JavaParser's {@code ResolvedType} internally but isolates consumers
 * from the JavaParser API so the resolver implementation can change independently.
 */
public class SemanticType {

    public enum Category { TYPE, VALUE, ARRAY, PRIMITIVE, UNRESOLVED }

    private final ResolvedType resolvedType;
    private final Category category;

    private SemanticType(ResolvedType resolvedType, Category category) {
        this.resolvedType = resolvedType;
        this.category = category;
    }

    public ResolvedType resolvedType() {
        return resolvedType;
    }

    public Category category() {
        return category;
    }

    public boolean isReferenceType() {
        return resolvedType != null && resolvedType.isReferenceType();
    }

    public boolean isArray() {
        return resolvedType != null && resolvedType.isArray();
    }

    public static SemanticType value(ResolvedType type) {
        Category cat;
        if (type.isArray()) {
            cat = Category.ARRAY;
        } else if (type.isPrimitive()) {
            cat = Category.PRIMITIVE;
        } else {
            cat = Category.VALUE;
        }
        return new SemanticType(type, cat);
    }

    public static SemanticType typeRef(ResolvedType type) {
        return new SemanticType(type, Category.TYPE);
    }

    public static SemanticType unresolved() {
        return new SemanticType(null, Category.UNRESOLVED);
    }
}
```

- [ ] **Step 4: Create WorkspaceTypes interface**

```java
package org.drools.drlx.completion.semantic;

import com.github.javaparser.resolution.TypeSolver;

/**
 * Provides type resolution against the workspace project's classpath and sources.
 *
 * <p>Resolves fully-qualified and simple class names using the project's Maven
 * dependencies, build output ({@code target/classes}), source roots, and the
 * current compilation unit's imports. Uses {@code Class.forName(fqcn, false, loader)}
 * to avoid executing user static initializers inside the language server.
 *
 * <p>Also provides DRLX-specific resolution: the unit class, its
 * {@code DataSource<T>}/{@code DataStore<T>} fields mapped to entry-point names,
 * and member enumeration that distinguishes static from instance members and
 * preserves generic type parameters.
 *
 * <p>For the baseline adapter, this delegates to
 * {@link WorkspaceSemanticModel#typeSolver()}.
 */
public interface WorkspaceTypes {

    TypeSolver typeSolver();
}
```

- [ ] **Step 5: Make WorkspaceSemanticModel implement WorkspaceTypes**

In `WorkspaceSemanticModel.java`, change the class declaration from:

```java
public class WorkspaceSemanticModel {
```

to:

```java
public class WorkspaceSemanticModel implements WorkspaceTypes {
```

The existing `typeSolver()` method already satisfies the interface contract. No other changes needed.

- [ ] **Step 6: Run all tests**

Run: `mvn -pl drlx-completion test`
Expected: All tests pass (67 existing + 5 new SemanticType tests)

- [ ] **Step 7: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/SemanticType.java
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceTypes.java
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModel.java
git add drlx-completion/src/test/java/org/drools/drlx/completion/semantic/SemanticTypeTest.java
git commit -m "feat: add SemanticType, WorkspaceTypes, and implement in WorkspaceSemanticModel"
```

---

### Task 2: CompletionExpression, VisibleSymbols, and ExpressionTypeResolver interface

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionExpression.java`
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/VisibleSymbols.java`
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/ExpressionTypeResolver.java`
- Test: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/CompletionExpressionTest.java`

**Interfaces:**
- Consumes: `SemanticType` (from Task 1), `org.antlr.v4.runtime.tree.ParseTree`
- Produces: `CompletionExpression` (used by Task 3, Task 5), `VisibleSymbols` (used by Task 3, Task 5), `ExpressionTypeResolver` (used by Task 3, Task 5)

- [ ] **Step 1: Write the failing tests for CompletionExpression**

```java
package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionExpressionTest {

    @Test
    void fromCaretPositionNormal() {
        // "System." — caret at index 2 (after DOT), scope at index 0 (System)
        CompletionExpression expr = CompletionExpression.fromCaretPosition(
                parse("System."), 2);

        assertThat(expr.caretTokenIndex()).isEqualTo(2);
        assertThat(expr.scopeTokenIndex()).isEqualTo(0);
        assertThat(expr.parseTree()).isNotNull();
    }

    @Test
    void fromCaretPositionAtZero() {
        CompletionExpression expr = CompletionExpression.fromCaretPosition(
                parse("x"), 0);

        assertThat(expr.caretTokenIndex()).isEqualTo(0);
        assertThat(expr.scopeTokenIndex()).isEqualTo(-2);
    }

    @Test
    void fromCaretPositionAtOne() {
        CompletionExpression expr = CompletionExpression.fromCaretPosition(
                parse(".x"), 1);

        assertThat(expr.caretTokenIndex()).isEqualTo(1);
        assertThat(expr.scopeTokenIndex()).isEqualTo(-1);
    }

    @Test
    void visibleSymbolsEmptyReturnsEmptyOnLookup() {
        VisibleSymbols symbols = VisibleSymbols.empty();
        assertThat(symbols.lookup("anything")).isEmpty();
    }

    private ParseTree parse(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        return parser.drlxStart();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl drlx-completion test -Dtest=CompletionExpressionTest -DfailIfNoTests=false`
Expected: Compilation failure — `CompletionExpression` class does not exist

- [ ] **Step 3: Implement CompletionExpression**

```java
package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.tree.ParseTree;

/**
 * An extracted expression fragment surrounding the caret, repaired for parsing.
 *
 * <p>Constructed by locating a balanced expression boundary via backward token
 * walking, then injecting a sentinel identifier after the incomplete member
 * operator (e.g. {@code person.address.} becomes {@code person.address.__sentinel__}).
 * The repaired text is reparsed using the DRLX expression grammar to produce
 * a structured AST rather than a raw token sequence.
 *
 * <p>The sentinel's position in the resulting AST marks the scope whose members
 * should be offered as completions. Token walking locates the boundary;
 * the grammar interprets method calls, inline casts, literals, null-safe access,
 * indexing, and other expression forms.
 *
 * <p>For the baseline adapter, this wraps the raw parse tree and caret position
 * that {@code resolveDotAccess} currently receives.
 */
public class CompletionExpression {

    private final ParseTree parseTree;
    private final int caretTokenIndex;
    private final int scopeTokenIndex;

    private CompletionExpression(ParseTree parseTree, int caretTokenIndex, int scopeTokenIndex) {
        this.parseTree = parseTree;
        this.caretTokenIndex = caretTokenIndex;
        this.scopeTokenIndex = scopeTokenIndex;
    }

    public static CompletionExpression fromCaretPosition(ParseTree tree, int caretTokenIndex) {
        // scopeTokenIndex = caretTokenIndex - 2
        // (caret token, minus DOT, minus one = the scope expression's last token)
        return new CompletionExpression(tree, caretTokenIndex, caretTokenIndex - 2);
    }

    public ParseTree parseTree() {
        return parseTree;
    }

    public int caretTokenIndex() {
        return caretTokenIndex;
    }

    public int scopeTokenIndex() {
        return scopeTokenIndex;
    }
}
```

- [ ] **Step 4: Implement VisibleSymbols**

```java
package org.drools.drlx.completion.semantic;

import java.util.Optional;

/**
 * The set of named symbols visible at a specific caret position within a rule.
 *
 * <p>Scope rules enforce: current compilation unit only, current rule only,
 * declarations appearing before the caret, nearest declaration when names are
 * shadowed, and nested block scoping. This prevents cross-rule binding leakage
 * and ensures that later declarations do not appear in earlier completions.
 *
 * <p>Symbol sources include: rule parameters, OOPath pattern bindings (with
 * types inferred from entry-point generic arguments or inline casts),
 * constraint bindings, accumulate results, and consequence local variables.
 * Each symbol maps to a {@link SemanticType} representing its resolved type.
 *
 * <p>For the baseline adapter, this is empty — the existing tolerant visitor
 * does not perform scope-aware binding lookup.
 */
public class VisibleSymbols {

    private static final VisibleSymbols EMPTY = new VisibleSymbols();

    public static VisibleSymbols empty() {
        return EMPTY;
    }

    public Optional<SemanticType> lookup(String name) {
        return Optional.empty();
    }
}
```

- [ ] **Step 5: Create ExpressionTypeResolver interface**

```java
package org.drools.drlx.completion.semantic;

import java.util.Optional;

public interface ExpressionTypeResolver {

    Optional<SemanticType> resolve(
            CompletionExpression expression,
            VisibleSymbols symbols,
            WorkspaceTypes workspaceTypes);
}
```

- [ ] **Step 6: Run all tests**

Run: `mvn -pl drlx-completion test`
Expected: All tests pass (67 existing + 5 SemanticType + 4 CompletionExpression/VisibleSymbols)

- [ ] **Step 7: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionExpression.java
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/VisibleSymbols.java
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/ExpressionTypeResolver.java
git add drlx-completion/src/test/java/org/drools/drlx/completion/semantic/CompletionExpressionTest.java
git commit -m "feat: add CompletionExpression, VisibleSymbols, and ExpressionTypeResolver interface"
```

---

### Task 3: TolerantVisitorTypeResolver baseline adapter

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/TolerantVisitorTypeResolver.java`
- Test: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/TolerantVisitorTypeResolverTest.java`

**Interfaces:**
- Consumes: `ExpressionTypeResolver` (from Task 2), `CompletionExpression` (from Task 2), `VisibleSymbols` (from Task 2), `WorkspaceTypes` (from Task 1), `SemanticType` (from Task 1)
- Produces: `TolerantVisitorTypeResolver` (used by Task 5 wiring)

- [ ] **Step 1: Write the failing tests**

These tests verify that the resolver produces the same types as the current inline `resolveDotAccess()` logic. Use real DRLX text parsed by the parser, same as the existing DOT_ACCESS tests.

```java
package org.drools.drlx.completion.semantic;

import java.util.Optional;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TolerantVisitorTypeResolverTest {

    private final WorkspaceSemanticModel model =
            new WorkspaceSemanticModel(new CurrentClassloaderProvider());
    private final TolerantVisitorTypeResolver resolver = new TolerantVisitorTypeResolver();

    @Test
    void resolvesSystemDot() {
        // "System." — caret after the dot
        String text = """
                unit MyUnit;
                rule R1 {
                    var a : /as,
                    do { System.
                """;
        Optional<SemanticType> result = resolveAt(text, 4, 16);

        assertThat(result).isPresent();
        assertThat(result.get().isReferenceType()).isTrue();
        assertThat(result.get().resolvedType().describe()).isEqualTo("java.lang.System");
    }

    @Test
    void resolvesSystemOutDot() {
        String text = """
                unit MyUnit;
                rule R1 {
                    var a : /as,
                    do { System.out.
                """;
        Optional<SemanticType> result = resolveAt(text, 4, 20);

        assertThat(result).isPresent();
        assertThat(result.get().isReferenceType()).isTrue();
        assertThat(result.get().resolvedType().describe()).isEqualTo("java.io.PrintStream");
    }

    @Test
    void returnsEmptyForUnresolvableScope() {
        // Dot after a nonexistent identifier
        String text = """
                unit MyUnit;
                rule R1 {
                    var a : /as,
                    do { nonExistent.
                """;
        Optional<SemanticType> result = resolveAt(text, 4, 21);

        // May return empty or throw — the adapter should handle gracefully
        // (existing code falls through to IDENTIFIER placeholder)
    }

    private Optional<SemanticType> resolveAt(String text, int line, int col) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        ParseTree tree = parser.drlxStart();

        int caretTokenIndex = computeTokenIndex(parser, line + 1, col);
        CompletionExpression expr = CompletionExpression.fromCaretPosition(tree, caretTokenIndex);

        return resolver.resolve(expr, VisibleSymbols.empty(), model);
    }

    private int computeTokenIndex(DrlxParser parser, int row, int col) {
        CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();
        int tokenIndex = 0;
        for (Token token : tokens.getTokens()) {
            if (token.getLine() > row || (token.getLine() == row && token.getCharPositionInLine() >= col)) {
                break;
            }
            tokenIndex++;
        }
        return tokenIndex;
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl drlx-completion test -Dtest=TolerantVisitorTypeResolverTest -DfailIfNoTests=false`
Expected: Compilation failure — `TolerantVisitorTypeResolver` class does not exist

- [ ] **Step 3: Implement TolerantVisitorTypeResolver**

Extract the logic from `DrlxCompletionHelper.resolveDotAccess()` lines 109-122:

```java
package org.drools.drlx.completion.semantic;

import java.util.Map;
import java.util.Optional;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import org.drools.drlx.parser.TolerantDrlxToJavaParserVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TolerantVisitorTypeResolver implements ExpressionTypeResolver {

    private static final Logger logger = LoggerFactory.getLogger(TolerantVisitorTypeResolver.class);

    @Override
    public Optional<SemanticType> resolve(
            CompletionExpression expression,
            VisibleSymbols symbols,
            WorkspaceTypes workspaceTypes) {

        int scopeTokenIndex = expression.scopeTokenIndex();
        if (scopeTokenIndex < 0) {
            return Optional.empty();
        }

        TolerantDrlxToJavaParserVisitor visitor = new TolerantDrlxToJavaParserVisitor();
        CompilationUnit compilationUnit = (CompilationUnit) visitor.visit(expression.parseTree());

        JavaSymbolSolver solver = new JavaSymbolSolver(workspaceTypes.typeSolver());
        solver.inject(compilationUnit);

        Map<Integer, Node> tokenIdJPNodeMap = visitor.getTokenIdJPNodeMap();
        Expression scopeNode = (Expression) tokenIdJPNodeMap.get(scopeTokenIndex);
        if (scopeNode == null) {
            logger.info("scopeNode is null");
            return Optional.empty();
        }

        logger.info("scopeNode: {} , text => [{}]", scopeNode.getClass(), scopeNode);
        try {
            ResolvedType resolvedType = scopeNode.calculateResolvedType();
            return Optional.of(SemanticType.value(resolvedType));
        } catch (Exception e) {
            logger.info("Failed to resolve type for scope node: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: Run all tests**

Run: `mvn -pl drlx-completion test`
Expected: All tests pass (67 existing + 5 SemanticType + 4 CompletionExpression + 2-3 resolver tests)

- [ ] **Step 5: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/TolerantVisitorTypeResolver.java
git add drlx-completion/src/test/java/org/drools/drlx/completion/semantic/TolerantVisitorTypeResolverTest.java
git commit -m "feat: add TolerantVisitorTypeResolver baseline adapter"
```

---

### Task 4: MemberCompletionProvider

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/MemberCompletionProvider.java`
- Test: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/MemberCompletionProviderTest.java`

**Interfaces:**
- Consumes: `SemanticType` (from Task 1)
- Produces: `MemberCompletionProvider.completions(SemanticType)` → `List<CompletionItem>` (used by Task 5 wiring)

- [ ] **Step 1: Write the failing tests**

These tests verify the extracted member enumeration logic produces the same results as the existing `createTypeBasedCompletions()`.

```java
package org.drools.drlx.completion.semantic;

import java.util.List;

import com.github.javaparser.resolution.types.ResolvedArrayType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberCompletionProviderTest {

    private final ReflectionTypeSolver solver = new ReflectionTypeSolver(false);
    private final MemberCompletionProvider provider = new MemberCompletionProvider();

    @Test
    void referenceTypeShowsFieldsAndMethods() {
        var resolved = solver.solveType("java.lang.System").asReferenceType();
        SemanticType type = SemanticType.value(resolved);

        List<CompletionItem> items = provider.completions(type);
        List<String> labels = items.stream().map(CompletionItem::getInsertText).toList();

        assertThat(labels).contains("out", "in", "err");  // fields
        assertThat(labels).contains("gc", "exit");         // methods
    }

    @Test
    void referenceTypeIncludesPropertyAccess() {
        var resolved = solver.solveType("java.lang.String").asReferenceType();
        SemanticType type = SemanticType.value(resolved);

        List<CompletionItem> items = provider.completions(type);
        List<String> labels = items.stream().map(CompletionItem::getInsertText).toList();

        // String has getBytes() -> "bytes" property
        assertThat(labels).contains("bytes");
    }

    @Test
    void arrayTypeShowsLength() {
        var elementType = solver.solveType("java.lang.String").asReferenceType();
        var arrayType = new ResolvedArrayType(elementType);
        SemanticType type = SemanticType.value(arrayType);

        List<CompletionItem> items = provider.completions(type);
        List<String> labels = items.stream().map(CompletionItem::getInsertText).toList();

        assertThat(labels).containsExactly("length");
    }

    @Test
    void unresolvedTypeReturnsEmpty() {
        SemanticType type = SemanticType.unresolved();

        List<CompletionItem> items = provider.completions(type);

        assertThat(items).isEmpty();
    }

    @Test
    void fieldsHaveCorrectKind() {
        var resolved = solver.solveType("java.lang.System").asReferenceType();
        SemanticType type = SemanticType.value(resolved);

        List<CompletionItem> items = provider.completions(type);
        CompletionItem outField = items.stream()
                .filter(i -> "out".equals(i.getInsertText()))
                .findFirst().orElseThrow();

        assertThat(outField.getKind()).isEqualTo(CompletionItemKind.Field);
        assertThat(outField.getDetail()).isEqualTo("java.io.PrintStream");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl drlx-completion test -Dtest=MemberCompletionProviderTest -DfailIfNoTests=false`
Expected: Compilation failure — `MemberCompletionProvider` class does not exist

- [ ] **Step 3: Implement MemberCompletionProvider**

Extract from `DrlxCompletionHelper` lines 172-246 (`createTypeBasedCompletions`, `addDirectPropertyAccess`, `isAccessible` methods):

```java
package org.drools.drlx.completion.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionFieldDeclaration;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionMethodDeclaration;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import static org.drools.drlx.completion.DrlxCompletionHelper.createCompletionItem;

public class MemberCompletionProvider {

    public List<CompletionItem> completions(SemanticType type) {
        if (type.resolvedType() == null) {
            return List.of();
        }

        List<CompletionItem> items = new ArrayList<>();

        try {
            ResolvedType resolvedType = type.resolvedType();
            if (resolvedType.isReferenceType()) {
                ResolvedReferenceType referenceType = resolvedType.asReferenceType();

                for (ResolvedFieldDeclaration field : referenceType.getAllFieldsVisibleToInheritors()) {
                    if (isAccessible(field)) {
                        CompletionItem item = createCompletionItem(field.getName(), CompletionItemKind.Field);
                        item.setDetail(field.getType().describe());
                        items.add(item);
                    }
                }

                referenceType.getAllMethods().stream()
                        .filter(method -> isAccessible(method))
                        .filter(method -> !method.getName().startsWith("$"))
                        .map(method -> method.getName())
                        .distinct()
                        .forEach(methodName -> items.add(createCompletionItem(methodName, CompletionItemKind.Method)));

                addDirectPropertyAccess(items);

            } else if (resolvedType.isArray()) {
                items.add(createCompletionItem("length", CompletionItemKind.Field));
            }
        } catch (Exception e) {
            System.err.println("Error resolving type members: " + e.getMessage());
        }

        return items;
    }

    private void addDirectPropertyAccess(List<CompletionItem> items) {
        Set<CompletionItem> propertyNames = items.stream()
                .filter(item -> item.getKind() == CompletionItemKind.Method)
                .map(CompletionItem::getInsertText)
                .filter(name -> name.startsWith("get") || name.startsWith("is"))
                .map(name -> {
                    if (name.startsWith("get")) {
                        return name.substring(3, 4).toLowerCase() + name.substring(4);
                    } else {
                        return name.substring(2, 3).toLowerCase() + name.substring(3);
                    }
                })
                .map(propName -> createCompletionItem(propName, CompletionItemKind.Field))
                .collect(Collectors.toSet());

        items.addAll(propertyNames);
    }

    private boolean isAccessible(ResolvedFieldDeclaration field) {
        try {
            if (field instanceof ReflectionFieldDeclaration reflectionField) {
                return reflectionField.accessSpecifier() == AccessSpecifier.PUBLIC;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isAccessible(ResolvedMethodDeclaration method) {
        try {
            if (method instanceof ReflectionMethodDeclaration reflectionMethod) {
                return reflectionMethod.accessSpecifier() == AccessSpecifier.PUBLIC;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }
}
```

Note: The MVEL-forked JavaParser's `getAllMethods()` returns `List<ResolvedMethodDeclaration>` (not `Set<MethodUsage>` as in upstream JavaParser). The code above matches the original exactly.

- [ ] **Step 4: Run all tests**

Run: `mvn -pl drlx-completion test`
Expected: All tests pass (67 existing + 5 SemanticType + 4 CompletionExpression + 2-3 resolver + 5 provider)

- [ ] **Step 5: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/MemberCompletionProvider.java
git add drlx-completion/src/test/java/org/drools/drlx/completion/semantic/MemberCompletionProviderTest.java
git commit -m "feat: extract MemberCompletionProvider from DrlxCompletionHelper"
```

---

### Task 5: Rewire DrlxCompletionHelper and update all call sites

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java`
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java:40-42`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java:17-18`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java:17-18`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperNewConstructsTest.java:26-27`

**Interfaces:**
- Consumes: `ExpressionTypeResolver` (from Task 2), `MemberCompletionProvider` (from Task 4), `CompletionExpression` (from Task 2), `VisibleSymbols` (from Task 2), `WorkspaceTypes` (from Task 1), `SemanticType` (from Task 1), `TolerantVisitorTypeResolver` (from Task 3)
- Produces: Refactored `DrlxCompletionHelper` with new constructor and DOT_ACCESS flow

- [ ] **Step 1: Change DrlxCompletionHelper constructor and fields**

In `DrlxCompletionHelper.java`, replace the constructor and fields (lines 46-50):

Old:
```java
    private final WorkspaceSemanticModel model;

    public DrlxCompletionHelper(WorkspaceSemanticModel model) {
        this.model = model;
    }
```

New:
```java
    private final WorkspaceSemanticModel model;
    private final ExpressionTypeResolver resolver;
    private final MemberCompletionProvider memberProvider;

    public DrlxCompletionHelper(WorkspaceSemanticModel model,
                                ExpressionTypeResolver resolver,
                                MemberCompletionProvider memberProvider) {
        this.model = model;
        this.resolver = resolver;
        this.memberProvider = memberProvider;
    }
```

Add imports at the top of the file:
```java
import org.drools.drlx.completion.semantic.CompletionExpression;
import org.drools.drlx.completion.semantic.ExpressionTypeResolver;
import org.drools.drlx.completion.semantic.MemberCompletionProvider;
import org.drools.drlx.completion.semantic.VisibleSymbols;
```

- [ ] **Step 2: Replace resolveDotAccess with the new flow**

Replace the `resolveDotAccess` method (lines 98-129) and the `createSemanticCompletions` switch case (lines 91-96):

Old `createSemanticCompletions`:
```java
    private List<CompletionItem> createSemanticCompletions(CompletionSite site, CompletionContext ctx) {
        return switch (site) {
            case DOT_ACCESS -> resolveDotAccess(ctx);
            default -> List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
        };
    }
```

New `createSemanticCompletions`:
```java
    private List<CompletionItem> createSemanticCompletions(CompletionSite site, CompletionContext ctx) {
        return switch (site) {
            case DOT_ACCESS -> resolveDotAccess(ctx);
            default -> List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
        };
    }

```

Old `resolveDotAccess`:
```java
    private List<CompletionItem> resolveDotAccess(CompletionContext ctx) {
        List<CompletionItem> semanticItems = new ArrayList<>();

        int previousTokenIndex = ctx.caretTokenIndex() - 1;
        if (previousTokenIndex < 0) {
            semanticItems.add(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
            return semanticItems;
        }

        int scopeTokenIndex = previousTokenIndex - 1;

        TolerantDrlxToJavaParserVisitor visitor = new TolerantDrlxToJavaParserVisitor();
        CompilationUnit compilationUnit = (CompilationUnit) visitor.visit(ctx.parseTree());

        JavaSymbolSolver solver = new JavaSymbolSolver(ctx.typeSolver());
        solver.inject(compilationUnit);

        Map<Integer, Node> tokenIdJPNodeMap = visitor.getTokenIdJPNodeMap();
        Expression scopeNode = (Expression) tokenIdJPNodeMap.get(scopeTokenIndex);
        if (scopeNode == null) {
            logger.info("scopeNode is null");
        } else {
            logger.info("scopeNode: " + scopeNode.getClass() + " , text => [" + scopeNode.toString() + "]");
            ResolvedType resolvedType = scopeNode.calculateResolvedType();
            semanticItems.addAll(createTypeBasedCompletions(resolvedType));
        }

        if (semanticItems.isEmpty()) {
            semanticItems.add(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
        }
        return semanticItems;
    }
```

New `resolveDotAccess`:
```java
    private List<CompletionItem> resolveDotAccess(CompletionContext ctx) {
        CompletionExpression expression = CompletionExpression.fromCaretPosition(
                ctx.parseTree(), ctx.caretTokenIndex());

        Optional<SemanticType> resolved = resolver.resolve(expression, VisibleSymbols.empty(), model);

        if (resolved.isPresent()) {
            List<CompletionItem> items = memberProvider.completions(resolved.get());
            if (!items.isEmpty()) {
                return items;
            }
        }

        return List.of(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
    }
```

Add import:
```java
import java.util.Optional;
import org.drools.drlx.completion.semantic.SemanticType;
```

- [ ] **Step 3: Remove extracted methods from DrlxCompletionHelper**

Delete the following methods that are now in `MemberCompletionProvider` (and no longer called from `DrlxCompletionHelper`):

- `createTypeBasedCompletions()` (lines 172-204)
- `addDirectPropertyAccess()` (lines 206-222)
- `isAccessible(ResolvedFieldDeclaration)` (lines 224-234)
- `isAccessible(ResolvedMethodDeclaration)` (lines 236-246)

Remove now-unused imports:
```java
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionFieldDeclaration;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionMethodDeclaration;
import org.drools.drlx.parser.TolerantDrlxToJavaParserVisitor;
import java.util.Map;
import java.util.stream.Collectors;
```

Verify that these imports are still needed and keep them:
```java
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
```

- [ ] **Step 4: Update DrlxLspDocumentService constructor**

In `DrlxLspDocumentService.java`, change lines 40-42:

Old:
```java
    public DrlxLspDocumentService(DrlxLspServer server, WorkspaceSemanticModel model) {
        this.server = server;
        this.completionHelper = new DrlxCompletionHelper(model);
    }
```

New:
```java
    public DrlxLspDocumentService(DrlxLspServer server, WorkspaceSemanticModel model) {
        this.server = server;
        this.completionHelper = new DrlxCompletionHelper(
                model,
                new TolerantVisitorTypeResolver(),
                new MemberCompletionProvider());
    }
```

Add imports:
```java
import org.drools.drlx.completion.semantic.TolerantVisitorTypeResolver;
import org.drools.drlx.completion.semantic.MemberCompletionProvider;
```

- [ ] **Step 5: Update test helper construction — DrlxCompletionHelperTest**

In `DrlxCompletionHelperTest.java`, replace lines 17-18:

Old:
```java
    private final DrlxCompletionHelper helper = new DrlxCompletionHelper(
            new WorkspaceSemanticModel(new CurrentClassloaderProvider()));
```

New:
```java
    private final DrlxCompletionHelper helper = new DrlxCompletionHelper(
            new WorkspaceSemanticModel(new CurrentClassloaderProvider()),
            new TolerantVisitorTypeResolver(),
            new MemberCompletionProvider());
```

Add imports:
```java
import org.drools.drlx.completion.semantic.TolerantVisitorTypeResolver;
import org.drools.drlx.completion.semantic.MemberCompletionProvider;
```

- [ ] **Step 6: Update test helper construction — DrlxCompletionHelperIncompleteCodeTest**

In `DrlxCompletionHelperIncompleteCodeTest.java`, replace lines 17-18:

Old:
```java
    private final DrlxCompletionHelper helper = new DrlxCompletionHelper(
            new WorkspaceSemanticModel(new CurrentClassloaderProvider()));
```

New:
```java
    private final DrlxCompletionHelper helper = new DrlxCompletionHelper(
            new WorkspaceSemanticModel(new CurrentClassloaderProvider()),
            new TolerantVisitorTypeResolver(),
            new MemberCompletionProvider());
```

Add imports:
```java
import org.drools.drlx.completion.semantic.TolerantVisitorTypeResolver;
import org.drools.drlx.completion.semantic.MemberCompletionProvider;
```

- [ ] **Step 7: Update test helper construction — DrlxCompletionHelperNewConstructsTest**

In `DrlxCompletionHelperNewConstructsTest.java`, replace lines 26-27:

Old:
```java
    private final DrlxCompletionHelper helper = new DrlxCompletionHelper(
            new WorkspaceSemanticModel(new CurrentClassloaderProvider()));
```

New:
```java
    private final DrlxCompletionHelper helper = new DrlxCompletionHelper(
            new WorkspaceSemanticModel(new CurrentClassloaderProvider()),
            new TolerantVisitorTypeResolver(),
            new MemberCompletionProvider());
```

Add imports:
```java
import org.drools.drlx.completion.semantic.TolerantVisitorTypeResolver;
import org.drools.drlx.completion.semantic.MemberCompletionProvider;
```

- [ ] **Step 8: Run all tests**

Run: `mvn -pl drlx-completion test`
Expected: All 67 existing tests pass with zero behavior change, plus all new unit tests pass.

Also run LSP server module to verify compilation:
Run: `mvn -pl drlx-lsp-server compile`
Expected: Compiles successfully

- [ ] **Step 9: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java
git add drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java
git add drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java
git add drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java
git add drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperNewConstructsTest.java
git commit -m "refactor: rewire DrlxCompletionHelper to use ExpressionTypeResolver and MemberCompletionProvider"
```

# Phase 4: Expression Resolver Interface — Design Spec

## Goal

Define `ExpressionTypeResolver` interface with four supporting domain types
(`SemanticType`, `CompletionExpression`, `VisibleSymbols`, `WorkspaceTypes`).
Extract `MemberCompletionProvider` from `DrlxCompletionHelper`. Adapt the
existing tolerant visitor behind the interface as a baseline. No behavior change.

## Scope

- Interface + baseline adapter only
- Replacement implementation (sentinel injection / reparse) deferred to a later phase
- Baseline adapter accepts new types at interface boundary but internally bypasses them

## New types

All in `org.drools.drlx.completion.semantic`.

### SemanticType

```java
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

    enum Category { TYPE, VALUE, ARRAY, PRIMITIVE, UNRESOLVED }

    private final ResolvedType resolvedType;  // nullable for UNRESOLVED
    private final Category category;

    // Public API
    public ResolvedType resolvedType();
    public Category category();
    public boolean isReferenceType();
    public boolean isArray();

    // Factory methods
    static SemanticType value(ResolvedType type);
    static SemanticType typeRef(ResolvedType type);
    static SemanticType unresolved();
}
```

### CompletionExpression

```java
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

    // Factory — scopeTokenIndex is caretTokenIndex - 2
    // (caret token, minus DOT, minus one = the scope expression's last token)
    static CompletionExpression fromCaretPosition(ParseTree tree, int caretTokenIndex);

    // Accessors
    public ParseTree parseTree();
    public int caretTokenIndex();
    public int scopeTokenIndex();
}
```

### VisibleSymbols

```java
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

    static VisibleSymbols empty();
    Optional<SemanticType> lookup(String name);
}
```

### WorkspaceTypes

```java
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

## ExpressionTypeResolver interface

```java
public interface ExpressionTypeResolver {

    Optional<SemanticType> resolve(
        CompletionExpression expression,
        VisibleSymbols symbols,
        WorkspaceTypes workspaceTypes);
}
```

## Baseline adapter: TolerantVisitorTypeResolver

`TolerantVisitorTypeResolver implements ExpressionTypeResolver`:

- Accepts the three parameter types at the interface boundary
- Internally ignores `VisibleSymbols` (current code doesn't use bindings for dot-access)
- Uses `CompletionExpression.parseTree()` and `scopeTokenIndex()` to reach the raw inputs
- Uses `WorkspaceTypes.typeSolver()` to get the TypeSolver
- Runs the existing logic unchanged:
  1. `TolerantDrlxToJavaParserVisitor.visit(parseTree)` → `CompilationUnit`
  2. `JavaSymbolSolver(typeSolver).inject(compilationUnit)`
  3. `tokenIdJPNodeMap.get(scopeTokenIndex)` → `Expression`
  4. `scopeNode.calculateResolvedType()` → `ResolvedType`
- Wraps the result in `SemanticType.value(resolvedType)`
- Returns `Optional.empty()` when scope node is null

## MemberCompletionProvider

```java
public class MemberCompletionProvider {

    List<CompletionItem> completions(SemanticType type);
}
```

Extracted from `DrlxCompletionHelper`:

- `createTypeBasedCompletions()` → `completions()`
- `isAccessible(ResolvedFieldDeclaration)` — moved
- `isAccessible(MethodUsage)` — moved
- `addDirectPropertyAccess()` — moved
- `createCompletionItem()` — remains in `DrlxCompletionHelper` as well (shared utility)

## Wiring

### DrlxCompletionHelper constructor change

```java
public DrlxCompletionHelper(
    WorkspaceSemanticModel model,
    ExpressionTypeResolver resolver,
    MemberCompletionProvider memberProvider)
```

### DOT_ACCESS flow

```
createSemanticCompletions(DOT_ACCESS, ctx)
  ├── CompletionExpression.fromCaretPosition(tree, caretTokenIndex)
  ├── VisibleSymbols.empty()
  ├── model (implements WorkspaceTypes)
  ├── resolver.resolve(expression, symbols, workspaceTypes)
  │     → Optional<SemanticType>
  ├── if present: memberProvider.completions(semanticType)
  │     → List<CompletionItem>
  └── if empty: fallback to IDENTIFIER placeholder
```

### WorkspaceSemanticModel implements WorkspaceTypes

`WorkspaceSemanticModel` implements the `WorkspaceTypes` interface directly,
delegating `typeSolver()` to its existing method.

### All other CompletionSite cases unchanged

Still return the `IDENTIFIER` placeholder.

## Test strategy

- All 67 existing tests must pass with zero behavior change
- Add unit tests for:
  - `SemanticType` factory methods and category
  - `CompletionExpression.fromCaretPosition()` edge cases (caretTokenIndex 0, 1)
  - `VisibleSymbols.empty()` returns empty on lookup
  - `TolerantVisitorTypeResolver` resolves the same types as the old inline code
  - `MemberCompletionProvider` produces the same items as the old `createTypeBasedCompletions()`

## Files to create

- `semantic/SemanticType.java`
- `semantic/CompletionExpression.java`
- `semantic/VisibleSymbols.java`
- `semantic/WorkspaceTypes.java`
- `semantic/ExpressionTypeResolver.java`
- `semantic/TolerantVisitorTypeResolver.java`
- `semantic/MemberCompletionProvider.java`

## Files to modify

- `semantic/WorkspaceSemanticModel.java` — implements `WorkspaceTypes`
- `DrlxCompletionHelper.java` — new constructor, refactored `DOT_ACCESS` case, remove `resolveDotAccess()` / `createTypeBasedCompletions()` / accessor helpers
- `DrlxLspDocumentService.java` — construct resolver and provider, pass to helper
- Test files — update constructor calls

## Non-goals

- Replacement resolver implementation (sentinel injection / reparse)
- Filling `VisibleSymbols` with scope-aware bindings
- Expanding `WorkspaceTypes` beyond `typeSolver()`
- Changing non-DOT_ACCESS completion sites

# Phase 5: Characterization Tests and Replacement Resolver — Design Spec

## Goal

Write a characterization test suite from the Completion_Redesign_Review01.md
fixture list. Verify tests pass with `TolerantVisitorTypeResolver`. Build a
replacement resolver (`SentinelExpressionTypeResolver`) that isolates the caret
expression via token walking + sentinel injection + reparse, then verify the
same tests pass.

## Scope

- Characterization test suite covering the 20 spec fixtures
- `@Disabled` for tests requiring VisibleSymbols (#7) or Maven classpath (#6)
- `SentinelExpressionTypeResolver` implementing `ExpressionTypeResolver`
- `TokenWalker` utility for backward expression boundary detection
- No changes to `DrlxCompletionHelper` wiring (swap happens later)

## Part 1: Characterization test suite

### Test class

`ExpressionTypeResolverCharacterizationTest` in
`org.drools.drlx.completion.semantic`.

Each test method:
1. Parses a complete DRLX document with `DrlxParser`
2. Computes caret token index from (line, col)
3. Calls `CompletionExpression.fromCaretPosition(tree, caretTokenIndex)`
4. Calls `resolver.resolve(expression, VisibleSymbols.empty(), model)`
5. Asserts `resolvedType().describe()` matches expected FQCN

The resolver field defaults to `TolerantVisitorTypeResolver`. Tests that also
need to pass with `SentinelExpressionTypeResolver` will be verified after
the replacement is built.

### Fixture categorization

**Pass with TolerantVisitorTypeResolver (enabled):**

| Fixture | Expected type | Notes |
|---------|--------------|-------|
| `System.` | `java.lang.System` | |
| `System.out.` | `java.io.PrintStream` | |
| `10.5B.` | `java.math.BigDecimal` | |
| `Person p = ...; p.address.` | `org.drools.drlx.domain.Address` | RHS local |
| `list#ArrayList#.` | `java.util.ArrayList` | inline cast |
| broken code after caret | same as above cases | incomplete trailing code |
| caret in middle of document | resolves normally | complete code follows |

**@Disabled — requires VisibleSymbols or Maven classpath (#6, #7):**

| Fixture | Reason |
|---------|--------|
| `var p : /persons` with `DataSource<Person>` | needs unit class resolution (#7) |
| explicit pattern types | needs VisibleSymbols (#7) |
| nested OOPath chunks and constraints | needs OOPath traversal (#7) |
| bindings from earlier patterns | needs VisibleSymbols (#7) |
| no leakage from later patterns/other rules | needs VisibleSymbols (#7) |
| shadowed local variables | needs VisibleSymbols (#7) |
| real Maven workspace classes | needs Maven classpath (#6) |

**Improvement targets for replacement resolver (may fail with baseline):**

| Fixture | Issue with baseline |
|---------|-------------------|
| `list.get(0).` | method return type through tokenIdJPNodeMap |
| null-safe access `!.` | not handled by tolerant visitor |
| comments/whitespace around `.` | hidden channel tokens |
| arrays and indexed access | not handled |
| overloaded methods | may select wrong overload |
| inline casts with qualified/generic types | partial support |

Tests in this category: mark `@Disabled` if they fail with the baseline,
add a note that the replacement resolver should handle them.

## Part 2: Replacement resolver

### SentinelExpressionTypeResolver

Implements `ExpressionTypeResolver`. Steps:

```
resolve(expression, symbols, workspaceTypes):
  1. TokenWalker.findExpressionBoundary(expression)
     → startTokenIndex
  2. extractTokenText(startTokenIndex .. dotTokenIndex)
     → raw expression text
  3. rawText + ".__sentinel__"
     → repaired text
  4. MVELCompiler.transpile(repaired)
     → TranspiledResult with JavaParser AST + JavaSymbolSolver already injected
  5. find NameExpr("__sentinel__") in AST → get scope (parent)
  6. scope.calculateResolvedType()
     → ResolvedType
  7. return Optional.of(SemanticType.value(resolvedType))
```

Uses `MVELCompiler.transpile()` from the MVEL3 module instead of
`DrlxToJavaParserVisitor`. The MVEL transpiler is mature and already handles
inline casts (`#`), null-safe access (`!.`), BigDecimal/BigInteger literals,
property access, and sets up `JavaSymbolSolver` with a `CombinedTypeSolver`
automatically. See `TypeResolveTest` in the MVEL3 project for proven coverage.

The `WorkspaceTypes.typeSolver()` should be passed to the transpiler's
`CompilerParameters` so it resolves workspace classes, not just JRE classes.

### TokenWalker

Utility class. Static method:

```java
public class TokenWalker {
    /**
     * Walk backward from dotTokenIndex to find the start of the
     * expression chain. Returns the index of the first token in
     * the expression.
     */
    public static int findExpressionBoundary(
            CommonTokenStream tokens, int dotTokenIndex);
}
```

**Continue through:**
- Identifiers (`IDENTIFIER`)
- Dots (`.`)
- Hash (`#`) for inline casts
- Balanced parentheses `()` — track nesting depth
- Balanced brackets `[]` — track nesting depth
- Numeric, string, BigDecimal literals
- Null-safe operator (`!.` if the grammar has it, or `!` followed by `.`)

**Stop at:**
- Semicolons `;`, commas `,`
- Opening braces `{`
- Keywords (`if`, `do`, `var`, `new`, etc.) — except `new` which starts
  an expression (handle `new` as part of the expression if directly
  preceding an identifier)
- Assignment operators `=`, `+=`, etc.
- Comparison operators `==`, `!=`, `<`, `>`, etc.
- Logical operators `&&`, `||`
- Beginning of token stream (index 0)

### Transpile strategy

Use `MVELCompiler.transpile()` to parse and resolve the repaired expression.
The transpiler:

1. Parses the MVEL expression via `MvelParser` (handles `#` inline casts,
   `!.` null-safe access, BigDecimal literals, etc.)
2. Builds a `CompilationUnit` with `JavaSymbolSolver` injected
3. Returns `TranspiledResult` with the resolved AST

The `CompilerParameters` builder must receive the `TypeSolver` from
`WorkspaceTypes` so workspace classes are resolvable.

### Sentinel location

After transpilation, walk the JavaParser AST in the `TranspiledResult`
to find `NameExpr` or `DrlNameExpr` with name `__sentinel__`. Its parent
is a `FieldAccessExpr` or `NullSafeFieldAccessExpr` — the scope of that
access expression is the expression whose type we want.

### CompletionExpression change

`SentinelExpressionTypeResolver` needs access to the `CommonTokenStream`
(not just the parse tree) for token walking. `CompletionExpression` needs
a new accessor or the resolver extracts tokens from the parse tree's parser.

Options:
- **A)** Add `DrlxParser` to `CompletionExpression` (it holds the token stream)
- **B)** `CompletionExpression` stores the `CommonTokenStream` directly

Choose **A** — `CompletionContext` already holds the `DrlxParser` as a
private field. Add a `parser()` accessor to `CompletionContext`, then pass
it through to `CompletionExpression`. The baseline adapter ignores it.

Updated factory:
```java
static CompletionExpression fromCaretPosition(
        DrlxParser parser, ParseTree tree, int caretTokenIndex);
```

Call site in `DrlxCompletionHelper.resolveDotAccess()`:
```java
CompletionExpression expression = CompletionExpression.fromCaretPosition(
        ctx.parser(), ctx.parseTree(), ctx.caretTokenIndex());
```

## Files to create

- `semantic/SentinelExpressionTypeResolver.java`
- `semantic/TokenWalker.java`
- `semantic/ExpressionTypeResolverCharacterizationTest.java` (test)
- `semantic/TokenWalkerTest.java` (test)

## Files to modify

- `semantic/CompletionContext.java` — add `parser()` public accessor
- `semantic/CompletionExpression.java` — add `parser` field and accessor, update factory method
- `DrlxCompletionHelper.java` — pass `ctx.parser()` to `CompletionExpression.fromCaretPosition()`

## New dependency

`drlx-completion` module needs `org.mvel:mvel3` as a dependency in its
`pom.xml` to access `MVELCompiler` and `CompilerParameters`.

## Non-goals

- Swapping the default resolver in `DrlxLspDocumentService` (done later)
- Implementing VisibleSymbols (#7)
- Maven classpath resolution (#6)
- Removing the tolerant visitor from the codebase (Phase 6)

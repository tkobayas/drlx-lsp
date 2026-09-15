# Phase 5: Characterization Tests and Replacement Resolver — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Write a characterization test suite for expression type resolution, verify with the baseline adapter, then build a replacement resolver using MVEL3's `MVELCompiler.transpile()` and sentinel injection.

**Architecture:** A single parameterized test class runs 20 spec fixtures against `ExpressionTypeResolver` implementations. The replacement `SentinelExpressionTypeResolver` walks backward from the dot token to find the expression boundary, appends `.__sentinel__`, transpiles via `MVELCompiler`, and resolves the sentinel's scope type. Tests requiring VisibleSymbols (#7) or Maven classpath (#6) are `@Disabled`.

**Tech Stack:** Java 21, MVEL3 (`MVELCompiler`, `MvelParser`), JavaParser (symbol resolution), ANTLR4, JUnit 5, AssertJ

## Global Constraints

- All existing 78 tests must continue to pass
- Code repo: `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`
- Source root: `drlx-completion/src/main/java/`
- Test root: `drlx-completion/src/test/java/`
- Build/test: `mvn -pl drlx-completion test` (requires `drlx-parser` installed)
- MVEL3 dependency: `org.mvel:mvel3:3.0.0-SNAPSHOT` (must be installed in local Maven repo)
- Before tests, set `MvelParser.Factory.USE_ANTLR = true` and `MVELTranspiler.ENABLE_REWRITE = false`

---

### Task 1: Add mvel3 dependency and update CompletionExpression for parser access

**Files:**
- Modify: `drlx-completion/pom.xml` — add mvel3 dependency
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java:18-35` — add `parser()` accessor
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionExpression.java` — add `parser` field, update factory
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java:96-98` — pass `ctx.parser()` to factory
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/CompletionExpressionTest.java` — update factory calls
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/TolerantVisitorTypeResolverTest.java` — update factory calls

**Interfaces:**
- Consumes: existing `CompletionExpression`, `CompletionContext`, `DrlxCompletionHelper`
- Produces: `CompletionExpression.parser()` → `DrlxParser` (used by Task 3 `SentinelExpressionTypeResolver`), `CompletionContext.parser()` → `DrlxParser`

- [ ] **Step 1: Add mvel3 dependency to drlx-completion/pom.xml**

Add after the existing `drlx-parser-core` dependency:

```xml
        <dependency>
            <groupId>org.mvel</groupId>
            <artifactId>mvel3</artifactId>
            <version>3.0.0-SNAPSHOT</version>
        </dependency>
```

- [ ] **Step 2: Add parser() accessor to CompletionContext**

In `CompletionContext.java`, add after the `caretTokenIndex()` method (line 42):

```java
    public DrlxParser parser() {
        return parser;
    }
```

- [ ] **Step 3: Update CompletionExpression to carry DrlxParser**

Replace the entire `CompletionExpression.java`:

```java
package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxParser;

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

    private final DrlxParser parser;
    private final ParseTree parseTree;
    private final int caretTokenIndex;
    private final int scopeTokenIndex;

    private CompletionExpression(DrlxParser parser, ParseTree parseTree,
                                int caretTokenIndex, int scopeTokenIndex) {
        this.parser = parser;
        this.parseTree = parseTree;
        this.caretTokenIndex = caretTokenIndex;
        this.scopeTokenIndex = scopeTokenIndex;
    }

    public static CompletionExpression fromCaretPosition(
            DrlxParser parser, ParseTree tree, int caretTokenIndex) {
        return new CompletionExpression(parser, tree, caretTokenIndex, caretTokenIndex - 2);
    }

    public DrlxParser parser() {
        return parser;
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

- [ ] **Step 4: Update DrlxCompletionHelper.resolveDotAccess() call site**

In `DrlxCompletionHelper.java`, change lines 97-98 from:

```java
        CompletionExpression expression = CompletionExpression.fromCaretPosition(
                ctx.parseTree(), ctx.caretTokenIndex());
```

to:

```java
        CompletionExpression expression = CompletionExpression.fromCaretPosition(
                ctx.parser(), ctx.parseTree(), ctx.caretTokenIndex());
```

- [ ] **Step 5: Update CompletionExpressionTest**

In `CompletionExpressionTest.java`, update the test methods. Each call to `CompletionExpression.fromCaretPosition` needs a `DrlxParser` as first argument. Add a helper that returns the parser:

Replace the test class body (keep the package and imports, add `DrlxParser` import):

```java
class CompletionExpressionTest {

    @Test
    void fromCaretPositionNormal() {
        DrlxParser parser = createParser("System.");
        CompletionExpression expr = CompletionExpression.fromCaretPosition(
                parser, parser.drlxStart(), 2);

        assertThat(expr.caretTokenIndex()).isEqualTo(2);
        assertThat(expr.scopeTokenIndex()).isEqualTo(0);
        assertThat(expr.parseTree()).isNotNull();
        assertThat(expr.parser()).isSameAs(parser);
    }

    @Test
    void fromCaretPositionAtZero() {
        DrlxParser parser = createParser("x");
        CompletionExpression expr = CompletionExpression.fromCaretPosition(
                parser, parser.drlxStart(), 0);

        assertThat(expr.caretTokenIndex()).isEqualTo(0);
        assertThat(expr.scopeTokenIndex()).isEqualTo(-2);
    }

    @Test
    void fromCaretPositionAtOne() {
        DrlxParser parser = createParser(".x");
        CompletionExpression expr = CompletionExpression.fromCaretPosition(
                parser, parser.drlxStart(), 1);

        assertThat(expr.caretTokenIndex()).isEqualTo(1);
        assertThat(expr.scopeTokenIndex()).isEqualTo(-1);
    }

    @Test
    void visibleSymbolsEmptyReturnsEmptyOnLookup() {
        VisibleSymbols symbols = VisibleSymbols.empty();
        assertThat(symbols.lookup("anything")).isEmpty();
    }

    private DrlxParser createParser(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        return new DrlxParser(tokens);
    }
}
```

Add import: `import org.drools.drlx.parser.DrlxParser;`

- [ ] **Step 6: Update TolerantVisitorTypeResolverTest**

In `TolerantVisitorTypeResolverTest.java`, update `resolveAt()` and `returnsEmptyWhenScopeTokenIndexNegative()` to pass the parser:

Change the `resolveAt` method:

```java
    private Optional<SemanticType> resolveAt(String text, int line, int col) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        ParseTree tree = parser.drlxStart();

        int caretTokenIndex = computeTokenIndex(parser, line + 1, col);
        CompletionExpression expr = CompletionExpression.fromCaretPosition(parser, tree, caretTokenIndex);

        return resolver.resolve(expr, VisibleSymbols.empty(), model);
    }
```

Change the `returnsEmptyWhenScopeTokenIndexNegative` test:

```java
    @Test
    void returnsEmptyWhenScopeTokenIndexNegative() {
        DrlxParser parser = createParser("x");
        CompletionExpression expr = CompletionExpression.fromCaretPosition(
                parser, parser.drlxStart(), 0);
        Optional<SemanticType> result = resolver.resolve(expr, VisibleSymbols.empty(), model);

        assertThat(result).isEmpty();
    }

    private DrlxParser createParser(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser drlxParser = new DrlxParser(tokens);
        drlxParser.drlxStart();
        return drlxParser;
    }
```

Remove the existing `parse()` method if present.

- [ ] **Step 7: Run all tests**

Run: `mvn -pl drlx-completion test`
Expected: All 78 existing tests + updated tests pass

- [ ] **Step 8: Commit**

```bash
git add drlx-completion/pom.xml
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionExpression.java
git add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java
git add drlx-completion/src/test/java/org/drools/drlx/completion/semantic/CompletionExpressionTest.java
git add drlx-completion/src/test/java/org/drools/drlx/completion/semantic/TolerantVisitorTypeResolverTest.java
git commit -m "chore: add mvel3 dependency and update CompletionExpression for parser access"
```

---

### Task 2: Characterization test suite

**Files:**
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java`

**Interfaces:**
- Consumes: `ExpressionTypeResolver.resolve(CompletionExpression, VisibleSymbols, WorkspaceTypes)`, `CompletionExpression.fromCaretPosition(DrlxParser, ParseTree, int)`, `SemanticType.resolvedType().describe()`, `TolerantVisitorTypeResolver` (from Task 1 changes)
- Produces: Characterization test suite (used by Task 3 to verify replacement resolver)

- [ ] **Step 1: Write the characterization test class**

```java
package org.drools.drlx.completion.semantic;

import java.util.Optional;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests for ExpressionTypeResolver implementations.
 * Each test resolves the type of the scope expression at a DOT_ACCESS caret position.
 *
 * Tests marked @Disabled require VisibleSymbols (#7) or Maven classpath (#6).
 */
class ExpressionTypeResolverCharacterizationTest {

    private final WorkspaceSemanticModel model =
            new WorkspaceSemanticModel(new CurrentClassloaderProvider());
    private final ExpressionTypeResolver resolver = new TolerantVisitorTypeResolver();

    // --- Cases that pass with TolerantVisitorTypeResolver ---

    @Test
    void systemDot() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.
                """;
        assertResolvesTo(text, 4, 16, "java.lang.System");
    }

    @Test
    void systemOutDot() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.out.
                """;
        assertResolvesTo(text, 4, 20, "java.io.PrintStream");
    }

    @Test
    void bigDecimalLiteral() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { 10.5B.
                """;
        assertResolvesTo(text, 4, 15, "java.math.BigDecimal");
    }

    @Test
    void rhsLocalPropertyChain() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        Person p = new Person("John", new Address("Tokyo"));
                        p.address.
                """;
        assertResolvesTo(text, 9, 18, "org.drools.drlx.domain.Address");
    }

    @Test
    void inlineCastSimple() {
        String text = """
                import java.util.ArrayList;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { list#ArrayList#.
                """;
        assertResolvesTo(text, 6, 25, "java.util.ArrayList");
    }

    @Test
    void caretInMiddleOfDocument() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.out.
                }

                rule R2 {
                    var b : /bs,
                    do { System.out.println("hello"); }
                }
                """;
        assertResolvesTo(text, 4, 20, "java.io.PrintStream");
    }

    @Test
    void brokenCodeAfterCaret() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.
                    invalid broken {{ code
                """;
        assertResolvesTo(text, 4, 16, "java.lang.System");
    }

    // --- Improvement targets: may fail with baseline, replacement should handle ---

    @Test
    @Disabled("Improvement target for replacement resolver — method return type")
    void methodReturnType() {
        // list.get(0). → should resolve to the return type of get()
        String text = """
                import java.util.List;
                import java.util.ArrayList;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        List list = new ArrayList();
                        list.get(0).
                """;
        assertResolvesTo(text, 9, 20, "java.lang.Object");
    }

    @Test
    @Disabled("Improvement target for replacement resolver — null-safe access")
    void nullSafeAccess() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        Person p = new Person("John", new Address("Tokyo"));
                        p!.address!.
                """;
        assertResolvesTo(text, 9, 20, "org.drools.drlx.domain.Address");
    }

    @Test
    @Disabled("Improvement target for replacement resolver — array indexed access")
    void arrayIndexedAccess() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        String[] arr = new String[]{"a","b"};
                        arr[0].
                """;
        assertResolvesTo(text, 6, 15, "java.lang.String");
    }

    @Test
    @Disabled("Improvement target for replacement resolver — inline cast with qualified type")
    void inlineCastQualifiedType() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { list#java.util.ArrayList#.
                """;
        assertResolvesTo(text, 4, 35, "java.util.ArrayList");
    }

    // --- @Disabled: requires VisibleSymbols (#7) or Maven classpath (#6) ---

    @Test
    @Disabled("Requires unit class resolution — see #7 ENTRY_POINT")
    void entryPointTypeInference() {
        // var p : /persons with DataSource<Person> persons in unit class
        // Should resolve p to Person
    }

    @Test
    @Disabled("Requires VisibleSymbols — see #7")
    void bindingsFromEarlierPatterns() {
        // var p : /persons, p. should offer Person members
    }

    @Test
    @Disabled("Requires VisibleSymbols — see #7")
    void noLeakageFromLaterPatterns() {
        // binding from rule R2 should not appear in rule R1
    }

    @Test
    @Disabled("Requires VisibleSymbols — see #7")
    void shadowedLocalVariables() {
        // inner scope shadows outer
    }

    @Test
    @Disabled("Requires OOPath traversal — see #7 OOPATH_CHUNK/CONSTRAINT_EXPRESSION")
    void nestedOopathChunkConstraint() {
        // /persons/address[city.] — city belongs to Address, not Person
    }

    @Test
    @Disabled("Requires Maven classpath — see #6")
    void realMavenWorkspaceClasses() {
        // resolve a class from a Maven dependency not on the LSP classpath
    }

    // --- Helper methods ---

    private void assertResolvesTo(String text, int line, int col, String expectedFqcn) {
        Optional<SemanticType> result = resolveAt(text, line, col);
        assertThat(result)
                .as("Expected type %s at line %d col %d", expectedFqcn, line, col)
                .isPresent();
        assertThat(result.get().resolvedType().describe()).isEqualTo(expectedFqcn);
    }

    private Optional<SemanticType> resolveAt(String text, int line, int col) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        ParseTree tree = parser.drlxStart();

        int caretTokenIndex = computeTokenIndex(parser, line + 1, col);
        CompletionExpression expr = CompletionExpression.fromCaretPosition(parser, tree, caretTokenIndex);

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

- [ ] **Step 2: Run the characterization tests**

Run: `mvn -pl drlx-completion test -Dtest=ExpressionTypeResolverCharacterizationTest`
Expected: 7 enabled tests pass, disabled tests skipped

- [ ] **Step 3: Run full test suite**

Run: `mvn -pl drlx-completion test`
Expected: All tests pass (78 existing + 7 new enabled characterization tests)

- [ ] **Step 4: Commit**

```bash
git add drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java
git commit -m "test: add characterization test suite for ExpressionTypeResolver"
```

---

### Task 3: TokenWalker and SentinelExpressionTypeResolver

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/TokenWalker.java`
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/SentinelExpressionTypeResolver.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/TokenWalkerTest.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/SentinelExpressionTypeResolverTest.java`

**Interfaces:**
- Consumes: `ExpressionTypeResolver` interface (from Phase 4), `CompletionExpression.parser()` (from Task 1), `SemanticType.value(ResolvedType)` (from Phase 4), `WorkspaceTypes.typeSolver()` (from Phase 4), `MVELCompiler.transpile()` and `MVEL.map()` (from mvel3 dependency)
- Produces: `SentinelExpressionTypeResolver` (used by Task 4 to verify characterization tests), `TokenWalker.findExpressionBoundary(CommonTokenStream, int)` → `int`

- [ ] **Step 1: Write TokenWalker tests**

```java
package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenWalkerTest {

    @Test
    void simpleIdentifierDot() {
        // "System." — tokens: System(0) .(1) EOF(2)
        // dot at index 1, boundary should be 0 (System)
        CommonTokenStream tokens = tokenize("System.");
        int boundary = TokenWalker.findExpressionBoundary(tokens, 1);
        assertThat(boundary).isEqualTo(0);
    }

    @Test
    void chainedDotAccess() {
        // "System.out." — tokens: System(0) .(1) out(2) .(3) EOF(4)
        // dot at index 3, boundary should be 0
        CommonTokenStream tokens = tokenize("System.out.");
        int boundary = TokenWalker.findExpressionBoundary(tokens, 3);
        assertThat(boundary).isEqualTo(0);
    }

    @Test
    void afterSemicolon() {
        // "x = 1; System." — boundary should stop at System, not cross ;
        CommonTokenStream tokens = tokenize("x = 1; System.");
        int dotIndex = findLastDot(tokens);
        int boundary = TokenWalker.findExpressionBoundary(tokens, dotIndex);
        assertThat(tokens.get(boundary).getText()).isEqualTo("System");
    }

    @Test
    void afterOpenBrace() {
        // "{ System." — boundary should be System, not cross {
        CommonTokenStream tokens = tokenize("{ System.");
        int dotIndex = findLastDot(tokens);
        int boundary = TokenWalker.findExpressionBoundary(tokens, dotIndex);
        assertThat(tokens.get(boundary).getText()).isEqualTo("System");
    }

    @Test
    void methodCallInChain() {
        // "list.get(0)." — tokens: list . get ( 0 ) .
        // boundary should be list (index 0)
        CommonTokenStream tokens = tokenize("list.get(0).");
        int dotIndex = findLastDot(tokens);
        int boundary = TokenWalker.findExpressionBoundary(tokens, dotIndex);
        assertThat(tokens.get(boundary).getText()).isEqualTo("list");
    }

    @Test
    void inlineCast() {
        // "list#ArrayList#." — boundary should be list
        CommonTokenStream tokens = tokenize("list#ArrayList#.");
        int dotIndex = findLastDot(tokens);
        int boundary = TokenWalker.findExpressionBoundary(tokens, dotIndex);
        assertThat(tokens.get(boundary).getText()).isEqualTo("list");
    }

    @Test
    void dotAtStartOfStream() {
        // "." — dot at index 0, boundary should be 0
        CommonTokenStream tokens = tokenize(".");
        int boundary = TokenWalker.findExpressionBoundary(tokens, 0);
        assertThat(boundary).isEqualTo(0);
    }

    private CommonTokenStream tokenize(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        return tokens;
    }

    private int findLastDot(CommonTokenStream tokens) {
        int lastDot = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).getText().equals(".")) {
                lastDot = i;
            }
        }
        return lastDot;
    }
}
```

- [ ] **Step 2: Implement TokenWalker**

```java
package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.drools.drlx.parser.DrlxLexer;

public class TokenWalker {

    public static int findExpressionBoundary(CommonTokenStream tokens, int dotTokenIndex) {
        int index = dotTokenIndex - 1;
        int parenDepth = 0;
        int bracketDepth = 0;

        while (index >= 0) {
            Token token = tokens.get(index);
            int type = token.getType();

            if (parenDepth > 0) {
                if (type == DrlxLexer.LPAREN) {
                    parenDepth--;
                } else if (type == DrlxLexer.RPAREN) {
                    parenDepth++;
                }
                index--;
                continue;
            }

            if (bracketDepth > 0) {
                if (type == DrlxLexer.LBRACK) {
                    bracketDepth--;
                } else if (type == DrlxLexer.RBRACK) {
                    bracketDepth++;
                }
                index--;
                continue;
            }

            if (type == DrlxLexer.RPAREN) {
                parenDepth++;
                index--;
                continue;
            }
            if (type == DrlxLexer.RBRACK) {
                bracketDepth++;
                index--;
                continue;
            }

            if (isExpressionToken(type)) {
                index--;
                continue;
            }

            // not an expression token — boundary is the next token
            return index + 1;
        }

        return 0;
    }

    private static boolean isExpressionToken(int type) {
        return type == DrlxLexer.IDENTIFIER
                || type == DrlxLexer.DOT
                || type == DrlxLexer.HASH
                || type == DrlxLexer.BANG
                || type == DrlxLexer.IntegerLiteral
                || type == DrlxLexer.FloatingPointLiteral
                || type == DrlxLexer.BigDecimalLiteral
                || type == DrlxLexer.BigIntegerLiteral
                || type == DrlxLexer.StringLiteral
                || type == DrlxLexer.CharacterLiteral
                || type == DrlxLexer.BooleanLiteral
                || type == DrlxLexer.NullLiteral
                || type == DrlxLexer.THIS
                || type == DrlxLexer.NEW;
    }
}
```

Note: The exact token type names (`LPAREN`, `RPAREN`, `LBRACK`, `RBRACK`, `HASH`, `BANG`, `BigDecimalLiteral`, etc.) must match the DRLX lexer. If a token type name differs, check `DrlxLexer.java` generated source for the correct constant names. The implementer should run `grep -n "LPAREN\|RPAREN\|LBRACK\|RBRACK\|HASH\|BANG\|BigDecimal" drlx-parser/drlx-parser-core/src/main/java/org/drools/drlx/parser/DrlxLexer.java` to verify.

- [ ] **Step 3: Run TokenWalker tests**

Run: `mvn -pl drlx-completion test -Dtest=TokenWalkerTest`
Expected: All 7 tests pass

- [ ] **Step 4: Write SentinelExpressionTypeResolver tests**

```java
package org.drools.drlx.completion.semantic;

import java.util.Optional;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mvel3.parser.MvelParser;
import org.mvel3.transpiler.MVELTranspiler;

import static org.assertj.core.api.Assertions.assertThat;

class SentinelExpressionTypeResolverTest {

    private final WorkspaceSemanticModel model =
            new WorkspaceSemanticModel(new CurrentClassloaderProvider());
    private final SentinelExpressionTypeResolver resolver = new SentinelExpressionTypeResolver();

    @BeforeAll
    static void setup() {
        MvelParser.Factory.USE_ANTLR = true;
        MVELTranspiler.ENABLE_REWRITE = false;
    }

    @Test
    void resolvesSystemDot() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.
                """;
        Optional<SemanticType> result = resolveAt(text, 4, 16);

        assertThat(result).isPresent();
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
        assertThat(result.get().resolvedType().describe()).isEqualTo("java.io.PrintStream");
    }

    @Test
    void resolvesBigDecimalLiteral() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { 10.5B.
                """;
        Optional<SemanticType> result = resolveAt(text, 4, 15);

        assertThat(result).isPresent();
        assertThat(result.get().resolvedType().describe()).isEqualTo("java.math.BigDecimal");
    }

    @Test
    void returnsEmptyWhenScopeTokenIndexNegative() {
        DrlxParser parser = createParser("x");
        ParseTree tree = parser.drlxStart();
        CompletionExpression expr = CompletionExpression.fromCaretPosition(parser, tree, 0);
        Optional<SemanticType> result = resolver.resolve(expr, VisibleSymbols.empty(), model);

        assertThat(result).isEmpty();
    }

    private Optional<SemanticType> resolveAt(String text, int line, int col) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        ParseTree tree = parser.drlxStart();

        int caretTokenIndex = computeTokenIndex(parser, line + 1, col);
        CompletionExpression expr = CompletionExpression.fromCaretPosition(parser, tree, caretTokenIndex);

        return resolver.resolve(expr, VisibleSymbols.empty(), model);
    }

    private DrlxParser createParser(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        return new DrlxParser(tokens);
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

- [ ] **Step 5: Implement SentinelExpressionTypeResolver**

```java
package org.drools.drlx.completion.semantic;

import java.util.Optional;
import java.util.Set;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.resolution.types.ResolvedType;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.mvel3.ClassManager;
import org.mvel3.MVEL;
import org.mvel3.MVELCompiler;
import org.mvel3.Type;
import org.mvel3.transpiler.TranspiledResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SentinelExpressionTypeResolver implements ExpressionTypeResolver {

    private static final Logger logger = LoggerFactory.getLogger(SentinelExpressionTypeResolver.class);
    private static final String SENTINEL = "__sentinel__";

    @Override
    public Optional<SemanticType> resolve(
            CompletionExpression expression,
            VisibleSymbols symbols,
            WorkspaceTypes workspaceTypes) {

        CommonTokenStream tokens = (CommonTokenStream) expression.parser().getTokenStream();
        int caretTokenIndex = expression.caretTokenIndex();

        // The DOT is at caretTokenIndex - 1
        int dotTokenIndex = caretTokenIndex - 1;
        if (dotTokenIndex < 0) {
            return Optional.empty();
        }

        // Walk backward to find expression boundary
        int boundaryIndex = TokenWalker.findExpressionBoundary(tokens, dotTokenIndex);

        // Extract token text from boundary to the dot (inclusive)
        StringBuilder sb = new StringBuilder();
        for (int i = boundaryIndex; i <= dotTokenIndex; i++) {
            Token token = tokens.get(i);
            if (i > boundaryIndex) {
                sb.append(token.getText());
            } else {
                sb.append(token.getText());
            }
        }
        sb.append(SENTINEL);
        String repairedText = sb.toString();
        logger.info("Repaired expression: [{}]", repairedText);

        try {
            // Transpile via MVELCompiler
            var params = MVEL.map().<Object>out(Type.OBJECT)
                    .expression(repairedText)
                    .classManager(new ClassManager())
                    .classLoader(ClassLoader.getSystemClassLoader())
                    .build();

            TranspiledResult result = new MVELCompiler().transpile(params);
            CompilationUnit unit = result.getUnit();

            // Find the sentinel node and resolve its scope
            Expression scopeExpr = findSentinelScope(unit);
            if (scopeExpr == null) {
                logger.info("Sentinel scope not found in transpiled AST");
                return Optional.empty();
            }

            ResolvedType resolvedType = scopeExpr.calculateResolvedType();
            return Optional.of(SemanticType.value(resolvedType));

        } catch (Exception e) {
            logger.info("Failed to resolve via sentinel: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Expression findSentinelScope(CompilationUnit unit) {
        for (Node node : unit.findAll(Node.class)) {
            if (node instanceof FieldAccessExpr fae && SENTINEL.equals(fae.getNameAsString())) {
                return fae.getScope();
            }
            if (node instanceof NameExpr ne && SENTINEL.equals(ne.getNameAsString())) {
                // Sentinel is a standalone name — check parent
                if (ne.getParentNode().isPresent() && ne.getParentNode().get() instanceof FieldAccessExpr parent) {
                    return parent.getScope();
                }
            }
        }
        return null;
    }
}
```

Note: The `findSentinelScope` method may need adjustment based on how `MvelParser` represents the AST. If MVEL uses custom node types like `NullSafeFieldAccessExpr` or `DrlNameExpr`, add those checks. The implementer should run the test and inspect the AST if the sentinel is not found, using `unit.toString()` to see the generated code.

- [ ] **Step 6: Run SentinelExpressionTypeResolver tests**

Run: `mvn -pl drlx-completion test -Dtest=SentinelExpressionTypeResolverTest`
Expected: All 4 tests pass

- [ ] **Step 7: Run all tests**

Run: `mvn -pl drlx-completion test`
Expected: All tests pass

- [ ] **Step 8: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/TokenWalker.java
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/SentinelExpressionTypeResolver.java
git add drlx-completion/src/test/java/org/drools/drlx/completion/semantic/TokenWalkerTest.java
git add drlx-completion/src/test/java/org/drools/drlx/completion/semantic/SentinelExpressionTypeResolverTest.java
git commit -m "feat: add SentinelExpressionTypeResolver with TokenWalker and MVELCompiler transpile"
```

---

### Task 4: Verify characterization tests with replacement resolver

**Files:**
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java` — add `@Nested` class for replacement resolver

**Interfaces:**
- Consumes: `SentinelExpressionTypeResolver` (from Task 3), characterization test suite (from Task 2)
- Produces: Verified test results for both resolvers

- [ ] **Step 1: Add a @Nested class for the replacement resolver**

In `ExpressionTypeResolverCharacterizationTest.java`, add `@BeforeAll` setup and a `@Nested` inner class after the existing test methods (before the helper methods section):

Add at the top of the class:

```java
    @BeforeAll
    static void setup() {
        MvelParser.Factory.USE_ANTLR = true;
        MVELTranspiler.ENABLE_REWRITE = false;
    }
```

Add imports:

```java
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.mvel3.parser.MvelParser;
import org.mvel3.transpiler.MVELTranspiler;
```

Add the `@Nested` class before the helper methods:

```java
    @Nested
    class WithSentinelResolver {

        private final SentinelExpressionTypeResolver sentinelResolver = new SentinelExpressionTypeResolver();

        @Test
        void systemDot() {
            assertResolvesTo(sentinelResolver, """
                    unit MyUnit;

                    rule R1 {
                        var a : /as,
                        do { System.
                    """, 4, 16, "java.lang.System");
        }

        @Test
        void systemOutDot() {
            assertResolvesTo(sentinelResolver, """
                    unit MyUnit;

                    rule R1 {
                        var a : /as,
                        do { System.out.
                    """, 4, 20, "java.io.PrintStream");
        }

        @Test
        void bigDecimalLiteral() {
            assertResolvesTo(sentinelResolver, """
                    unit MyUnit;

                    rule R1 {
                        var a : /as,
                        do { 10.5B.
                    """, 4, 15, "java.math.BigDecimal");
        }

        @Test
        void inlineCastSimple() {
            assertResolvesTo(sentinelResolver, """
                    import java.util.ArrayList;

                    unit MyUnit;

                    rule R1 {
                        var a : /as,
                        do { list#ArrayList#.
                    """, 6, 25, "java.util.ArrayList");
        }

        @Test
        void caretInMiddleOfDocument() {
            assertResolvesTo(sentinelResolver, """
                    unit MyUnit;

                    rule R1 {
                        var a : /as,
                        do { System.out.
                    }

                    rule R2 {
                        var b : /bs,
                        do { System.out.println("hello"); }
                    }
                    """, 4, 20, "java.io.PrintStream");
        }
    }
```

Refactor the `assertResolvesTo` helper to accept a resolver parameter:

```java
    private void assertResolvesTo(String text, int line, int col, String expectedFqcn) {
        assertResolvesTo(resolver, text, line, col, expectedFqcn);
    }

    private void assertResolvesTo(ExpressionTypeResolver res, String text, int line, int col, String expectedFqcn) {
        Optional<SemanticType> result = resolveAt(res, text, line, col);
        assertThat(result)
                .as("Expected type %s at line %d col %d", expectedFqcn, line, col)
                .isPresent();
        assertThat(result.get().resolvedType().describe()).isEqualTo(expectedFqcn);
    }

    private Optional<SemanticType> resolveAt(String text, int line, int col) {
        return resolveAt(resolver, text, line, col);
    }

    private Optional<SemanticType> resolveAt(ExpressionTypeResolver res, String text, int line, int col) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        ParseTree tree = parser.drlxStart();

        int caretTokenIndex = computeTokenIndex(parser, line + 1, col);
        CompletionExpression expr = CompletionExpression.fromCaretPosition(parser, tree, caretTokenIndex);

        return res.resolve(expr, VisibleSymbols.empty(), model);
    }
```

- [ ] **Step 2: Run all characterization tests**

Run: `mvn -pl drlx-completion test -Dtest=ExpressionTypeResolverCharacterizationTest`
Expected: All enabled tests in both outer class and `WithSentinelResolver` pass

- [ ] **Step 3: Run full test suite**

Run: `mvn -pl drlx-completion test`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add drlx-completion/src/test/java/org/drools/drlx/completion/semantic/ExpressionTypeResolverCharacterizationTest.java
git commit -m "test: verify characterization tests with SentinelExpressionTypeResolver"
```

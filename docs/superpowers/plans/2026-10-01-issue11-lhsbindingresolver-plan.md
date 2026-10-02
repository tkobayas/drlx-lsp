# LhsBindingResolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the `createParser → drlxStart() → createContext → buildVisibleSymbols()` boilerplate that is duplicated across Helper classes by extracting a thin façade `LhsBindingResolver`. Net reduction: 3 boilerplate blocks (Definition ×1, Rename ×2).

**Architecture:** A final utility class `LhsBindingResolver` in the `semantic` package wraps the existing pipeline. `resolve(text, tokenIndex, model)` is used by caret-aware Helpers. `resolveAll(text, model)` passes `Integer.MAX_VALUE` as token index for whole-file consumers. Parser construction is inline (3 lines) rather than calling `DrlxHoverHelper.createParser()`, which is package-private and inaccessible from the `semantic` package. All real work stays in `CompletionContext.buildVisibleSymbols()`.

**Tech Stack:** Java 21, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-10-01-issue11-lhsbindingresolverspec.md`

## Global Constraints

- Java 21 language features
- No new dependencies
- Package: `org.drools.drlx.completion.semantic`
- Build: `mvn -pl drlx-completion -am install -DskipTests -q` before running tests

## Review Focus

1. **`DrlxHoverHelper` is out of scope** — `ctx` is needed for `imports()`, `resolveEntryPointType()`, and `resolveDotHover`; replacing only `buildVisibleSymbols()` would keep `ctx` and add a second parse pass. Do not touch this Helper.
2. **`DrlxReferencesHelper` is out of scope** — `ctx` is passed to `findImportTypeReferences`; same double-parse argument applies. Do not touch this Helper.
3. **No `DrlxHoverHelper.createParser()` call** — that method is package-private. `LhsBindingResolver.resolve()` must build the parser inline: `new ANTLRInputStream → DrlxLexer → CommonTokenStream → DrlxParser`.
4. **Null / empty text guard** — both `LhsBindingResolver` methods must return `VisibleSymbols.empty()` (not throw) when `text` is null or blank.
5. **`resolveAll` uses `Integer.MAX_VALUE`** — this causes `CompletionContext` to treat every declaration as "before the caret" and include all bindings. Confirm this is the same value already used in `DrlxInlayHintHelper`.
6. **No observable behaviour change** — all existing tests must pass unchanged after Task 2.

---

### Task 1: Create LhsBindingResolver with unit tests

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/LhsBindingResolver.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/LhsBindingResolverTest.java`

**Interfaces:**
- Consumes: `WorkspaceSemanticModel.createContext(...)`, `CompletionContext.buildVisibleSymbols()`
- Produces: `LhsBindingResolver.resolve(String, int, WorkspaceSemanticModel) → VisibleSymbols`, `LhsBindingResolver.resolveAll(String, WorkspaceSemanticModel) → VisibleSymbols`

- [ ] **Step 1: Write the failing tests**

Create `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/LhsBindingResolverTest.java`:

```java
package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LhsBindingResolverTest {

    private static final String DRLX_TEXT = """
            import org.drools.drlx.domain.MyUnit;
            unit MyUnit;
            rule R {
                var p : /persons,
                do { p. }
            }
            """;

    private static WorkspaceSemanticModel model() {
        return new WorkspaceSemanticModel(new CurrentClassloaderProvider());
    }

    /** Find the token index for the first occurrence of the given identifier text. */
    private static int tokenIndexOf(String text, String identifier) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        for (var t : tokens.getTokens()) {
            if (t.getType() == DrlxLexer.IDENTIFIER && identifier.equals(t.getText())) {
                return t.getTokenIndex();
            }
        }
        return 0;
    }

    @Test
    void resolve_returnsBindingAtCaretPosition() {
        int tokenIndex = tokenIndexOf(DRLX_TEXT, "p");
        VisibleSymbols symbols = LhsBindingResolver.resolve(DRLX_TEXT, tokenIndex, model());
        assertThat(symbols.lookup("p")).isPresent();
    }

    @Test
    void resolve_nullText_returnsEmpty() {
        VisibleSymbols symbols = LhsBindingResolver.resolve(null, 0, model());
        assertThat(symbols.isEmpty()).isTrue();
    }

    @Test
    void resolve_emptyText_returnsEmpty() {
        VisibleSymbols symbols = LhsBindingResolver.resolve("", 0, model());
        assertThat(symbols.isEmpty()).isTrue();
    }

    @Test
    void resolveAll_returnsBindingWithoutTokenIndex() {
        VisibleSymbols symbols = LhsBindingResolver.resolveAll(DRLX_TEXT, model());
        assertThat(symbols.lookup("p")).isPresent();
    }

    @Test
    void resolveAll_nullText_returnsEmpty() {
        VisibleSymbols symbols = LhsBindingResolver.resolveAll(null, model());
        assertThat(symbols.isEmpty()).isTrue();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl drlx-completion test -Dtest="org.drools.drlx.completion.semantic.LhsBindingResolverTest" -q`

Expected: compilation error — `LhsBindingResolver` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/LhsBindingResolver.java`:

```java
package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;

/**
 * Façade for resolving LHS variable bindings in a DRLX source text.
 *
 * <p>Encapsulates the parse → context → buildVisibleSymbols() pipeline so
 * that callers (Definition, Rename) share a single entry point rather than
 * duplicating the three-step boilerplate.
 *
 * <p>Note: does NOT call {@code DrlxHoverHelper.createParser()} — that method
 * is package-private in {@code org.drools.drlx.completion} and is not
 * accessible from this package.
 */
public final class LhsBindingResolver {

    private LhsBindingResolver() {}

    /**
     * Returns the bindings visible at {@code tokenIndex} within {@code text},
     * scoped to the enclosing rule (caret-aware).
     *
     * @param text        full source text of the .drlx file
     * @param tokenIndex  token-stream index of the cursor token
     * @param model       workspace semantic model
     * @return            visible symbols at the position; never null
     */
    public static VisibleSymbols resolve(String text, int tokenIndex,
                                          WorkspaceSemanticModel model) {
        if (text == null || text.isEmpty()) {
            return VisibleSymbols.empty();
        }
        DrlxParser parser = createParser(text);
        ParseTree parseTree = parser.drlxStart();
        CompletionContext ctx = model.createContext(parser, parseTree, tokenIndex);
        return ctx.buildVisibleSymbols();
    }

    /**
     * Returns all bindings declared anywhere in {@code text}, across all
     * rules, without caret-position filtering.
     *
     * @param text   full source text of the .drlx file
     * @param model  workspace semantic model
     * @return       all visible symbols; never null
     */
    public static VisibleSymbols resolveAll(String text, WorkspaceSemanticModel model) {
        return resolve(text, Integer.MAX_VALUE, model);
    }

    private static DrlxParser createParser(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        return new DrlxParser(tokens);
    }
}
```

- [ ] **Step 4: Build and run unit tests**

Run: `mvn -pl drlx-completion -am install -DskipTests -q`
Run: `mvn -pl drlx-completion test -Dtest="org.drools.drlx.completion.semantic.LhsBindingResolverTest" -q`

Expected: all 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/LhsBindingResolver.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/semantic/LhsBindingResolverTest.java
git commit -m "feat: add LhsBindingResolver façade for binding resolution pipeline (#11)"
```

---

### Task 2: Wire LhsBindingResolver into Definition and Rename helpers

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDefinitionHelper.java`
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxRenameHelper.java`

Note: `DrlxHoverHelper` and `DrlxReferencesHelper` are **not** modified — both need their `CompletionContext` for purposes other than `buildVisibleSymbols()`, so wiring would add a second parse pass with no net benefit.

**Interfaces:**
- Consumes: `LhsBindingResolver.resolve(String, int, WorkspaceSemanticModel)` (from Task 1)
- Produces: no new interfaces

- [ ] **Step 1: Modify DrlxDefinitionHelper.java**

In `definition()`, replace lines 32–44:

```java
// Before:
DrlxParser parser = DrlxHoverHelper.createParser(text);
ParseTree parseTree = parser.drlxStart();
CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();

Token token = DrlxHoverHelper.findTokenAt(tokens, position);
if (token == null || token.getType() != DrlxLexer.IDENTIFIER) {
    return Collections.emptyList();
}
String word = token.getText();
int tokenIndex = token.getTokenIndex();

CompletionContext ctx = model.createContext(parser, parseTree, tokenIndex);
VisibleSymbols symbols = ctx.buildVisibleSymbols();
```

```java
// After:
DrlxParser parser = DrlxHoverHelper.createParser(text);
ParseTree parseTree = parser.drlxStart();
CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();

Token token = DrlxHoverHelper.findTokenAt(tokens, position);
if (token == null || token.getType() != DrlxLexer.IDENTIFIER) {
    return Collections.emptyList();
}
String word = token.getText();
int tokenIndex = token.getTokenIndex();

VisibleSymbols symbols = LhsBindingResolver.resolve(text, tokenIndex, model);
```

Also update the `resolveImportDefinition` call below — it uses `parseTree` which is still available from the local parse. No further changes needed.

Add import: `import org.drools.drlx.completion.semantic.LhsBindingResolver;`
Remove import if now unused: `import org.drools.drlx.completion.semantic.CompletionContext;`

- [ ] **Step 2: Modify DrlxRenameHelper.java**

In `prepare()`, replace lines 50–51:

```java
// Before:
CompletionContext ctx = model.createContext(parser, parseTree, tokenIndex);
VisibleSymbols symbols = ctx.buildVisibleSymbols();
```

```java
// After:
VisibleSymbols symbols = LhsBindingResolver.resolve(text, tokenIndex, model);
```

In `rename()`, replace lines 83–84:

```java
// Before:
CompletionContext ctx = model.createContext(parser, parseTree, tokenIndex);
VisibleSymbols symbols = ctx.buildVisibleSymbols();
```

```java
// After:
VisibleSymbols symbols = LhsBindingResolver.resolve(text, tokenIndex, model);
```

Add import: `import org.drools.drlx.completion.semantic.LhsBindingResolver;`
Remove imports if now unused: `import org.drools.drlx.completion.semantic.CompletionContext;`

- [ ] **Step 3: Build and run full test suite**

Run: `mvn -pl drlx-completion -am install -DskipTests -q`
Run: `mvn -pl drlx-completion test -q`

Expected: all tests pass — no regressions in `DrlxDefinitionHelperTest`, `DrlxRenameHelperTest`, `DrlxHoverHelperTest`, `DrlxReferencesHelperTest`.

- [ ] **Step 4: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDefinitionHelper.java \
       drlx-completion/src/main/java/org/drools/drlx/completion/DrlxRenameHelper.java
git commit -m "refactor: wire LhsBindingResolver into Definition and Rename helpers (#11)"
```

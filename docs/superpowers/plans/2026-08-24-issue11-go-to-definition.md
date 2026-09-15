# Go to Definition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `textDocument/definition` support to drlx-lsp — navigate from identifier usage to its declaration (bindings and import types, in-file only).

**Architecture:** Extend `VisibleSymbols` to carry source positions via a new `SymbolEntry` record. A new `DrlxDefinitionHelper` utility class (following the `DrlxHoverHelper` pattern) resolves identifiers through two tiers: binding lookup via `VisibleSymbols.lookupEntry()`, then import type lookup via parse tree walking. Wired into the LSP server as `textDocument/definition`.

**Tech Stack:** Java 17, ANTLR4, LSP4J, JavaParser (type resolution), JUnit 5 + AssertJ

**Spec:** `docs/superpowers/specs/2026-08-24-issue11-go-to-definition-design.md`

## Global Constraints

- Source repo: `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`
- Build before testing: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q`
- Run completion tests: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test`
- Run server tests: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-lsp-server test`
- ANTLR tokens use 1-based lines; LSP and TokenRange use 0-based lines — always subtract 1 when converting
- Test domain classes: `org.drools.drlx.domain.{MyUnit, Person, Address, Country}`
- All new files go in package `org.drools.drlx.completion.semantic` (records and VisibleSymbols) or `org.drools.drlx.completion` (definition helper and tests)

---

### Task 1: TokenRange, SymbolEntry, and VisibleSymbols extension

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/TokenRange.java`
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/SymbolEntry.java`
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/VisibleSymbols.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `TokenRange(int startLine, int startCol, int endLine, int endCol)` — record, 0-based positions
  - `TokenRange.fromAntlrToken(Token token, int length)` — factory converting ANTLR 1-based to 0-based
  - `TokenRange.toLspRange()` — converts to `org.eclipse.lsp4j.Range`
  - `SymbolEntry(SemanticType type, TokenRange range)` — record, range may be null
  - `VisibleSymbols.lookupEntry(String name)` — returns `Optional<SymbolEntry>`
  - `VisibleSymbols.Builder.add(String name, SemanticType type, TokenRange range)` — new overload

- [ ] **Step 1: Create TokenRange record**

```java
// drlx-completion/src/main/java/org/drools/drlx/completion/semantic/TokenRange.java
package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.Token;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public record TokenRange(int startLine, int startCol, int endLine, int endCol) {

    public static TokenRange fromAntlrToken(Token token, int length) {
        int line = token.getLine() - 1;
        int col = token.getCharPositionInLine();
        return new TokenRange(line, col, line, col + length);
    }

    public Range toLspRange() {
        return new Range(new Position(startLine, startCol), new Position(endLine, endCol));
    }
}
```

- [ ] **Step 2: Create SymbolEntry record**

```java
// drlx-completion/src/main/java/org/drools/drlx/completion/semantic/SymbolEntry.java
package org.drools.drlx.completion.semantic;

public record SymbolEntry(SemanticType type, TokenRange range) {
}
```

- [ ] **Step 3: Modify VisibleSymbols to use SymbolEntry internally**

Change the internal map from `Map<String, SemanticType>` to `Map<String, SymbolEntry>`. Maintain backward compatibility for `lookup()` and `entries()`. Add `lookupEntry()` and a new `add()` overload.

In `VisibleSymbols.java`:

Replace the field and constructor:
```java
private final Map<String, SymbolEntry> symbols;

private VisibleSymbols(Map<String, SymbolEntry> symbols) {
    this.symbols = symbols;
}
```

Replace `lookup`:
```java
public Optional<SemanticType> lookup(String name) {
    SymbolEntry entry = symbols.get(name);
    return entry != null ? Optional.of(entry.type()) : Optional.empty();
}
```

Add `lookupEntry`:
```java
public Optional<SymbolEntry> lookupEntry(String name) {
    return Optional.ofNullable(symbols.get(name));
}
```

Replace `entries` to maintain backward compatibility (returns `SemanticType` values):
```java
public Iterable<Map.Entry<String, SemanticType>> entries() {
    Map<String, SemanticType> typeMap = new LinkedHashMap<>();
    symbols.forEach((k, v) -> typeMap.put(k, v.type()));
    return typeMap.entrySet();
}
```

Update the `Builder`:
```java
public static class Builder {
    private final Map<String, SymbolEntry> map = new LinkedHashMap<>();

    public Builder add(String name, SemanticType type) {
        map.put(name, new SymbolEntry(type, null));
        return this;
    }

    public Builder add(String name, SemanticType type, TokenRange range) {
        map.put(name, new SymbolEntry(type, range));
        return this;
    }

    public VisibleSymbols build() {
        if (map.isEmpty()) {
            return EMPTY;
        }
        return new VisibleSymbols(new LinkedHashMap<>(map));
    }
}
```

Update the `EMPTY` singleton:
```java
private static final VisibleSymbols EMPTY = new VisibleSymbols(Collections.emptyMap());
```

- [ ] **Step 4: Build and run all existing tests to verify backward compatibility**

Run:
```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test
```
Expected: All 155+ existing tests pass — the VisibleSymbols API changes are backward compatible.

- [ ] **Step 5: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add \
  drlx-completion/src/main/java/org/drools/drlx/completion/semantic/TokenRange.java \
  drlx-completion/src/main/java/org/drools/drlx/completion/semantic/SymbolEntry.java \
  drlx-completion/src/main/java/org/drools/drlx/completion/semantic/VisibleSymbols.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: add TokenRange, SymbolEntry and extend VisibleSymbols for go-to-definition"
```

---

### Task 2: Capture token positions in CompletionContext extract methods

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java`

**Interfaces:**
- Consumes: `TokenRange.fromAntlrToken(Token, int)`, `VisibleSymbols.Builder.add(String, SemanticType, TokenRange)` from Task 1
- Produces: `buildVisibleSymbols()` now returns `VisibleSymbols` with populated `TokenRange` for each source-level binding

- [ ] **Step 1: Modify extractRuleParameters to capture positions**

In `CompletionContext.java` line 170-179, change `builder.add(varName, st)` to also pass the token range. The binding name token is `param.identifier().getStart()`:

```java
private void extractRuleParameters(RuleDeclarationContext rule, VisibleSymbols.Builder builder) {
    RuleParameterListContext paramList = rule.ruleParameterList();
    if (paramList == null) return;
    for (RuleParameterContext param : paramList.ruleParameter()) {
        if (param.getStart().getTokenIndex() >= caretTokenIndex) continue;
        String typeName = param.typeType().getText();
        String varName = param.identifier().getText();
        SemanticType st = resolveTypeToSemanticType(typeName);
        if (st != null) {
            TokenRange range = TokenRange.fromAntlrToken(param.identifier().getStart(), varName.length());
            builder.add(varName, st, range);
        }
    }
}
```

- [ ] **Step 2: Modify collectBoundOopathFromTree to capture positions**

In `CompletionContext.java` lines 190-221. For OOPath bindings, the binding name is `bound.identifier(1)`. For accumulate items, the binding name is `accItem.identifier()`:

```java
private void collectBoundOopathFromTree(ParseTree node, VisibleSymbols.Builder builder) {
    if (node instanceof BoundOopathContext bound) {
        if (bound.getStart().getTokenIndex() >= caretTokenIndex) return;
        if (bound.identifier().size() >= 2) {
            String typeName = bound.identifier(0).getText();
            String bindName = bound.identifier(1).getText();
            TokenRange range = TokenRange.fromAntlrToken(bound.identifier(1).getStart(), bindName.length());
            if (!"var".equals(typeName)) {
                SemanticType st = resolveTypeToSemanticType(typeName);
                if (st != null) builder.add(bindName, st, range);
            } else {
                SemanticType inferred = inferVarBindingType(bound);
                if (inferred != null) builder.add(bindName, inferred, range);
            }
        }
        return;
    }
    if (node instanceof AccumulateItemContext accItem) {
        if (accItem.getStart().getTokenIndex() >= caretTokenIndex) return;
        String bindName = accItem.identifier().getText();
        TokenRange range = TokenRange.fromAntlrToken(accItem.identifier().getStart(), bindName.length());
        if (accItem.typeType() != null) {
            SemanticType st = resolveTypeToSemanticType(accItem.typeType().getText());
            if (st != null) builder.add(bindName, st, range);
        } else if (accItem.VAR() != null) {
            SemanticType inferred = inferAccumulateResultType(accItem);
            if (inferred != null) builder.add(bindName, inferred, range);
        }
        return;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
        collectBoundOopathFromTree(node.getChild(i), builder);
    }
}
```

- [ ] **Step 3: Modify extractBindingsFromDrlxExpressions to capture positions**

In `CompletionContext.java` lines 297-312. The binding token is `drlxExpr.bind`. Check the generated `DrlxExpressionContext` class to determine if `bind` is a `Token` or `IdentifierContext`:
- If `Token`: use `TokenRange.fromAntlrToken(drlxExpr.bind, bindName.length())`
- If `IdentifierContext`: use `TokenRange.fromAntlrToken(drlxExpr.bind.getStart(), bindName.length())`

```java
private void extractBindingsFromDrlxExpressions(List<DrlxExpressionContext> drlxExprs, SemanticType ownerType, VisibleSymbols.Builder builder) {
    if (drlxExprs == null) return;
    for (DrlxExpressionContext drlxExpr : drlxExprs) {
        if (drlxExpr.getStart().getTokenIndex() >= caretTokenIndex) continue;
        if (drlxExpr.bind != null && drlxExpr.expression() != null) {
            String bindName = drlxExpr.bind.getText();
            // Use drlxExpr.bind.getStart() if bind is IdentifierContext,
            // or drlxExpr.bind directly if bind is Token
            TokenRange range = TokenRange.fromAntlrToken(/* bind token */, bindName.length());
            String propName = extractLeadingPropertyName(drlxExpr.expression());
            if (propName != null) {
                SemanticType propType = resolvePropertyType(ownerType, propName);
                if (propType != null) {
                    builder.add(bindName, propType, range);
                }
            }
        }
    }
}
```

- [ ] **Step 4: Modify extractFromLocalVarDecl to capture positions**

In `CompletionContext.java` lines 852-868. Two paths:

For typed declarations (`TypeType varName = ...`), the variable name is at `decl.variableDeclaratorId().identifier()`:
```java
String varName = decl.variableDeclaratorId().identifier().getText();
TokenRange range = TokenRange.fromAntlrToken(decl.variableDeclaratorId().identifier().getStart(), varName.length());
builder.add(varName, st, range);
```

For var declarations (`var varName = ...`), the variable name is at `localVar.identifier()`:
```java
String varName = localVar.identifier().getText();
TokenRange range = TokenRange.fromAntlrToken(localVar.identifier().getStart(), varName.length());
builder.add(varName, st, range);
```

Note: `extractOopathConstraintProperties` (line 691) adds type properties from Java reflection, not source-level bindings. Leave it using `builder.add(name, type)` with null range — these have no in-file definition.

- [ ] **Step 5: Add TokenRange import**

Add to the imports in `CompletionContext.java`:
```java
import org.drools.drlx.completion.semantic.TokenRange;  // if not already in same package
```

Note: `CompletionContext` is in `org.drools.drlx.completion.semantic` — same package as `TokenRange`, so no import needed.

- [ ] **Step 6: Build and run all tests**

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test
```
Expected: All existing tests still pass. The additional `TokenRange` parameter on `builder.add()` doesn't change any existing behavior.

- [ ] **Step 7: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add \
  drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: capture token positions in CompletionContext extract methods"
```

---

### Task 3: DrlxDefinitionHelper — binding definitions

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDefinitionHelper.java`
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxHoverHelper.java` (make `createParser` and `findTokenAt` package-private)
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDefinitionHelperTest.java`

**Interfaces:**
- Consumes: `VisibleSymbols.lookupEntry(String)` → `Optional<SymbolEntry>`, `SymbolEntry.range()` → `TokenRange`, `TokenRange.toLspRange()` → `Range`, `DrlxHoverHelper.createParser(String)` → `DrlxParser`, `DrlxHoverHelper.findTokenAt(CommonTokenStream, Position)` → `Token`
- Produces: `DrlxDefinitionHelper.definition(String uri, String text, Position position, WorkspaceSemanticModel model)` → `List<Location>`

- [ ] **Step 1: Make createParser and findTokenAt package-private in DrlxHoverHelper**

In `DrlxHoverHelper.java`, change `private static` to `static` (package-private) for:
- `createParser(String text)` at line 252
- `findTokenAt(CommonTokenStream tokens, Position position)` at line 222

```java
// Was: private static DrlxParser createParser(String text)
static DrlxParser createParser(String text) { ... }

// Was: private static Token findTokenAt(CommonTokenStream tokens, Position position)
static Token findTokenAt(CommonTokenStream tokens, Position position) { ... }
```

- [ ] **Step 2: Write failing tests for binding definitions**

Create `DrlxDefinitionHelperTest.java`:

```java
package org.drools.drlx.completion;

import java.util.List;

import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxDefinitionHelperTest {

    private final WorkspaceSemanticModel model =
            new WorkspaceSemanticModel(new CurrentClassloaderProvider());

    private static final String URI = "file:///test.drlx";

    @Test
    void oopathBinding() {
        // Line 0: import org.drools.drlx.domain.Person;
        // Line 1: import org.drools.drlx.domain.MyUnit;
        // Line 2:
        // Line 3: unit MyUnit;
        // Line 4:
        // Line 5: rule R1 {
        // Line 6:     var p : /persons,
        // Line 7:     do { p }
        // Line 8: }
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p }
                }
                """;
        // Cursor on "p" in "do { p }" — line 7, char 9
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(7, 9), model);

        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).getUri()).isEqualTo(URI);
        // "p" in "var p : /persons" — line 6, char 8
        assertThat(defs.get(0).getRange().getStart().getLine()).isEqualTo(6);
        assertThat(defs.get(0).getRange().getStart().getCharacter()).isEqualTo(8);
    }

    @Test
    void constraintBinding() {
        // Line 0: import org.drools.drlx.domain.Person;
        // Line 1: import org.drools.drlx.domain.Address;
        // Line 2: import org.drools.drlx.domain.MyUnit;
        // Line 3:
        // Line 4: unit MyUnit;
        // Line 5:
        // Line 6: rule R1 {
        // Line 7:     var p : /persons[$addr : address],
        // Line 8:     do { $addr }
        // Line 9: }
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons[$addr : address],
                    do { $addr }
                }
                """;
        // Cursor on "$addr" in "do { $addr }" — line 8, char 9
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(8, 9), model);

        assertThat(defs).hasSize(1);
        // "$addr" in "[$addr : address]" — line 7, char 24
        assertThat(defs.get(0).getRange().getStart().getLine()).isEqualTo(7);
    }

    @Test
    void ruleParameter() {
        // Line 0: import org.drools.drlx.domain.Person;
        // Line 1:
        // Line 2: rule R1(Person p) {
        // Line 3:     do { p }
        // Line 4: }
        String text = """
                import org.drools.drlx.domain.Person;

                rule R1(Person p) {
                    do { p }
                }
                """;
        // Cursor on "p" in "do { p }" — line 3, char 9
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(3, 9), model);

        assertThat(defs).hasSize(1);
        // "p" in "rule R1(Person p)" — line 2, char 19
        assertThat(defs.get(0).getRange().getStart().getLine()).isEqualTo(2);
        assertThat(defs.get(0).getRange().getStart().getCharacter()).isEqualTo(19);
    }

    @Test
    void rhsLocalVariable() {
        // Line 0: import org.drools.drlx.domain.Person;
        // Line 1: import org.drools.drlx.domain.Address;
        // Line 2: import org.drools.drlx.domain.MyUnit;
        // Line 3:
        // Line 4: unit MyUnit;
        // Line 5:
        // Line 6: rule R1 {
        // Line 7:     var p : /persons,
        // Line 8:     do {
        // Line 9:         Address x = p.getAddress();
        // Line 10:        x }
        // Line 11: }
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do {
                        Address x = p.getAddress();
                        x }
                }
                """;
        // Cursor on "x" in "x }" — line 10, char 8
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(10, 8), model);

        assertThat(defs).hasSize(1);
        // "x" in "Address x = ..." — line 9, char 16
        assertThat(defs.get(0).getRange().getStart().getLine()).isEqualTo(9);
        assertThat(defs.get(0).getRange().getStart().getCharacter()).isEqualTo(16);
    }

    @Test
    void cursorOnDefinitionSite() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p }
                }
                """;
        // Cursor on "p" in "var p : /persons" — line 6, char 8 (the definition itself)
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(6, 8), model);

        assertThat(defs).isEmpty();
    }

    @Test
    void keywordReturnsEmpty() {
        String text = """
                import org.drools.drlx.domain.Person;

                rule R1(Person p) {
                    do { p }
                }
                """;
        // Cursor on "rule" keyword — line 2, char 0
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(2, 0), model);

        assertThat(defs).isEmpty();
    }

    @Test
    void unknownSymbolReturnsEmpty() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { unknown }
                }
                """;
        // Cursor on "unknown" — line 7, char 9
        List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(7, 9), model);

        assertThat(defs).isEmpty();
    }

    @Test
    void nullTextReturnsEmpty() {
        List<Location> defs = DrlxDefinitionHelper.definition(URI, null, new Position(0, 0), model);
        assertThat(defs).isEmpty();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxDefinitionHelperTest"
```
Expected: FAIL — `DrlxDefinitionHelper` class doesn't exist yet.

- [ ] **Step 4: Implement DrlxDefinitionHelper**

```java
package org.drools.drlx.completion;

import java.util.Collections;
import java.util.List;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.drools.drlx.completion.semantic.CompletionContext;
import org.drools.drlx.completion.semantic.SymbolEntry;
import org.drools.drlx.completion.semantic.TokenRange;
import org.drools.drlx.completion.semantic.VisibleSymbols;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.antlr.v4.runtime.tree.ParseTree;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;

import java.util.Optional;

public class DrlxDefinitionHelper {

    private DrlxDefinitionHelper() {
    }

    public static List<Location> definition(String uri, String text, Position position, WorkspaceSemanticModel model) {
        if (text == null || text.isEmpty() || position == null) {
            return Collections.emptyList();
        }

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
        Optional<SymbolEntry> entry = symbols.lookupEntry(word);
        if (entry.isPresent() && entry.get().range() != null) {
            TokenRange defRange = entry.get().range();
            if (position.getLine() == defRange.startLine()
                    && position.getCharacter() >= defRange.startCol()
                    && position.getCharacter() < defRange.endCol()) {
                return Collections.emptyList();
            }
            return List.of(new Location(uri, defRange.toLspRange()));
        }

        return Collections.emptyList();
    }
}
```

- [ ] **Step 5: Build and run tests**

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxDefinitionHelperTest"
```
Expected: All binding tests pass. If character positions are wrong, adjust the test assertions to match the actual token positions (count carefully in the stripped text block).

- [ ] **Step 6: Run full test suite to check for regressions**

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test
```
Expected: All tests pass (existing + new).

- [ ] **Step 7: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add \
  drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDefinitionHelper.java \
  drlx-completion/src/main/java/org/drools/drlx/completion/DrlxHoverHelper.java \
  drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDefinitionHelperTest.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: add DrlxDefinitionHelper with binding definition resolution"
```

---

### Task 4: Import type resolution and LSP wiring

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDefinitionHelper.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDefinitionHelperTest.java`
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java:89`
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java:102`
- Modify: `drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspDocumentServiceTest.java`

**Interfaces:**
- Consumes: `DrlxDefinitionHelper.definition(String, String, Position, WorkspaceSemanticModel)` from Task 3, `CompletionContext.imports()` → `Set<String>`
- Produces: Import resolution tier added to `definition()`, LSP `textDocument/definition` endpoint wired

- [ ] **Step 1: Write failing tests for import type definition**

Add to `DrlxDefinitionHelperTest.java`:

```java
@Test
void importType() {
    // Line 0: import org.drools.drlx.domain.Person;
    // Line 1: import org.drools.drlx.domain.MyUnit;
    // Line 2:
    // Line 3: unit MyUnit;
    // Line 4:
    // Line 5: rule R1 {
    // Line 6:     var p : /persons,
    // Line 7:     do { p }
    // Line 8: }
    String text = """
            import org.drools.drlx.domain.Person;
            import org.drools.drlx.domain.MyUnit;

            unit MyUnit;

            rule R1 {
                var p : /persons,
                do { p }
            }
            """;
    // Cursor on "Person" in "var p : /persons" is not a type usage.
    // Instead, test on the type in a rule parameter context:
    // Actually, "persons" is an entry point, not a type name.
    // Use a rule parameter to reference Person as a type.
    String text2 = """
            import org.drools.drlx.domain.Person;

            rule R1(Person p) {
                do { p }
            }
            """;
    // Cursor on "Person" in "rule R1(Person p)" — line 2, char 8
    List<Location> defs = DrlxDefinitionHelper.definition(URI, text2, new Position(2, 8), model);

    assertThat(defs).hasSize(1);
    // "Person" in import line — line 0
    assertThat(defs.get(0).getRange().getStart().getLine()).isEqualTo(0);
}

@Test
void cursorOnImportLine() {
    String text = """
            import org.drools.drlx.domain.Person;

            rule R1(Person p) {
                do { p }
            }
            """;
    // Cursor on "Person" in the import line — line 0, char 31
    List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(0, 31), model);

    assertThat(defs).isEmpty();
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxDefinitionHelperTest#importType+cursorOnImportLine"
```
Expected: `importType` FAILS (returns empty — no import resolution yet). `cursorOnImportLine` may pass (returns empty, which is correct).

- [ ] **Step 3: Add import resolution to DrlxDefinitionHelper**

Add a private method `resolveImportDefinition` and call it as tier 2 in the `definition()` method.

The import lookup walks the parse tree to find `ImportDeclarationContext` nodes and matches the simple name. The simple name is the last segment of the qualified name. The token position for the import comes from parsing the `qualifiedName()`.

Add to `DrlxDefinitionHelper.java`:

```java
import org.drools.drlx.parser.DrlxParser.DrlxCompilationUnitContext;
import org.drools.drlx.parser.DrlxParser.ImportDeclarationContext;

// In definition() method, after the binding lookup returns empty, before the final return:
List<Location> importDef = resolveImportDefinition(word, position, parseTree, uri);
if (!importDef.isEmpty()) {
    return importDef;
}

// New private method:
private static List<Location> resolveImportDefinition(String word, Position position, ParseTree parseTree, String uri) {
    DrlxCompilationUnitContext cu = findCompilationUnit(parseTree);
    if (cu == null) return Collections.emptyList();

    for (ImportDeclarationContext imp : cu.importDeclaration()) {
        if (imp.qualifiedName() == null) continue;
        String fqcn = imp.qualifiedName().getText();
        String simpleName = fqcn.contains(".") ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
        if (!simpleName.equals(word)) continue;

        // Check if cursor is on the import line itself
        int importLine = imp.getStart().getLine() - 1; // ANTLR 1-based → 0-based
        if (position.getLine() == importLine) {
            return Collections.emptyList();
        }

        // Find the position of the simple name token (last identifier in qualifiedName)
        var identifiers = imp.qualifiedName().identifier();
        var lastIdent = identifiers.get(identifiers.size() - 1);
        Token nameToken = lastIdent.getStart();
        TokenRange range = TokenRange.fromAntlrToken(nameToken, simpleName.length());
        return List.of(new Location(uri, range.toLspRange()));
    }
    return Collections.emptyList();
}

private static DrlxCompilationUnitContext findCompilationUnit(ParseTree node) {
    if (node instanceof DrlxCompilationUnitContext cu) return cu;
    for (int i = 0; i < node.getChildCount(); i++) {
        DrlxCompilationUnitContext found = findCompilationUnit(node.getChild(i));
        if (found != null) return found;
    }
    return null;
}
```

- [ ] **Step 4: Run definition tests**

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxDefinitionHelperTest"
```
Expected: All tests pass. If `importType` character position is wrong, adjust — count the position of "Person" in `import org.drools.drlx.domain.Person;` after text-block stripping.

- [ ] **Step 5: Wire definition into DrlxLspServer**

In `DrlxLspServer.java` at line 89, add after `setHoverProvider(true)`:

```java
initializeResult.getCapabilities().setDefinitionProvider(true);
```

- [ ] **Step 6: Wire definition into DrlxLspDocumentService**

In `DrlxLspDocumentService.java`, add the import:
```java
import org.drools.drlx.completion.DrlxDefinitionHelper;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
```

Add the override after the `hover()` method (after line 102):
```java
@Override
public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
    return CompletableFuture.supplyAsync(() -> {
        String uri = params.getTextDocument().getUri();
        String text = sourcesMap.get(uri);
        if (text == null) return Either.forLeft(Collections.emptyList());
        List<Location> locations = DrlxDefinitionHelper.definition(uri, text, params.getPosition(), model);
        return Either.forLeft(locations);
    });
}
```

Add import for `Collections` if not already present.

- [ ] **Step 7: Write server test**

Add to `DrlxLspDocumentServiceTest.java`:

```java
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

@Test
void definition_oopathBinding() throws Exception {
    String drlx = """
            import org.drools.drlx.domain.Person;
            import org.drools.drlx.domain.MyUnit;

            unit MyUnit;

            rule R1 {
                var p : /persons,
                do { p }
            }
            """;

    DrlxLspDocumentService service = getDrlxLspDocumentService(drlx);

    DefinitionParams params = new DefinitionParams();
    params.setTextDocument(new TextDocumentIdentifier("myDocument"));
    params.setPosition(new Position(7, 9)); // "p" in "do { p }"

    Either<List<? extends Location>, List<? extends LocationLink>> result = service.definition(params).get();

    assertThat(result.getLeft()).hasSize(1);
    assertThat(result.getLeft().get(0).getRange().getStart().getLine()).isEqualTo(6);
}
```

- [ ] **Step 8: Build and run all tests**

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-lsp-server test
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test
```
Expected: All tests pass — completion (155+), hover (10+), definition (10), server (7+).

- [ ] **Step 9: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add \
  drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDefinitionHelper.java \
  drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDefinitionHelperTest.java \
  drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java \
  drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java \
  drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspDocumentServiceTest.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: add import type definition resolution and LSP wiring"
```

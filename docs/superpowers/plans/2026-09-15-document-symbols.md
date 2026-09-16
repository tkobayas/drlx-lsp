# Document Symbols Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `textDocument/documentSymbol` LSP feature for DRLX files to support the outline view in editors.

**Architecture:** Create `DrlxDocumentSymbolHelper` in `drlx-completion` that parses DRLX text using `DrlxHoverHelper.createParser()`, extracts `unit` and `rule` declarations from `DrlxCompilationUnitContext` as flat `DocumentSymbol` items with accurate `range` and `selectionRange`, and wire it up to `DrlxLspDocumentService.documentSymbol()` and `DrlxLspServer.initialize()` capabilities in `drlx-lsp-server`.

**Tech Stack:** Java 17+, ANTLR4, Eclipse LSP4J (`DocumentSymbol`, `SymbolKind`, `Range`, `Position`, `Either`), JUnit 5, AssertJ.

## Global Constraints

- Scope: `drlxCompilationUnit` branch only; Java-style `compilationUnit` returns an empty list.
- Symbols returned: `unit` declaration (`SymbolKind.Namespace`), `rule` declaration (`SymbolKind.Method`).
- `import` declarations are excluded from document symbols.
- Flat hierarchy: `DocumentSymbol.getChildren()` is null / empty.
- LSP Invariant: `selectionRange` must be contained within `range` (`selectionRange ⊆ range`). If violated, fall back to `range` for both.
- ANTLR 1-based line numbers are converted to LSP 0-based lines (`line - 1`).
- End position for tokens in LSP: line = token line - 1, character = `token.getCharPositionInLine() + token.getText().length()`.
- Resilient: partial / malformed syntax must not throw; swallow exceptions per symbol and return whatever valid symbols were extracted.

---

### Task 1: Unit Tests for DrlxDocumentSymbolHelper

**Files:**
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDocumentSymbolHelperTest.java`

**Interfaces:**
- Consumes: `DrlxDocumentSymbolHelper.symbols(String text) -> List<DocumentSymbol>`
- Produces: Test suite validating outline behavior for all spec test cases

- [ ] **Step 1: Write the test class**

Create `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDocumentSymbolHelperTest.java`:

```java
package org.drools.drlx.completion;

import java.util.List;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxDocumentSymbolHelperTest {

    @Test
    void nullOrEmptyTextYieldsNoSymbols() {
        assertThat(DrlxDocumentSymbolHelper.symbols(null)).isEmpty();
        assertThat(DrlxDocumentSymbolHelper.symbols("")).isEmpty();
        assertThat(DrlxDocumentSymbolHelper.symbols("   \n\t  ")).isEmpty();
    }

    @Test
    void unitAndRulesOutlined() {
        String drlx = """
                unit MyUnit;

                import org.example.Person;

                rule R1 {
                    var p : /persons,
                    do { System.out.println(p); }
                }

                rule R2 {
                    var p : /persons,
                    do { }
                }
                """;

        List<DocumentSymbol> symbols = DrlxDocumentSymbolHelper.symbols(drlx);

        assertThat(symbols).hasSize(3);

        DocumentSymbol s0 = symbols.get(0);
        assertThat(s0.getName()).isEqualTo("MyUnit");
        assertThat(s0.getKind()).isEqualTo(SymbolKind.Namespace);
        assertThat(s0.getRange().getStart().getLine()).isEqualTo(0);

        DocumentSymbol s1 = symbols.get(1);
        assertThat(s1.getName()).isEqualTo("R1");
        assertThat(s1.getKind()).isEqualTo(SymbolKind.Method);
        assertThat(s1.getRange().getStart().getLine()).isEqualTo(4);

        DocumentSymbol s2 = symbols.get(2);
        assertThat(s2.getName()).isEqualTo("R2");
        assertThat(s2.getKind()).isEqualTo(SymbolKind.Method);
        assertThat(s2.getRange().getStart().getLine()).isEqualTo(9);
    }

    @Test
    void rulesOnlyWhenNoUnit() {
        String drlx = """
                rule R1 {
                    var p : /persons,
                    do { }
                }
                """;

        List<DocumentSymbol> symbols = DrlxDocumentSymbolHelper.symbols(drlx);

        assertThat(symbols).hasSize(1);
        assertThat(symbols.get(0).getName()).isEqualTo("R1");
        assertThat(symbols.get(0).getKind()).isEqualTo(SymbolKind.Method);
    }

    @Test
    void unitOnlyWhenNoRules() {
        String drlx = """
                unit org.example.MyUnit;

                import org.example.Person;
                """;

        List<DocumentSymbol> symbols = DrlxDocumentSymbolHelper.symbols(drlx);

        assertThat(symbols).hasSize(1);
        assertThat(symbols.get(0).getName()).isEqualTo("org.example.MyUnit");
        assertThat(symbols.get(0).getKind()).isEqualTo(SymbolKind.Namespace);
    }

    @Test
    void selectionRangeWithinRange() {
        String drlx = """
                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { }
                }
                """;

        List<DocumentSymbol> symbols = DrlxDocumentSymbolHelper.symbols(drlx);

        assertThat(symbols).isNotEmpty();
        for (DocumentSymbol s : symbols) {
            Range range = s.getRange();
            Range sel = s.getSelectionRange();

            assertThat(isPositionBeforeOrEqual(range.getStart(), sel.getStart())).isTrue();
            assertThat(isPositionBeforeOrEqual(sel.getEnd(), range.getEnd())).isTrue();
        }
    }

    @Test
    void javaStyleFileYieldsNoSymbols() {
        String drlx = """
                package org.example;

                class Foo {
                    rule R1 {
                        var p : /persons,
                        do { }
                    }
                }
                """;

        List<DocumentSymbol> symbols = DrlxDocumentSymbolHelper.symbols(drlx);

        assertThat(symbols).isEmpty();
    }

    @Test
    void partialFileDoesNotThrow() {
        String drlx = "unit ; rule {";

        List<DocumentSymbol> symbols = DrlxDocumentSymbolHelper.symbols(drlx);

        // Should return without throwing exceptions
        assertThat(symbols).isNotNull();
    }

    private boolean isPositionBeforeOrEqual(Position p1, Position p2) {
        if (p1.getLine() < p2.getLine()) return true;
        if (p1.getLine() == p2.getLine()) return p1.getCharacter() <= p2.getCharacter();
        return false;
    }
}
```

- [ ] **Step 2: Run test to verify compilation/execution fails**

Run: `mvn test -pl drlx-completion -Dtest=DrlxDocumentSymbolHelperTest`
Expected: Compilation failure because `DrlxDocumentSymbolHelper` does not exist yet.

---

### Task 2: Implement DrlxDocumentSymbolHelper

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDocumentSymbolHelper.java`

**Interfaces:**
- Consumes: ANTLR parser generated types (`DrlxParser`, `DrlxCompilationUnitContext`, `UnitDeclarationContext`, `RuleDeclarationContext`), `DrlxHoverHelper.createParser(text)`
- Produces: `DrlxDocumentSymbolHelper.symbols(String text) -> List<DocumentSymbol>`

- [ ] **Step 1: Write DrlxDocumentSymbolHelper implementation**

Create `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDocumentSymbolHelper.java`:

```java
package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxParser;
import org.drools.drlx.parser.DrlxParser.DrlxCompilationUnitContext;
import org.drools.drlx.parser.DrlxParser.DrlxStartContext;
import org.drools.drlx.parser.DrlxParser.RuleDeclarationContext;
import org.drools.drlx.parser.DrlxParser.UnitDeclarationContext;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

public final class DrlxDocumentSymbolHelper {

    private DrlxDocumentSymbolHelper() {
    }

    /**
     * Returns the outline symbols for {@code text}, or an empty list.
     */
    public static List<DocumentSymbol> symbols(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        try {
            DrlxParser parser = DrlxHoverHelper.createParser(text);
            DrlxStartContext drlxStart = parser.drlxStart();
            if (drlxStart == null) {
                return Collections.emptyList();
            }

            DrlxCompilationUnitContext cu = drlxStart.drlxCompilationUnit();
            if (cu == null) {
                return Collections.emptyList();
            }

            List<DocumentSymbol> result = new ArrayList<>();

            UnitDeclarationContext unitDecl = cu.unitDeclaration();
            if (unitDecl != null && unitDecl.qualifiedName() != null) {
                try {
                    String name = unitDecl.qualifiedName().getText();
                    if (name != null && !name.isEmpty()) {
                        DocumentSymbol symbol = symbol(name, SymbolKind.Namespace, unitDecl, unitDecl.qualifiedName());
                        if (symbol != null) {
                            result.add(symbol);
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            if (cu.ruleDeclaration() != null) {
                for (RuleDeclarationContext ruleDecl : cu.ruleDeclaration()) {
                    if (ruleDecl == null) continue;
                    try {
                        if (ruleDecl.identifier() != null) {
                            String name = ruleDecl.identifier().getText();
                            if (name != null && !name.isEmpty()) {
                                DocumentSymbol symbol = symbol(name, SymbolKind.Method, ruleDecl, ruleDecl.identifier());
                                if (symbol != null) {
                                    result.add(symbol);
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static DocumentSymbol symbol(String name, SymbolKind kind,
                                         ParserRuleContext rangeCtx,
                                         ParserRuleContext selectionCtx) {
        Range fullRange = rangeOf(rangeCtx);
        if (fullRange == null) {
            return null;
        }

        Range selectionRange = rangeOf(selectionCtx);
        if (selectionRange == null || !isContained(selectionRange, fullRange)) {
            selectionRange = fullRange;
        }

        DocumentSymbol sym = new DocumentSymbol();
        sym.setName(name);
        sym.setKind(kind);
        sym.setRange(fullRange);
        sym.setSelectionRange(selectionRange);
        return sym;
    }

    private static Range rangeOf(ParserRuleContext ctx) {
        if (ctx == null || ctx.getStart() == null) {
            return null;
        }
        Token startToken = ctx.getStart();
        Token stopToken = ctx.getStop() != null ? ctx.getStop() : startToken;

        int startLine = Math.max(0, startToken.getLine() - 1);
        int startCol = Math.max(0, startToken.getCharPositionInLine());

        int stopLine = Math.max(0, stopToken.getLine() - 1);
        String stopText = stopToken.getText();
        int stopLength = stopText != null ? stopText.length() : 0;
        int stopCol = Math.max(0, stopToken.getCharPositionInLine() + stopLength);

        return new Range(new Position(startLine, startCol), new Position(stopLine, stopCol));
    }

    private static boolean isContained(Range sub, Range sup) {
        return isBeforeOrEqual(sup.getStart(), sub.getStart()) && isBeforeOrEqual(sub.getEnd(), sup.getEnd());
    }

    private static boolean isBeforeOrEqual(Position p1, Position p2) {
        if (p1.getLine() < p2.getLine()) return true;
        if (p1.getLine() == p2.getLine()) return p1.getCharacter() <= p2.getCharacter();
        return false;
    }
}
```

- [ ] **Step 2: Run unit tests**

Run: `mvn test -pl drlx-completion -Dtest=DrlxDocumentSymbolHelperTest`
Expected: All tests pass.

- [ ] **Step 3: Build and install modified module**

Run: `mvn -pl drlx-completion -am install -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit module changes**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDocumentSymbolHelper.java drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDocumentSymbolHelperTest.java
git commit -m "feat: add DrlxDocumentSymbolHelper for documentSymbol LSP support"
```

---

### Task 3: Wire Server Capabilities and Document Service

**Files:**
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java:85-93`
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java:120-137`
- Modify: `drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspDocumentServiceTest.java`

**Interfaces:**
- Consumes: `DrlxDocumentSymbolHelper.symbols(String text)`
- Produces: `DrlxLspServer` capabilities with `documentSymbolProvider = true`, `DrlxLspDocumentService.documentSymbol(DocumentSymbolParams)` implementation returning `CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>>`.

- [ ] **Step 1: Write integration test in DrlxLspDocumentServiceTest**

Add test to `drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspDocumentServiceTest.java`:

```java
    @Test
    void documentSymbol_returnsSymbolsForDrlxFile() throws Exception {
        String drlx = """
                unit MyUnit;

                import org.drools.drlx.domain.Person;

                rule R1 {
                    var p : /persons,
                    do { System.out.println(p); }
                }

                rule R2 {
                    var p : /persons,
                    do { }
                }
                """;

        DrlxLspDocumentService service = getDrlxLspDocumentService(drlx);

        DocumentSymbolParams params = new DocumentSymbolParams(new TextDocumentIdentifier("myDocument"));
        List<Either<SymbolInformation, DocumentSymbol>> result = service.documentSymbol(params).get();

        assertThat(result).hasSize(3);
        assertThat(result.get(0).isRight()).isTrue();
        assertThat(result.get(0).getRight().getName()).isEqualTo("MyUnit");
        assertThat(result.get(0).getRight().getKind()).isEqualTo(SymbolKind.Namespace);

        assertThat(result.get(1).isRight()).isTrue();
        assertThat(result.get(1).getRight().getName()).isEqualTo("R1");
        assertThat(result.get(1).getRight().getKind()).isEqualTo(SymbolKind.Method);

        assertThat(result.get(2).isRight()).isTrue();
        assertThat(result.get(2).getRight().getName()).isEqualTo("R2");
        assertThat(result.get(2).getRight().getKind()).isEqualTo(SymbolKind.Method);
    }
```

- [ ] **Step 2: Update DrlxLspServer to advertise documentSymbolProvider**

In `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java`:
Add `initializeResult.getCapabilities().setDocumentSymbolProvider(true);` in `initialize()` method.

```java
<<<<<<< SEARCH
        initializeResult.getCapabilities().setDefinitionProvider(true);
        initializeResult.getCapabilities().setReferencesProvider(true);
        return CompletableFuture.supplyAsync(() -> initializeResult);
=======
        initializeResult.getCapabilities().setDefinitionProvider(true);
        initializeResult.getCapabilities().setReferencesProvider(true);
        initializeResult.getCapabilities().setDocumentSymbolProvider(true);
        return CompletableFuture.supplyAsync(() -> initializeResult);
>>>>>>> REPLACE
```

- [ ] **Step 3: Update DrlxLspDocumentService to implement documentSymbol**

In `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java`:
Add imports for:
```java
import java.util.stream.Collectors;
import org.drools.drlx.completion.DrlxDocumentSymbolHelper;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.SymbolInformation;
```
And override `documentSymbol`:
```java
    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
            DocumentSymbolParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            String text = sourcesMap.get(uri);
            if (text == null) return Collections.emptyList();
            return DrlxDocumentSymbolHelper.symbols(text).stream()
                    .map(Either::<SymbolInformation, DocumentSymbol>forRight)
                    .collect(Collectors.toList());
        });
    }
```

- [ ] **Step 4: Run server test suite**

Run: `mvn test -pl drlx-lsp-server`
Expected: All tests pass including `documentSymbol_returnsSymbolsForDrlxFile`.

- [ ] **Step 5: Run full project build and test**

Run: `mvn clean test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit server changes**

```bash
git add drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspDocumentServiceTest.java
git commit -m "feat: enable documentSymbol capability and wire to DrlxDocumentSymbolHelper"
```

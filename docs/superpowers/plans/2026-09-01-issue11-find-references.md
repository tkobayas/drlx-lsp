# Find References Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `textDocument/references` support to drlx-lsp so users can find all usages of bindings and import types within a file.

**Architecture:** New `DrlxReferencesHelper` class scans ANTLR tokens for identifier matches. Bindings are scoped to the enclosing rule; import types are scoped to the whole file. Wired into `DrlxLspDocumentService.references()` with the `referencesProvider` capability.

**Tech Stack:** Java 17, ANTLR4, LSP4J, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-09-01-issue11-find-references-design.md`

## Global Constraints

- Source repo: `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`
- Build: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q`
- Test (drlx-completion): `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test`
- Test (single class): `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxReferencesHelperTest"`
- Test (drlx-lsp-server): `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-lsp-server test -Dtest="DrlxLspDocumentServiceTest"`
- Must `install` before running server tests if drlx-completion was modified
- Commits go to the source repo, on `main` branch directly (no feature branches)
- Domain classes for tests: `org.drools.drlx.domain.{MyUnit, Person, Address, Country}` — `MyUnit` has `DataStore<Person> persons`, `DataStore<Address> addresses`; `Person` has `name`(String), `age`(int), `address`(Address); `Address` has `city`(String), `country`(Country)

---

### Task 1: Core `DrlxReferencesHelper` with binding reference tests

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxReferencesHelper.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxReferencesHelperTest.java`

**Interfaces:**
- Consumes: `DrlxHoverHelper.findTokenAt(CommonTokenStream, Position)` → `Token`, `DrlxHoverHelper.createParser(String)` → `DrlxParser`, `WorkspaceSemanticModel.createContext(DrlxParser, ParseTree, int)` → `CompletionContext`, `CompletionContext.buildVisibleSymbols()` → `VisibleSymbols`, `VisibleSymbols.lookupEntry(String)` → `Optional<SymbolEntry>`, `CompletionContext.imports()` → `Set<String>`
- Produces: `DrlxReferencesHelper.references(String uri, String text, Position position, WorkspaceSemanticModel model, boolean includeDeclaration)` → `List<Location>` — used by Task 2 (`DrlxLspDocumentService`)

- [ ] **Step 1: Write the first failing test — `oopathBinding_findsAllUsesInRule`**

Create `DrlxReferencesHelperTest.java`:

```java
package org.drools.drlx.completion;

import java.util.List;

import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxReferencesHelperTest {

    private final WorkspaceSemanticModel model =
            new WorkspaceSemanticModel(new CurrentClassloaderProvider());

    private static final String URI = "file:///test.drlx";

    @Test
    void oopathBinding_findsAllUsesInRule() {
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
        List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(7, 9), model, true);

        assertThat(refs).hasSize(2);
        // Declaration: "p" in "var p : /persons" — line 6
        assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 6);
        // Usage: "p" in "do { p }" — line 7
        assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 7);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxReferencesHelperTest#oopathBinding_findsAllUsesInRule"`

Expected: FAIL — `DrlxReferencesHelper` does not exist.

- [ ] **Step 3: Write the `DrlxReferencesHelper` implementation**

Create `DrlxReferencesHelper.java`:

```java
package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.completion.semantic.CompletionContext;
import org.drools.drlx.completion.semantic.SymbolEntry;
import org.drools.drlx.completion.semantic.TokenRange;
import org.drools.drlx.completion.semantic.VisibleSymbols;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.drools.drlx.parser.DrlxParser.DrlxCompilationUnitContext;
import org.drools.drlx.parser.DrlxParser.RuleDeclarationContext;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;

public class DrlxReferencesHelper {

    private DrlxReferencesHelper() {
    }

    public static List<Location> references(String uri, String text, Position position,
                                             WorkspaceSemanticModel model, boolean includeDeclaration) {
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

        if (entry.isPresent()) {
            return findBindingReferences(uri, word, tokens, parseTree, tokenIndex,
                    entry.get(), includeDeclaration);
        }

        return findImportTypeReferences(uri, word, tokens, ctx, includeDeclaration);
    }

    private static List<Location> findBindingReferences(String uri, String word,
                                                         CommonTokenStream tokens, ParseTree parseTree,
                                                         int tokenIndex, SymbolEntry entry,
                                                         boolean includeDeclaration) {
        RuleDeclarationContext enclosingRule = findEnclosingRule(parseTree, tokenIndex);
        if (enclosingRule == null) {
            return Collections.emptyList();
        }

        int ruleStart = enclosingRule.getStart().getTokenIndex();
        int ruleStop = enclosingRule.getStop() != null
                ? enclosingRule.getStop().getTokenIndex() : Integer.MAX_VALUE;

        TokenRange declRange = entry.range();

        List<Location> refs = new ArrayList<>();
        tokens.fill();
        for (Token t : tokens.getTokens()) {
            if (t.getType() != DrlxLexer.IDENTIFIER) continue;
            int idx = t.getTokenIndex();
            if (idx < ruleStart || idx > ruleStop) continue;
            if (!t.getText().equals(word)) continue;

            if (!includeDeclaration && declRange != null && isAtRange(t, declRange)) {
                continue;
            }

            refs.add(tokenToLocation(uri, t));
        }
        return refs;
    }

    private static List<Location> findImportTypeReferences(String uri, String word,
                                                            CommonTokenStream tokens,
                                                            CompletionContext ctx,
                                                            boolean includeDeclaration) {
        boolean isImportType = false;
        int importLine = -1;
        for (String fqcn : ctx.imports()) {
            String simpleName = fqcn.contains(".")
                    ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
            if (simpleName.equals(word)) {
                isImportType = true;
                break;
            }
        }
        if (!isImportType) {
            return Collections.emptyList();
        }

        List<Location> refs = new ArrayList<>();
        tokens.fill();
        for (Token t : tokens.getTokens()) {
            if (t.getType() != DrlxLexer.IDENTIFIER) continue;
            if (!t.getText().equals(word)) continue;
            refs.add(tokenToLocation(uri, t));
        }

        if (!includeDeclaration) {
            DrlxCompilationUnitContext cu = findCompilationUnit(ctx.parseTree());
            if (cu != null) {
                for (var imp : cu.importDeclaration()) {
                    if (imp.qualifiedName() == null) continue;
                    String fqcn = imp.qualifiedName().getText();
                    String simpleName = fqcn.contains(".")
                            ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
                    if (simpleName.equals(word)) {
                        importLine = imp.getStart().getLine() - 1;
                        break;
                    }
                }
            }
            if (importLine >= 0) {
                int impLine = importLine;
                refs.removeIf(loc -> loc.getRange().getStart().getLine() == impLine);
            }
        }

        return refs;
    }

    private static RuleDeclarationContext findEnclosingRule(ParseTree parseTree, int tokenIndex) {
        DrlxCompilationUnitContext cu = findCompilationUnit(parseTree);
        if (cu == null) return null;

        for (RuleDeclarationContext rule : cu.ruleDeclaration()) {
            if (rule.getStart() != null) {
                int ruleStart = rule.getStart().getTokenIndex();
                int ruleStop = rule.getStop() != null
                        ? rule.getStop().getTokenIndex() : Integer.MAX_VALUE;
                if (ruleStart <= tokenIndex && ruleStop >= tokenIndex) {
                    return rule;
                }
            }
        }
        return null;
    }

    private static DrlxCompilationUnitContext findCompilationUnit(ParseTree node) {
        if (node instanceof DrlxCompilationUnitContext cu) return cu;
        for (int i = 0; i < node.getChildCount(); i++) {
            DrlxCompilationUnitContext found = findCompilationUnit(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isAtRange(Token token, TokenRange range) {
        int line = token.getLine() - 1;
        int col = token.getCharPositionInLine();
        return line == range.startLine() && col == range.startCol();
    }

    private static Location tokenToLocation(String uri, Token token) {
        TokenRange range = TokenRange.fromAntlrToken(token, token.getText().length());
        return new Location(uri, range.toLspRange());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxReferencesHelperTest#oopathBinding_findsAllUsesInRule"`

Expected: PASS

- [ ] **Step 5: Add remaining binding tests**

Add these tests to `DrlxReferencesHelperTest.java`:

```java
@Test
void oopathBinding_excludeDeclaration() {
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
    List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(7, 9), model, false);

    assertThat(refs).hasSize(1);
    // Only usage, not declaration
    assertThat(refs.get(0).getRange().getStart().getLine()).isEqualTo(7);
}

@Test
void constraintBinding_findsAllUsesInRule() {
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
    List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(8, 9), model, true);

    assertThat(refs).hasSize(2);
    assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 7);
    assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 8);
}

@Test
void ruleParameter_findsAllUsesInRule() {
    // Line 0: import org.drools.drlx.domain.Person;
    // Line 1: import org.drools.drlx.domain.MyUnit;
    // Line 2:
    // Line 3: unit MyUnit;
    // Line 4:
    // Line 5: rule R1(Person p) {
    // Line 6:     do { p }
    // Line 7: }
    String text = """
            import org.drools.drlx.domain.Person;
            import org.drools.drlx.domain.MyUnit;

            unit MyUnit;

            rule R1(Person p) {
                do { p }
            }
            """;
    // Cursor on "p" in "do { p }" — line 6, char 9
    List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(6, 9), model, true);

    assertThat(refs).hasSize(2);
    assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 5);
    assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 6);
}

@Test
void rhsLocalVariable_findsAllUsesInRule() {
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
    List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(10, 8), model, true);

    assertThat(refs).hasSize(2);
    assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 9);
    assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 10);
}

@Test
void bindingScopedToEnclosingRule() {
    // Line 0: import org.drools.drlx.domain.Person;
    // Line 1: import org.drools.drlx.domain.MyUnit;
    // Line 2:
    // Line 3: unit MyUnit;
    // Line 4:
    // Line 5: rule R1 {
    // Line 6:     var p : /persons,
    // Line 7:     do { p }
    // Line 8: }
    // Line 9:
    // Line 10: rule R2 {
    // Line 11:     var p : /persons,
    // Line 12:     do { p }
    // Line 13: }
    String text = """
            import org.drools.drlx.domain.Person;
            import org.drools.drlx.domain.MyUnit;

            unit MyUnit;

            rule R1 {
                var p : /persons,
                do { p }
            }

            rule R2 {
                var p : /persons,
                do { p }
            }
            """;
    // Cursor on "p" in R1's "do { p }" — line 7, char 9
    List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(7, 9), model, true);

    assertThat(refs).hasSize(2);
    // All refs should be within R1 (lines 5-8), none from R2 (lines 10-13)
    assertThat(refs).allMatch(loc -> loc.getRange().getStart().getLine() <= 8);
}
```

- [ ] **Step 6: Run all binding tests**

Run: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxReferencesHelperTest"`

Expected: All PASS

- [ ] **Step 7: Add import type and edge case tests**

Add these tests to `DrlxReferencesHelperTest.java`:

```java
@Test
void importType_findsAllUsesInFile() {
    // Line 0: import org.drools.drlx.domain.Person;
    // Line 1: import org.drools.drlx.domain.MyUnit;
    // Line 2:
    // Line 3: unit MyUnit;
    // Line 4:
    // Line 5: rule R1(Person p) {
    // Line 6:     do { p }
    // Line 7: }
    String text = """
            import org.drools.drlx.domain.Person;
            import org.drools.drlx.domain.MyUnit;

            unit MyUnit;

            rule R1(Person p) {
                do { p }
            }
            """;
    // Cursor on "Person" in "rule R1(Person p)" — line 5, char 8
    List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(5, 8), model, true);

    assertThat(refs).hasSize(2);
    // Import line — line 0
    assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 0);
    // Rule parameter — line 5
    assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 5);
}

@Test
void importType_excludeDeclaration() {
    String text = """
            import org.drools.drlx.domain.Person;
            import org.drools.drlx.domain.MyUnit;

            unit MyUnit;

            rule R1(Person p) {
                do { p }
            }
            """;
    // Cursor on "Person" in "rule R1(Person p)" — line 5, char 8
    List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(5, 8), model, false);

    assertThat(refs).hasSize(1);
    // Only the usage in rule parameter, not the import line
    assertThat(refs.get(0).getRange().getStart().getLine()).isEqualTo(5);
}

@Test
void keywordReturnsEmpty() {
    String text = """
            import org.drools.drlx.domain.Person;
            import org.drools.drlx.domain.MyUnit;

            unit MyUnit;

            rule R1(Person p) {
                do { p }
            }
            """;
    // Cursor on "rule" keyword — line 5, char 0
    List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(5, 0), model, true);

    assertThat(refs).isEmpty();
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
    // "unknown" — line 7, char 9
    List<Location> refs = DrlxReferencesHelper.references(URI, text, new Position(7, 9), model, true);

    assertThat(refs).isEmpty();
}

@Test
void nullTextReturnsEmpty() {
    List<Location> refs = DrlxReferencesHelper.references(URI, null, new Position(0, 0), model, true);
    assertThat(refs).isEmpty();
}
```

- [ ] **Step 8: Run all tests**

Run: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxReferencesHelperTest"`

Expected: All PASS

- [ ] **Step 9: Run the full drlx-completion test suite to check for regressions**

Run: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test`

Expected: All existing tests still pass

- [ ] **Step 10: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add \
  drlx-completion/src/main/java/org/drools/drlx/completion/DrlxReferencesHelper.java \
  drlx-completion/src/test/java/org/drools/drlx/completion/DrlxReferencesHelperTest.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: add DrlxReferencesHelper with binding and import type reference scanning"
```

---

### Task 2: LSP server wiring and integration test

**Files:**
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java:90`
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java`
- Modify: `drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspDocumentServiceTest.java`

**Interfaces:**
- Consumes: `DrlxReferencesHelper.references(String uri, String text, Position position, WorkspaceSemanticModel model, boolean includeDeclaration)` → `List<Location>` (from Task 1)
- Produces: LSP `textDocument/references` endpoint

- [ ] **Step 1: Write the failing integration test**

Add to `DrlxLspDocumentServiceTest.java`:

```java
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
```

```java
@Test
void references_findsBindingUses() throws Exception {
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

    ReferenceParams params = new ReferenceParams();
    params.setTextDocument(new TextDocumentIdentifier("myDocument"));
    params.setPosition(new Position(7, 9)); // "p" in "do { p }"
    params.setContext(new ReferenceContext(true));

    List<? extends Location> refs = service.references(params).get();

    assertThat(refs).hasSize(2);
    assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 6);
    assertThat(refs).anyMatch(loc -> loc.getRange().getStart().getLine() == 7);
}
```

- [ ] **Step 2: Run test to verify it fails**

First install drlx-completion:

Run: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q`

Then:

Run: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-lsp-server test -Dtest="DrlxLspDocumentServiceTest#references_findsBindingUses"`

Expected: FAIL — `references()` method not overridden, returns default empty.

- [ ] **Step 3: Add `references()` to `DrlxLspDocumentService`**

Add this import to `DrlxLspDocumentService.java`:

```java
import org.drools.drlx.completion.DrlxReferencesHelper;
import org.eclipse.lsp4j.ReferenceParams;
```

Add this method to `DrlxLspDocumentService`:

```java
@Override
public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
    return CompletableFuture.supplyAsync(() -> {
        String uri = params.getTextDocument().getUri();
        String text = sourcesMap.get(uri);
        if (text == null) return Collections.emptyList();
        boolean includeDeclaration =
                params.getContext() != null && params.getContext().isIncludeDeclaration();
        return DrlxReferencesHelper.references(uri, text, params.getPosition(), model, includeDeclaration);
    });
}
```

- [ ] **Step 4: Advertise `referencesProvider` capability**

In `DrlxLspServer.java`, after line 90 (`setDefinitionProvider(true)`), add:

```java
initializeResult.getCapabilities().setReferencesProvider(true);
```

- [ ] **Step 5: Run integration test to verify it passes**

Run: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-lsp-server test -Dtest="DrlxLspDocumentServiceTest#references_findsBindingUses"`

Expected: PASS

- [ ] **Step 6: Run the full server test suite**

Run: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-lsp-server test`

Expected: All existing tests still pass

- [ ] **Step 7: Run the full project test suite**

Run: `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp test`

Expected: All tests pass

- [ ] **Step 8: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add \
  drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java \
  drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java \
  drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspDocumentServiceTest.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: wire find-references into LSP server, advertise referencesProvider capability"
```

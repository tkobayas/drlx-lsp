# Rename (Bound Variables) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `textDocument/prepareRename` and `textDocument/rename` for bound variables in DRLX files, allowing users to rename pattern bindings, constraint bindings, rule parameters, and RHS local variables with all references updated within the enclosing rule.

**Architecture:** A new `DrlxRenameHelper` static utility class delegates to the existing `DrlxReferencesHelper.references()` for location collection, then converts each `Location` to a `TextEdit` grouped into a `WorkspaceEdit`. The LSP server wires up `prepareRename` and `rename` endpoints following the same pattern as `references()`.

**Tech Stack:** Java 17, ANTLR4, lsp4j, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-10-01-issue11-rename-spec.md`

## Global Constraints

- Bound variables only — import types (classpath types) are never renameable; `prepare()` returns `null` so the client refuses.
- Cross-file rename is out of scope; all edits are within the single file.
- `$` is part of the identifier — no special normalization; `newName` is used as-is after validation.
- Invalid identifiers (per `Character.isJavaIdentifierStart/Part`, which accepts `$` as a legal character) are rejected.

## Review Focus

1. **Caret on a `$`-prefixed variable where the `$` is at char 0 of the token** — `findTokenAt` must match the whole `$p` token, not skip it. Covered by Task 1 test `prepare_constraintBinding_returnsRangeAndPlaceholder`.
2. **Binding name that collides with a Java keyword (e.g. rename to `class`)** — `isValidIdentifier` accepts this because `Character.isJavaIdentifierStart/Part` does not check keywords. This is acceptable: DRL/DRLX allows identifier names that happen to be Java keywords in binding positions, and the LSP server should not over-reject.
3. **Token at position is on a hidden channel (comment/whitespace)** — `findTokenAt` already filters by `DEFAULT_CHANNEL`, so `prepare()` returns `null`. Covered by existing `DrlxReferencesHelper` behavior; no extra test needed.

---

### Task 1: DrlxRenameHelper core logic and tests

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxRenameHelper.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxRenameHelperTest.java`
- Read (not modify): `DrlxHoverHelper.java` (for `createParser`, `findTokenAt`), `DrlxReferencesHelper.java`, `TokenRange.java`

**Interfaces:**
- Consumes: `DrlxHoverHelper.createParser(String)` → `DrlxParser`, `DrlxHoverHelper.findTokenAt(CommonTokenStream, Position)` → `Token`, `DrlxReferencesHelper.references(String, String, Position, WorkspaceSemanticModel, boolean)` → `List<Location>`, `WorkspaceSemanticModel.createContext(DrlxParser, ParseTree, int)` → `CompletionContext`, `CompletionContext.buildVisibleSymbols()` → `VisibleSymbols`, `VisibleSymbols.lookupEntry(String)` → `Optional<SymbolEntry>`, `TokenRange.fromAntlrToken(Token, int)` → `TokenRange`, `TokenRange.toLspRange()` → `Range`
- Produces: `DrlxRenameHelper.prepare(String uri, String text, Position position, WorkspaceSemanticModel model)` → `PreparedRename | null`, `DrlxRenameHelper.rename(String uri, String text, Position position, String newName, WorkspaceSemanticModel model)` → `WorkspaceEdit | null`. `PreparedRename` is a static inner record with fields `Range range` and `String placeholder`.

- [ ] **Step 1: Write the test class skeleton with the first failing test — prepare for OOPath binding**

Create `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxRenameHelperTest.java`:

```java
package org.drools.drlx.completion;

import java.util.List;

import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxRenameHelperTest {

    private final WorkspaceSemanticModel model =
            new WorkspaceSemanticModel(new CurrentClassloaderProvider());

    private static final String URI = "file:///test.drlx";

    // --- prepare ---

    @Test
    void prepare_oopathBinding_returnsRangeAndPlaceholder() {
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
        // Cursor on "p" in "var p : /persons" — line 6, char 8
        DrlxRenameHelper.PreparedRename result =
                DrlxRenameHelper.prepare(URI, text, new Position(6, 8), model);

        assertThat(result).isNotNull();
        assertThat(result.placeholder()).isEqualTo("p");
        assertThat(result.range().getStart().getLine()).isEqualTo(6);
        assertThat(result.range().getStart().getCharacter()).isEqualTo(8);
        assertThat(result.range().getEnd().getCharacter()).isEqualTo(9);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl drlx-completion test -Dtest="DrlxRenameHelperTest#prepare_oopathBinding_returnsRangeAndPlaceholder"`
Expected: Compilation error — `DrlxRenameHelper` does not exist yet.

- [ ] **Step 3: Create `DrlxRenameHelper` with `prepare()` and `PreparedRename`**

Create `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxRenameHelper.java`:

```java
package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

public final class DrlxRenameHelper {

    private DrlxRenameHelper() {
    }

    public record PreparedRename(Range range, String placeholder) {
    }

    public static PreparedRename prepare(String uri, String text, Position position,
                                          WorkspaceSemanticModel model) {
        if (text == null || text.isEmpty() || position == null) {
            return null;
        }

        DrlxParser parser = DrlxHoverHelper.createParser(text);
        ParseTree parseTree = parser.drlxStart();
        CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();

        Token token = DrlxHoverHelper.findTokenAt(tokens, position);
        if (token == null || token.getType() != DrlxLexer.IDENTIFIER) {
            return null;
        }
        String word = token.getText();
        int tokenIndex = token.getTokenIndex();

        CompletionContext ctx = model.createContext(parser, parseTree, tokenIndex);
        VisibleSymbols symbols = ctx.buildVisibleSymbols();
        Optional<SymbolEntry> entry = symbols.lookupEntry(word);

        if (entry.isEmpty()) {
            return null;
        }

        TokenRange range = TokenRange.fromAntlrToken(token, word.length());
        return new PreparedRename(range.toLspRange(), word);
    }

    public static WorkspaceEdit rename(String uri, String text, Position position,
                                        String newName, WorkspaceSemanticModel model) {
        if (text == null || text.isEmpty() || position == null || newName == null) {
            return null;
        }

        if (!isValidIdentifier(newName)) {
            return null;
        }

        DrlxParser parser = DrlxHoverHelper.createParser(text);
        ParseTree parseTree = parser.drlxStart();
        CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();

        Token token = DrlxHoverHelper.findTokenAt(tokens, position);
        if (token == null || token.getType() != DrlxLexer.IDENTIFIER) {
            return null;
        }
        String word = token.getText();
        int tokenIndex = token.getTokenIndex();

        CompletionContext ctx = model.createContext(parser, parseTree, tokenIndex);
        VisibleSymbols symbols = ctx.buildVisibleSymbols();
        Optional<SymbolEntry> entry = symbols.lookupEntry(word);

        if (entry.isEmpty()) {
            return null;
        }

        List<Location> refs = DrlxReferencesHelper.references(uri, text, position, model, true);
        if (refs.isEmpty()) {
            return null;
        }

        Map<String, List<TextEdit>> changes = new LinkedHashMap<>();
        for (Location loc : refs) {
            changes.computeIfAbsent(loc.getUri(), k -> new ArrayList<>())
                   .add(new TextEdit(loc.getRange(), newName));
        }
        return new WorkspaceEdit(changes);
    }

    private static boolean isValidIdentifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
```

- [ ] **Step 4: Run the prepare test to verify it passes**

Run: `mvn -pl drlx-completion test -Dtest="DrlxRenameHelperTest#prepare_oopathBinding_returnsRangeAndPlaceholder"`
Expected: PASS

- [ ] **Step 5: Add prepare test for constraint binding**

Add to `DrlxRenameHelperTest.java`:

```java
@Test
void prepare_constraintBinding_returnsRangeAndPlaceholder() {
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
    DrlxRenameHelper.PreparedRename result =
            DrlxRenameHelper.prepare(URI, text, new Position(8, 9), model);

    assertThat(result).isNotNull();
    assertThat(result.placeholder()).isEqualTo("$addr");
    assertThat(result.range().getStart().getLine()).isEqualTo(8);
}
```

- [ ] **Step 6: Run prepare tests to verify they pass**

Run: `mvn -pl drlx-completion test -Dtest="DrlxRenameHelperTest#prepare_oopathBinding_returnsRangeAndPlaceholder+prepare_constraintBinding_returnsRangeAndPlaceholder"`
Expected: PASS

- [ ] **Step 7: Add prepare rejection tests — import type, keyword, null text**

Add to `DrlxRenameHelperTest.java`:

```java
@Test
void prepare_importType_returnsNull() {
    String text = """
            import org.drools.drlx.domain.Person;
            import org.drools.drlx.domain.MyUnit;

            unit MyUnit;

            rule R1(Person p) {
                do { p }
            }
            """;
    // Cursor on "Person" in "rule R1(Person p)" — line 5, char 8
    DrlxRenameHelper.PreparedRename result =
            DrlxRenameHelper.prepare(URI, text, new Position(5, 8), model);

    assertThat(result).isNull();
}

@Test
void prepare_keyword_returnsNull() {
    String text = """
            import org.drools.drlx.domain.Person;
            import org.drools.drlx.domain.MyUnit;

            unit MyUnit;

            rule R1 {
                var p : /persons,
                do { p }
            }
            """;
    // Cursor on "rule" keyword — line 5, char 0
    DrlxRenameHelper.PreparedRename result =
            DrlxRenameHelper.prepare(URI, text, new Position(5, 0), model);

    assertThat(result).isNull();
}

@Test
void prepare_nullText_returnsNull() {
    assertThat(DrlxRenameHelper.prepare(URI, null, new Position(0, 0), model)).isNull();
}
```

- [ ] **Step 8: Run all prepare tests**

Run: `mvn -pl drlx-completion test -Dtest="DrlxRenameHelperTest#prepare_oopathBinding_returnsRangeAndPlaceholder+prepare_constraintBinding_returnsRangeAndPlaceholder+prepare_importType_returnsNull+prepare_keyword_returnsNull+prepare_nullText_returnsNull"`
Expected: PASS

- [ ] **Step 9: Add rename happy path test — OOPath binding**

Add to `DrlxRenameHelperTest.java`:

```java
// --- rename ---

@Test
void rename_oopathBinding_updatesAllReferences() {
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
    WorkspaceEdit edit =
            DrlxRenameHelper.rename(URI, text, new Position(7, 9), "person", model);

    assertThat(edit).isNotNull();
    List<TextEdit> edits = edit.getChanges().get(URI);
    assertThat(edits).hasSize(2);
    assertThat(edits).allSatisfy(e -> assertThat(e.getNewText()).isEqualTo("person"));
}
```

- [ ] **Step 10: Run the rename test to verify it passes**

Run: `mvn -pl drlx-completion test -Dtest="DrlxRenameHelperTest#rename_oopathBinding_updatesAllReferences"`
Expected: PASS

- [ ] **Step 11: Add rename test — constraint binding uses newName as-is**

Add to `DrlxRenameHelperTest.java`:

```java
@Test
void rename_constraintBinding_withDollar() {
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
    // Cursor on "$addr" — line 8, char 9; newName with $
    WorkspaceEdit edit =
            DrlxRenameHelper.rename(URI, text, new Position(8, 9), "$address", model);

    assertThat(edit).isNotNull();
    List<TextEdit> edits = edit.getChanges().get(URI);
    assertThat(edits).hasSize(2);
    assertThat(edits).allSatisfy(e -> assertThat(e.getNewText()).isEqualTo("$address"));
}

@Test
void rename_constraintBinding_withoutDollar() {
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
    // Cursor on "$addr" — line 8, char 9; newName without $ — used as-is
    WorkspaceEdit edit =
            DrlxRenameHelper.rename(URI, text, new Position(8, 9), "address", model);

    assertThat(edit).isNotNull();
    List<TextEdit> edits = edit.getChanges().get(URI);
    assertThat(edits).hasSize(2);
    assertThat(edits).allSatisfy(e -> assertThat(e.getNewText()).isEqualTo("address"));
}
```

- [ ] **Step 12: Run the constraint binding rename tests**

Run: `mvn -pl drlx-completion test -Dtest="DrlxRenameHelperTest#rename_constraintBinding_withDollar+rename_constraintBinding_withoutDollar"`
Expected: PASS

- [ ] **Step 13: Add rename test — RHS local variable**

Add to `DrlxRenameHelperTest.java`:

```java
@Test
void rename_rhsLocalVariable_updatesAllReferences() {
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
    WorkspaceEdit edit =
            DrlxRenameHelper.rename(URI, text, new Position(10, 8), "result", model);

    assertThat(edit).isNotNull();
    List<TextEdit> edits = edit.getChanges().get(URI);
    assertThat(edits).hasSize(2);
    assertThat(edits).allSatisfy(e -> assertThat(e.getNewText()).isEqualTo("result"));
}
```

- [ ] **Step 14: Run the RHS local variable test**

Run: `mvn -pl drlx-completion test -Dtest="DrlxRenameHelperTest#rename_rhsLocalVariable_updatesAllReferences"`
Expected: PASS

- [ ] **Step 15: Add rename test — scoped to enclosing rule only**

Add to `DrlxRenameHelperTest.java`:

```java
@Test
void rename_scopedToEnclosingRule() {
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
    WorkspaceEdit edit =
            DrlxRenameHelper.rename(URI, text, new Position(7, 9), "person", model);

    assertThat(edit).isNotNull();
    List<TextEdit> edits = edit.getChanges().get(URI);
    assertThat(edits).hasSize(2);
    // All edits within R1 (lines 5-8), none from R2
    assertThat(edits).allSatisfy(e ->
            assertThat(e.getRange().getStart().getLine()).isLessThanOrEqualTo(8));
}
```

- [ ] **Step 16: Run the scoping test**

Run: `mvn -pl drlx-completion test -Dtest="DrlxRenameHelperTest#rename_scopedToEnclosingRule"`
Expected: PASS

- [ ] **Step 17: Add rename rejection tests — import type, invalid name, empty name**

Add to `DrlxRenameHelperTest.java`:

```java
@Test
void rename_importType_returnsNull() {
    String text = """
            import org.drools.drlx.domain.Person;
            import org.drools.drlx.domain.MyUnit;

            unit MyUnit;

            rule R1(Person p) {
                do { p }
            }
            """;
    // Cursor on "Person" — line 5, char 8
    WorkspaceEdit edit =
            DrlxRenameHelper.rename(URI, text, new Position(5, 8), "Customer", model);

    assertThat(edit).isNull();
}

@Test
void rename_invalidNewName_returnsNull() {
    String text = """
            import org.drools.drlx.domain.Person;
            import org.drools.drlx.domain.MyUnit;

            unit MyUnit;

            rule R1 {
                var p : /persons,
                do { p }
            }
            """;
    assertThat(DrlxRenameHelper.rename(URI, text, new Position(7, 9), "123abc", model)).isNull();
    assertThat(DrlxRenameHelper.rename(URI, text, new Position(7, 9), "has space", model)).isNull();
}

@Test
void rename_emptyNewName_returnsNull() {
    String text = """
            import org.drools.drlx.domain.Person;
            import org.drools.drlx.domain.MyUnit;

            unit MyUnit;

            rule R1 {
                var p : /persons,
                do { p }
            }
            """;
    assertThat(DrlxRenameHelper.rename(URI, text, new Position(7, 9), "", model)).isNull();
}

@Test
void rename_nullText_returnsNull() {
    assertThat(DrlxRenameHelper.rename(URI, null, new Position(0, 0), "x", model)).isNull();
}
```

- [ ] **Step 18: Run all rename rejection tests**

Run: `mvn -pl drlx-completion test -Dtest="DrlxRenameHelperTest#rename_importType_returnsNull+rename_invalidNewName_returnsNull+rename_emptyNewName_returnsNull+rename_nullText_returnsNull"`
Expected: PASS

- [ ] **Step 19: Run all tests in `DrlxRenameHelperTest`**

Run: `mvn -pl drlx-completion test -Dtest="DrlxRenameHelperTest"`
Expected: All tests PASS

- [ ] **Step 20: Run the full drlx-completion test suite to check for regressions**

Run: `mvn -pl drlx-completion test`
Expected: All tests PASS

- [ ] **Step 21: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxRenameHelper.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/DrlxRenameHelperTest.java
git commit -m "feat: add DrlxRenameHelper for bound variable rename (#11)"
```

---

### Task 2: LSP server integration

**Files:**
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java`
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java`

**Interfaces:**
- Consumes: `DrlxRenameHelper.prepare(String, String, Position, WorkspaceSemanticModel)` → `PreparedRename | null`, `DrlxRenameHelper.rename(String, String, Position, String, WorkspaceSemanticModel)` → `WorkspaceEdit | null`
- Produces: LSP endpoints `textDocument/prepareRename` and `textDocument/rename`

- [ ] **Step 1: Add `prepareRename` and `rename` methods to `DrlxLspDocumentService`**

Add import at the top of `DrlxLspDocumentService.java`:

```java
import org.drools.drlx.completion.DrlxRenameHelper;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
```

Add methods after the existing `codeAction` method:

```java
@Override
public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(PrepareRenameParams params) {
    return CompletableFuture.supplyAsync(() -> {
        String uri = params.getTextDocument().getUri();
        String text = sourcesMap.get(uri);
        if (text == null) return null;
        DrlxRenameHelper.PreparedRename prepared =
                DrlxRenameHelper.prepare(uri, text, params.getPosition(), model);
        if (prepared == null) return null;
        return Either3.forSecond(new PrepareRenameResult(prepared.range(), prepared.placeholder()));
    });
}

@Override
public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
    return CompletableFuture.supplyAsync(() -> {
        String uri = params.getTextDocument().getUri();
        String text = sourcesMap.get(uri);
        if (text == null) return null;
        return DrlxRenameHelper.rename(uri, text, params.getPosition(),
                params.getNewName(), model);
    });
}
```

- [ ] **Step 2: Register rename capability in `DrlxLspServer.initialize()`**

Add import at the top of `DrlxLspServer.java`:

```java
import org.eclipse.lsp4j.RenameOptions;
```

Add after the `setCodeActionProvider(true)` line (line 96):

```java
initializeResult.getCapabilities().setRenameProvider(new RenameOptions(true));
```

- [ ] **Step 3: Build the drlx-completion module to update local .m2**

Run: `mvn -pl drlx-completion -am install -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Build the drlx-lsp-server module to verify compilation**

Run: `mvn -pl drlx-lsp-server compile -q`
Expected: BUILD SUCCESS — the new imports and method overrides compile.

- [ ] **Step 5: Run the full test suite**

Run: `mvn test`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java \
       drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java
git commit -m "feat: wire up prepareRename and rename endpoints in LSP server (#11)"
```

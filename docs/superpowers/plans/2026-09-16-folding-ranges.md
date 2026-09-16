# Folding Ranges Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `textDocument/foldingRange` LSP capability for DRLX files, providing code folding for regions (rules/units), import blocks, block comments, and contiguous line comments.

**Architecture:** Create `DrlxFoldingRangeHelper` in `drlx-completion` that reuses `DrlxDocumentSymbolHelper` for regions, inspects AST `importDeclaration` for imports, and walks ANTLR lexer token stream for multi-line block comments and contiguous line comments. Wire up `DrlxLspServer` capabilities (`foldingRangeProvider = true`) and `DrlxLspDocumentService.foldingRange()` in `drlx-lsp-server`.

**Tech Stack:** Java 17, ANTLR4, LSP4J (0.24.0), AssertJ, JUnit 5, Maven.

## Global Constraints

- Line numbers in LSP `FoldingRange` are 0-based.
- `FoldingRange` requires `endLine > startLine` (single-line constructs do not produce folds).
- Java-style compilation units return an empty list.

---

### Task 1: Create `DrlxFoldingRangeHelperTest` with TDD

**Files:**
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxFoldingRangeHelperTest.java`
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxFoldingRangeHelper.java`

**Interfaces:**
- Produces: `DrlxFoldingRangeHelper.foldingRanges(String text) -> List<FoldingRange>`

- [ ] **Step 1: Write failing unit tests in `DrlxFoldingRangeHelperTest`**

```java
package org.drools.drlx.completion;

import java.util.List;

import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class DrlxFoldingRangeHelperTest {

    @Test
    void emptyOrNullYieldsNoRanges() {
        assertThat(DrlxFoldingRangeHelper.foldingRanges(null)).isEmpty();
        assertThat(DrlxFoldingRangeHelper.foldingRanges("")).isEmpty();
        assertThat(DrlxFoldingRangeHelper.foldingRanges("   \n  \n")).isEmpty();
    }

    @Test
    void foldsRuleBlocks() {
        String drlx =
                "unit MyUnit;\n"                    // 0
                + "rule R1 {\n"                     // 1
                + "    var p : /persons,\n"         // 2
                + "    do {\n"                      // 3
                + "        System.out.println(p);\n"// 4
                + "    }\n"                         // 5
                + "}\n";                            // 6

        List<FoldingRange> ranges = DrlxFoldingRangeHelper.foldingRanges(drlx);

        assertThat(ranges)
                .extracting(FoldingRange::getStartLine, FoldingRange::getEndLine, FoldingRange::getKind)
                .contains(tuple(1, 6, FoldingRangeKind.Region));
    }

    @Test
    void foldsMultiLineImports() {
        String drlx =
                "unit MyUnit;\n"                    // 0
                + "import org.example.Person;\n"    // 1
                + "import org.example.Address;\n"   // 2
                + "import org.example.Order;\n"     // 3
                + "rule R1 {\n"                     // 4
                + "    var p : /persons\n"          // 5
                + "}\n";                            // 6

        List<FoldingRange> ranges = DrlxFoldingRangeHelper.foldingRanges(drlx);

        assertThat(ranges)
                .extracting(FoldingRange::getStartLine, FoldingRange::getEndLine, FoldingRange::getKind)
                .contains(
                        tuple(1, 3, FoldingRangeKind.Imports),
                        tuple(4, 6, FoldingRangeKind.Region));
    }

    @Test
    void singleImportProducesNoImportsFold() {
        String drlx =
                "unit MyUnit;\n"                    // 0
                + "import org.example.Person;\n"    // 1
                + "rule R1 {\n"                     // 2
                + "    var p : /persons\n"          // 3
                + "}\n";                            // 4

        List<FoldingRange> ranges = DrlxFoldingRangeHelper.foldingRanges(drlx);

        assertThat(ranges)
                .extracting(FoldingRange::getStartLine, FoldingRange::getEndLine, FoldingRange::getKind)
                .containsExactly(tuple(2, 4, FoldingRangeKind.Region));
    }

    @Test
    void foldsBlockComments() {
        String drlx =
                "/*\n"                              // 0
                + " * Multi-line header comment\n"  // 1
                + " */\n"                           // 2
                + "unit MyUnit;\n"                  // 3
                + "rule R1 {\n"                     // 4
                + "    /* inline block\n"           // 5
                + "       comment */\n"             // 6
                + "    var p : /persons\n"          // 7
                + "}\n";                            // 8

        List<FoldingRange> ranges = DrlxFoldingRangeHelper.foldingRanges(drlx);

        assertThat(ranges)
                .extracting(FoldingRange::getStartLine, FoldingRange::getEndLine, FoldingRange::getKind)
                .contains(
                        tuple(0, 2, FoldingRangeKind.Comment),
                        tuple(5, 6, FoldingRangeKind.Comment),
                        tuple(4, 8, FoldingRangeKind.Region));
    }

    @Test
    void foldsContiguousLineComments() {
        String drlx =
                "unit MyUnit;\n"                    // 0
                + "// Line comment 1\n"             // 1
                + "// Line comment 2\n"             // 2
                + "// Line comment 3\n"             // 3
                + "rule R1 {\n"                     // 4
                + "    // isolated single line\n"   // 5
                + "    var p : /persons\n"          // 6
                + "}\n";                            // 7

        List<FoldingRange> ranges = DrlxFoldingRangeHelper.foldingRanges(drlx);

        assertThat(ranges)
                .extracting(FoldingRange::getStartLine, FoldingRange::getEndLine, FoldingRange::getKind)
                .contains(
                        tuple(1, 3, FoldingRangeKind.Comment),
                        tuple(4, 7, FoldingRangeKind.Region))
                .doesNotContain(tuple(5, 5, FoldingRangeKind.Comment));
    }

    @Test
    void javaCompilationUnitProducesNoRanges() {
        String javaCode =
                "package org.example;\n"
                + "public class Person {\n"
                + "    private String name;\n"
                + "}\n";

        assertThat(DrlxFoldingRangeHelper.foldingRanges(javaCode)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl drlx-completion -Dtest=DrlxFoldingRangeHelperTest`
Expected: FAIL (class `DrlxFoldingRangeHelper` does not exist)

- [ ] **Step 3: Implement `DrlxFoldingRangeHelper`**

```java
package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.drools.drlx.parser.DrlxParser.DrlxCompilationUnitContext;
import org.drools.drlx.parser.DrlxParser.ImportDeclarationContext;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;

public final class DrlxFoldingRangeHelper {

    private DrlxFoldingRangeHelper() {
    }

    /**
     * Returns the folding ranges for {@code text}, or an empty list.
     */
    public static List<FoldingRange> foldingRanges(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        try {
            DrlxLexer lexer = new DrlxLexer(CharStreams.fromString(text));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            DrlxParser parser = new DrlxParser(tokens);
            ParseTree parseTree = parser.drlxStart();
            if (parseTree == null || isJavaStyleCompilationUnit(parseTree)) {
                return Collections.emptyList();
            }

            List<FoldingRange> ranges = new ArrayList<>();

            // 1. Regions from document symbols (rules, etc.)
            for (DocumentSymbol symbol : DrlxDocumentSymbolHelper.symbols(text)) {
                int start = symbol.getRange().getStart().getLine();
                int end = symbol.getRange().getEnd().getLine();
                if (end > start) {
                    FoldingRange r = new FoldingRange(start, end);
                    r.setKind(FoldingRangeKind.Region);
                    ranges.add(r);
                }
            }

            // 2. Multi-line imports group
            DrlxCompilationUnitContext cu = findCompilationUnit(parseTree);
            if (cu != null && cu.importDeclaration() != null && !cu.importDeclaration().isEmpty()) {
                List<ImportDeclarationContext> imports = cu.importDeclaration();
                int firstStart = imports.get(0).getStart().getLine() - 1;
                int lastEnd = imports.get(imports.size() - 1).getStop().getLine() - 1;
                if (lastEnd > firstStart) {
                    FoldingRange r = new FoldingRange(firstStart, lastEnd);
                    r.setKind(FoldingRangeKind.Imports);
                    ranges.add(r);
                }
            }

            // 3. Comments (block comments and contiguous line comments)
            tokens.fill();
            List<Token> allTokens = tokens.getTokens();
            int lineCommentStart = -1;
            int prevLineCommentLine = -1;

            for (Token t : allTokens) {
                int type = t.getType();
                if (type == DrlxLexer.COMMENT) {
                    // Flush any pending line comments
                    if (lineCommentStart != -1 && prevLineCommentLine > lineCommentStart) {
                        FoldingRange r = new FoldingRange(lineCommentStart, prevLineCommentLine);
                        r.setKind(FoldingRangeKind.Comment);
                        ranges.add(r);
                    }
                    lineCommentStart = -1;
                    prevLineCommentLine = -1;

                    // Block comment
                    int startLine = t.getLine() - 1;
                    int endLine = getStopLine(t);
                    if (endLine > startLine) {
                        FoldingRange r = new FoldingRange(startLine, endLine);
                        r.setKind(FoldingRangeKind.Comment);
                        ranges.add(r);
                    }
                } else if (type == DrlxLexer.LINE_COMMENT) {
                    int curLine = t.getLine() - 1;
                    if (lineCommentStart == -1) {
                        lineCommentStart = curLine;
                        prevLineCommentLine = curLine;
                    } else if (curLine == prevLineCommentLine + 1) {
                        prevLineCommentLine = curLine;
                    } else {
                        if (prevLineCommentLine > lineCommentStart) {
                            FoldingRange r = new FoldingRange(lineCommentStart, prevLineCommentLine);
                            r.setKind(FoldingRangeKind.Comment);
                            ranges.add(r);
                        }
                        lineCommentStart = curLine;
                        prevLineCommentLine = curLine;
                    }
                } else if (type != DrlxLexer.WS && type != Token.EOF) {
                    // Non-whitespace, non-comment token: flush line comments
                    if (lineCommentStart != -1 && prevLineCommentLine > lineCommentStart) {
                        FoldingRange r = new FoldingRange(lineCommentStart, prevLineCommentLine);
                        r.setKind(FoldingRangeKind.Comment);
                        ranges.add(r);
                    }
                    lineCommentStart = -1;
                    prevLineCommentLine = -1;
                }
            }

            // Flush remaining line comments at end of file
            if (lineCommentStart != -1 && prevLineCommentLine > lineCommentStart) {
                FoldingRange r = new FoldingRange(lineCommentStart, prevLineCommentLine);
                r.setKind(FoldingRangeKind.Comment);
                ranges.add(r);
            }

            return ranges;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static int getStopLine(Token t) {
        String text = t.getText();
        if (text == null || text.isEmpty()) {
            return t.getLine() - 1;
        }
        int line = t.getLine() - 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static DrlxCompilationUnitContext findCompilationUnit(ParseTree node) {
        if (node instanceof DrlxCompilationUnitContext cu) return cu;
        for (int i = 0; i < node.getChildCount(); i++) {
            DrlxCompilationUnitContext found = findCompilationUnit(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isJavaStyleCompilationUnit(ParseTree node) {
        if (node instanceof DrlxParser.CompilationUnitContext) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (isJavaStyleCompilationUnit(node.getChild(i))) return true;
        }
        return false;
    }
}
```

- [ ] **Step 4: Run unit tests to verify they pass**

Run: `mvn test -pl drlx-completion -Dtest=DrlxFoldingRangeHelperTest`
Expected: PASS

- [ ] **Step 5: Commit `DrlxFoldingRangeHelper`**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxFoldingRangeHelper.java \
        drlx-completion/src/test/java/org/drools/drlx/completion/DrlxFoldingRangeHelperTest.java
git commit -m "feat: add DrlxFoldingRangeHelper for foldingRange LSP support"
```

---

### Task 2: Wire up `foldingRange` in `drlx-lsp-server`

**Files:**
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java`
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java`
- Modify: `drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspDocumentServiceTest.java`

**Interfaces:**
- Consumes: `DrlxFoldingRangeHelper.foldingRanges(String text)`
- Produces: `DrlxLspServer` capabilities advertise `foldingRangeProvider = true`, `DrlxLspDocumentService.foldingRange()` returns `CompletableFuture<List<FoldingRange>>`.

- [ ] **Step 1: Write integration test in `DrlxLspDocumentServiceTest`**

Add test method to `drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspDocumentServiceTest.java`:

```java
    @Test
    void foldingRange_returnsRangesForDrlxFile() throws Exception {
        String uri = "file:///test/Sample.drlx";
        String content =
                "/*\n"                              // 0
                + " * multi line comment\n"         // 1
                + " */\n"                           // 2
                + "unit MyUnit;\n"                  // 3
                + "import org.example.Person;\n"    // 4
                + "import org.example.Order;\n"     // 5
                + "rule R1 {\n"                     // 6
                + "    var p : /persons,\n"         // 7
                + "    do {\n"                      // 8
                + "        System.out.println(p);\n"// 9
                + "    }\n"                         // 10
                + "}\n";                            // 11

        service.didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "drlx", 1, content)));

        FoldingRangeRequestParams params = new FoldingRangeRequestParams(
                new TextDocumentIdentifier(uri));

        List<FoldingRange> ranges = service.foldingRange(params).get();

        assertThat(ranges)
                .extracting(FoldingRange::getStartLine, FoldingRange::getEndLine, FoldingRange::getKind)
                .contains(
                        tuple(0, 2, FoldingRangeKind.Comment),
                        tuple(4, 5, FoldingRangeKind.Imports),
                        tuple(6, 11, FoldingRangeKind.Region));
    }
```

- [ ] **Step 2: Update `DrlxLspServer` to advertise `foldingRangeProvider`**

In `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java`:
```java
capabilities.setFoldingRangeProvider(true);
```

- [ ] **Step 3: Update `DrlxLspDocumentService` to implement `foldingRange`**

In `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java`:
Import:
```java
import org.drools.drlx.completion.DrlxFoldingRangeHelper;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
```
Override `foldingRange`:
```java
    @Override
    public CompletableFuture<List<FoldingRange>> foldingRange(FoldingRangeRequestParams params) {
        String uri = params.getTextDocument().getUri();
        String text = documents.get(uri);
        if (text == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return CompletableFuture.completedFuture(DrlxFoldingRangeHelper.foldingRanges(text));
    }
```

- [ ] **Step 4: Run all server tests**

Run: `mvn -pl drlx-completion -am install && mvn test -pl drlx-lsp-server`
Expected: All tests pass including `foldingRange_returnsRangesForDrlxFile`.

- [ ] **Step 5: Commit server integration**

```bash
git add drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java \
        drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java \
        drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspDocumentServiceTest.java
git commit -m "feat: enable foldingRange capability and wire to DrlxFoldingRangeHelper"
```

---

### Task 3: Full Project Validation

- [ ] **Step 1: Run full Maven build and test suite**

Run: `mvn clean test`
Expected: All tests pass (BUILD SUCCESS).

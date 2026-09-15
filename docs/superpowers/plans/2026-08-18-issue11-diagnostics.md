# Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface ANTLR syntax errors as LSP diagnostics so editors show red squiggly underlines as the user types. First non-completion LSP feature for drlx-lsp.

**Architecture:** New `DrlxDiagnosticHelper` class attaches a `BaseErrorListener` to the DRLX ANTLR lexer and parser, collects syntax errors as LSP `Diagnostic` objects. The existing `DrlxLspDocumentService.validate()` stub is wired to call it.

**Tech Stack:** Java 17, ANTLR4, Maven, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-08-17-issue11-diagnostics-design.md`

## Global Constraints

- New source files in `/home/tkobayas/usr/work/mvel3-development/drlx-lsp/drlx-completion` (helper + tests)
- Server wiring in `/home/tkobayas/usr/work/mvel3-development/drlx-lsp/drlx-lsp-server`
- After modifying source, run `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q` before running tests
- Run tests with `mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test`
- Git operations target the source repo: `git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp`

---

### Task 1: Write `DrlxDiagnosticHelperTest` (tests first)

**File:** New — `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDiagnosticHelperTest.java`

8 test methods covering all spec cases. Use DRLX syntax (braces, `var`, `/persons`).

- [ ] **Step 1: Create the test class**

```java
package org.drools.drlx.completion;

import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxDiagnosticHelperTest {

    @Test
    void cleanFileProducesNoDiagnostics() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { System.out.println(p); }
                }
                """;
        assertThat(DrlxDiagnosticHelper.validate(text)).isEmpty();
    }

    @Test
    void syntaxErrorProducesDiagnostic() {
        String text = "rule R1 { var p : /persons[ }";
        List<Diagnostic> diags = DrlxDiagnosticHelper.validate(text);
        assertThat(diags).isNotEmpty();
    }

    @Test
    void diagnosticHasRangeSeverityAndSource() {
        String text = """
                rule R1 {
                    var p : /persons[,
                }
                """;
        List<Diagnostic> diags = DrlxDiagnosticHelper.validate(text);

        assertThat(diags).isNotEmpty();
        for (Diagnostic d : diags) {
            assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Error);
            assertThat(d.getSource()).isEqualTo("drlx-parser");
            assertThat(d.getMessage()).isNotBlank();
            assertThat(d.getRange().getStart().getLine())
                    .isEqualTo(d.getRange().getEnd().getLine());
            assertThat(d.getRange().getEnd().getCharacter())
                    .isGreaterThan(d.getRange().getStart().getCharacter());
        }
    }

    @Test
    void multipleBrokenRulesReportMultiple() {
        String text = """
                rule R1 {
                    var p : /persons[,
                }

                rule R2 {
                    var a : /addresses[,
                }
                """;
        List<Diagnostic> diags = DrlxDiagnosticHelper.validate(text);

        assertThat(diags.size()).isGreaterThanOrEqualTo(2);
        assertThat(diags.stream().map(d -> d.getRange().getStart().getLine()).distinct().count())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void lexerErrorProducesDiagnostic() {
        String text = """
                rule "R1
                {}
                """;
        List<Diagnostic> diags = DrlxDiagnosticHelper.validate(text);

        assertThat(diags).isNotEmpty();
        for (Diagnostic d : diags) {
            assertThat(d.getRange().getStart().getCharacter())
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void eofErrorRangeStaysWithinText() {
        String text = "rule R1 {";
        List<Diagnostic> diags = DrlxDiagnosticHelper.validate(text);

        assertThat(diags).isNotEmpty();
        for (Diagnostic d : diags) {
            assertThat(d.getRange().getEnd().getCharacter())
                    .as("range end must not extend past the line (len=%d): %s",
                            text.length(), d)
                    .isLessThanOrEqualTo(text.length());
        }
    }

    @Test
    void nullTextReturnsEmpty() {
        assertThat(DrlxDiagnosticHelper.validate(null)).isEmpty();
    }

    @Test
    void emptyTextReturnsEmpty() {
        assertThat(DrlxDiagnosticHelper.validate("")).isEmpty();
    }
}
```

- [ ] **Step 2: Verify tests fail to compile** (class doesn't exist yet)

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxDiagnosticHelperTest" 2>&1 | tail -5
```

Expected: compilation error — `DrlxDiagnosticHelper` not found.

---

### Task 2: Write `DrlxDiagnosticHelper`

**File:** New — `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDiagnosticHelper.java`

**Interfaces:**
- Consumes: `DrlxLexer` (from drlx-parser), `DrlxParser` (from drlx-parser), ANTLR `BaseErrorListener`
- Produces: `List<Diagnostic>` — one per syntax error

- [ ] **Step 1: Create the helper class**

```java
package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public final class DrlxDiagnosticHelper {

    private DrlxDiagnosticHelper() {
    }

    public static List<Diagnostic> validate(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);

        List<Diagnostic> diagnostics = new ArrayList<>();
        CollectingErrorListener listener = new CollectingErrorListener(diagnostics);

        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        parser.removeErrorListeners();
        parser.addErrorListener(listener);

        parser.drlxStart();
        return diagnostics;
    }

    private static class CollectingErrorListener extends BaseErrorListener {

        private final List<Diagnostic> diagnostics;

        CollectingErrorListener(List<Diagnostic> diagnostics) {
            this.diagnostics = diagnostics;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg,
                                RecognitionException e) {
            int startCol = charPositionInLine;
            int endCol = charPositionInLine + 1;
            if (offendingSymbol instanceof Token token) {
                if (token.getType() == Token.EOF) {
                    startCol = Math.max(0, charPositionInLine - 1);
                    endCol = charPositionInLine;
                } else {
                    String tokenText = token.getText();
                    if (tokenText != null && !tokenText.isEmpty()
                            && tokenText.indexOf('\n') < 0 && tokenText.indexOf('\r') < 0) {
                        endCol = charPositionInLine + tokenText.length();
                    }
                }
            }
            Diagnostic d = new Diagnostic();
            d.setRange(new Range(new Position(line - 1, startCol),
                                 new Position(line - 1, endCol)));
            d.setSeverity(DiagnosticSeverity.Error);
            d.setSource("drlx-parser");
            d.setMessage(msg);
            diagnostics.add(d);
        }
    }
}
```

- [ ] **Step 2: Install and run diagnostic tests**

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion -am install -DskipTests -q
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test -Dtest="DrlxDiagnosticHelperTest"
```

Expected: all 8 tests pass. If any fail, adjust test inputs (DRLX syntax may differ from assumptions — e.g. the clean file test input must be valid DRLX).

- [ ] **Step 3: Run full test suite**

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion test
```

Expected: 136+ tests pass, 0 fail, 1 pre-existing skip. No regressions.

---

### Task 3: Wire into `DrlxLspDocumentService`

**File:** Modify — `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java`

- [ ] **Step 1: Replace `validate()` stub**

Change the method signature and body (currently at lines 65–68):

From:
```java
    private List<Diagnostic> validate() {
        // TODO: Implement Drlx validation
        return Collections.emptyList();
    }
```

To:
```java
    private List<Diagnostic> validate(String uri) {
        String text = sourcesMap.get(uri);
        if (text == null) {
            return Collections.emptyList();
        }
        return DrlxDiagnosticHelper.validate(text);
    }
```

Add import: `import org.drools.drlx.completion.DrlxDiagnosticHelper;`

- [ ] **Step 2: Update call sites to pass URI**

In `didOpen` (line 58–62), change `validate()` to `validate(uri)`:

```java
        CompletableFuture.runAsync(() ->
                                           server.getClient().publishDiagnostics(
                                                   new PublishDiagnosticsParams(uri, validate(uri))
                                           )
        );
```

In `didChange` (line 78–82), same change — use the local `uri` variable:

```java
        CompletableFuture.runAsync(() ->
                                           server.getClient().publishDiagnostics(
                                                   new PublishDiagnosticsParams(uri, validate(uri))
                                           )
        );
```

- [ ] **Step 3: Run full test suite across both modules**

```bash
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion,drlx-lsp-server -am install -DskipTests -q
mvn -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp -pl drlx-completion,drlx-lsp-server test
```

Expected: all tests pass across both modules.

- [ ] **Step 4: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add \
  drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDiagnosticHelper.java \
  drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDiagnosticHelperTest.java \
  drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java

git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: add syntax error diagnostics (issue #11, item 1)"
```

# DrlxLintHelper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `DrlxLintHelper` — a heuristic lint pass that detects OOPath filter `[`/`]` bracket imbalance and integrates it into the existing `textDocument/publishDiagnostics` pipeline.

**Architecture:** A stateless utility class (`DrlxLintHelper`) in `drlx-completion` runs a single-pass text scan over the document. It sanitizes comments and string literals, then walks through rule pattern bodies tracking `[`/`]` depth. Results are merged with ANTLR diagnostics inside `DrlxLspDocumentService.validate()`.

**Tech Stack:** Java 17+, lsp4j `Diagnostic` / `Position` / `Range`, JUnit 5 + AssertJ (existing test stack).

## Global Constraints

- No dependency on ANTLR parse tree — heuristic pass must work even when parsing fails partially.
- Max diagnostics per pass: 20 (consistent with drools-lsp pattern).
- `DrlxLintHelper` MUST NOT call `DrlxDiagnosticHelper` — callers merge results.
- Diagnostic `source` field MUST be `"drlx-lint"`.
- Diagnostic messages:
  - Unclosed `[`: `"Unclosed '[' in OOPath filter — missing ']'"`
  - Unmatched `]`: `"Unmatched ']' — no corresponding '['"` 
- Configuration system property: `drlx.lsp.lint.unbalancedOopathBrackets` (values: `off|hint|info|warning|error`, default: `warning`).
- Range for each diagnostic: single character at the offending `[` or `]` (start col == end col + 1).
- Public API: `public static List<Diagnostic> lint(String text)`.

---

### Task 1: `DrlxLintHelper` + unit tests

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxLintHelper.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxLintHelperTest.java`

**Interfaces:**
- Produces: `DrlxLintHelper.lint(String text) → List<Diagnostic>`

---

- [ ] **Step 1: Write the failing tests**

Create `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxLintHelperTest.java`:

```java
package org.drools.drlx.completion;

import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxLintHelperTest {

    @AfterEach
    void resetSystemProperty() {
        System.clearProperty("drlx.lsp.lint.unbalancedOopathBrackets");
    }

    @Test
    void nullAndEmptyReturnEmpty() {
        assertThat(DrlxLintHelper.lint(null)).isEmpty();
        assertThat(DrlxLintHelper.lint("")).isEmpty();
    }

    @Test
    void cleanRuleIsClean() {
        String text = """
                rule R1 {
                    var $p : /persons[ age > 18 ],
                    do { System.out.println($p); }
                }
                """;
        assertThat(DrlxLintHelper.lint(text)).isEmpty();
    }

    @Test
    void unclosedBracketIsReported() {
        String text = """
                rule R1 {
                    var $p = /persons[ age > 18,
                    do { }
                }
                """;
        List<Diagnostic> diags = DrlxLintHelper.lint(text);
        assertThat(diags).hasSize(1);
        Diagnostic d = diags.get(0);
        assertThat(d.getSource()).isEqualTo("drlx-lint");
        assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
        assertThat(d.getMessage()).isEqualTo("Unclosed '[' in OOPath filter — missing ']'");
        // '[' is at line 1 (0-based), column 22
        assertThat(d.getRange().getStart().getLine()).isEqualTo(1);
        assertThat(d.getRange().getStart().getCharacter()).isEqualTo(22);
        assertThat(d.getRange().getEnd().getCharacter())
                .isEqualTo(d.getRange().getStart().getCharacter() + 1);
    }

    @Test
    void unmatchedCloseBracketIsReported() {
        String text = """
                rule R1 {
                    var $p = /persons] age > 18,
                    do { }
                }
                """;
        List<Diagnostic> diags = DrlxLintHelper.lint(text);
        assertThat(diags).hasSize(1);
        Diagnostic d = diags.get(0);
        assertThat(d.getSource()).isEqualTo("drlx-lint");
        assertThat(d.getMessage()).isEqualTo("Unmatched ']' — no corresponding '['");
        // ']' is at line 1 (0-based), column 22
        assertThat(d.getRange().getStart().getLine()).isEqualTo(1);
        assertThat(d.getRange().getStart().getCharacter()).isEqualTo(22);
    }

    @Test
    void multipleUnclosedBracketsReported() {
        String text = """
                rule R1 {
                    var $p = /persons[ age > foo[ 18,
                    do { }
                }
                """;
        List<Diagnostic> diags = DrlxLintHelper.lint(text);
        assertThat(diags).hasSize(2);
        assertThat(diags).allMatch(d -> d.getMessage()
                .equals("Unclosed '[' in OOPath filter — missing ']'"));
    }

    @Test
    void bracketInStringIsIgnored() {
        String text = """
                rule R1 {
                    var $p = /persons[ name == "[" ],
                    do { }
                }
                """;
        assertThat(DrlxLintHelper.lint(text)).isEmpty();
    }

    @Test
    void bracketInCommentIsIgnored() {
        String text = """
                rule R1 {
                    // [
                    var $p = /persons[ age > 18 ],
                    do { }
                }
                """;
        assertThat(DrlxLintHelper.lint(text)).isEmpty();
    }

    @Test
    void consequenceBracketsIgnored() {
        String text = """
                rule R1 {
                    var $p = /persons,
                    do { int[] arr = new int[3]; }
                }
                """;
        assertThat(DrlxLintHelper.lint(text)).isEmpty();
    }

    @Test
    void passDisabledBySystemProperty() {
        System.setProperty("drlx.lsp.lint.unbalancedOopathBrackets", "off");
        String text = """
                rule R1 {
                    var $p = /persons[ age > 18,
                    do { }
                }
                """;
        assertThat(DrlxLintHelper.lint(text)).isEmpty();
    }
}
```

- [ ] **Step 2: Run the tests to confirm they fail**

```bash
cd drlx-completion && mvn test -pl . -Dtest=DrlxLintHelperTest -q 2>&1 | tail -20
```

Expected: compilation error `DrlxLintHelper` not found.

- [ ] **Step 3: Implement `DrlxLintHelper`**

Create `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxLintHelper.java`:

```java
package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public final class DrlxLintHelper {

    private static final int MAX_DIAGNOSTICS = 20;

    private DrlxLintHelper() {
    }

    public static List<Diagnostic> lint(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        DiagnosticSeverity severity = resolveSeverity();
        if (severity == null) {
            return Collections.emptyList();
        }
        String sanitized = sanitize(text);
        return lintUnbalancedOopathBrackets(sanitized, severity);
    }

    // ---- configuration ------------------------------------------------

    private static DiagnosticSeverity resolveSeverity() {
        String val = System.getProperty("drlx.lsp.lint.unbalancedOopathBrackets", "warning")
                .trim().toLowerCase();
        return switch (val) {
            case "off"     -> null;
            case "hint"    -> DiagnosticSeverity.Hint;
            case "info"    -> DiagnosticSeverity.Information;
            case "error"   -> DiagnosticSeverity.Error;
            default        -> DiagnosticSeverity.Warning; // "warning" and unknown values
        };
    }

    // ---- sanitize -------------------------------------------------------

    /**
     * Replaces comment and string-literal content with spaces (preserving
     * newlines) so that '['/']' inside those regions cannot affect bracket
     * depth counting.
     */
    static String sanitize(String text) {
        char[] src = text.toCharArray();
        char[] out = src.clone();
        int i = 0;
        int len = src.length;
        while (i < len) {
            // block comment
            if (i + 1 < len && src[i] == '/' && src[i + 1] == '*') {
                out[i] = ' '; out[i + 1] = ' ';
                i += 2;
                while (i < len) {
                    if (i + 1 < len && src[i] == '*' && src[i + 1] == '/') {
                        out[i] = ' '; out[i + 1] = ' ';
                        i += 2;
                        break;
                    }
                    if (src[i] != '\n' && src[i] != '\r') {
                        out[i] = ' ';
                    }
                    i++;
                }
            // line comment
            } else if (i + 1 < len && src[i] == '/' && src[i + 1] == '/') {
                out[i] = ' '; out[i + 1] = ' ';
                i += 2;
                while (i < len && src[i] != '\n' && src[i] != '\r') {
                    out[i] = ' ';
                    i++;
                }
            // string literal
            } else if (src[i] == '"') {
                out[i] = '"';
                i++;
                while (i < len && src[i] != '"' && src[i] != '\n' && src[i] != '\r') {
                    if (src[i] == '\\' && i + 1 < len) {
                        out[i] = ' '; i++;
                        out[i] = ' '; i++;
                    } else {
                        out[i] = ' ';
                        i++;
                    }
                }
                if (i < len && src[i] == '"') {
                    out[i] = '"';
                    i++;
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    // ---- lint pass ------------------------------------------------------

    private static List<Diagnostic> lintUnbalancedOopathBrackets(
            String sanitized, DiagnosticSeverity severity) {

        List<Diagnostic> result = new ArrayList<>();
        String[] lines = sanitized.split("\n", -1);

        enum State { OUTSIDE_RULE, IN_PATTERN, IN_CONSEQUENCE }
        State state = State.OUTSIDE_RULE;

        // Track rule-body brace depth to find the closing '}'
        int ruleBraceDepth = 0;

        // Per-pattern-item bracket tracking
        int bracketDepth = 0;
        // openStack: int[] {line, col} for each unmatched '['
        List<int[]> openStack = new ArrayList<>();

        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];

            if (state == State.OUTSIDE_RULE) {
                if (line.matches("\\s*rule\\b.*")) {
                    state = State.IN_PATTERN;
                    ruleBraceDepth = 0;
                    bracketDepth = 0;
                    openStack.clear();
                    // Count any '{' on the rule-keyword line
                    for (char c : line.toCharArray()) {
                        if (c == '{') ruleBraceDepth++;
                        else if (c == '}') ruleBraceDepth--;
                    }
                }
                continue;
            }

            // IN_PATTERN or IN_CONSEQUENCE: scan character by character
            char[] chars = line.toCharArray();

            if (state == State.IN_PATTERN) {
                // Check for 'do' keyword that starts consequence
                if (line.matches(".*\\bdo\\b.*")) {
                    // Flush any unclosed '[' before entering consequence
                    for (int[] pos : openStack) {
                        if (result.size() < MAX_DIAGNOSTICS) {
                            result.add(makeDiag(pos[0], pos[1], pos[1] + 1, severity,
                                    "Unclosed '[' in OOPath filter — missing ']'"));
                        }
                    }
                    bracketDepth = 0;
                    openStack.clear();
                    state = State.IN_CONSEQUENCE;
                    // Fall through to track brace depth on this line
                }
            }

            // Track rule-body brace depth in all non-OUTSIDE states
            for (int col = 0; col < chars.length; col++) {
                char c = chars[col];

                if (state == State.IN_PATTERN) {
                    if (c == '[') {
                        bracketDepth++;
                        openStack.add(new int[]{lineIdx, col});
                    } else if (c == ']') {
                        if (bracketDepth > 0) {
                            bracketDepth--;
                            openStack.remove(openStack.size() - 1);
                        } else {
                            if (result.size() < MAX_DIAGNOSTICS) {
                                result.add(makeDiag(lineIdx, col, col + 1, severity,
                                        "Unmatched ']' — no corresponding '['"));
                            }
                        }
                    } else if (c == ',' && bracketDepth == 0) {
                        // End of one pattern item — flush unclosed '['
                        for (int[] pos : openStack) {
                            if (result.size() < MAX_DIAGNOSTICS) {
                                result.add(makeDiag(pos[0], pos[1], pos[1] + 1, severity,
                                        "Unclosed '[' in OOPath filter — missing ']'"));
                            }
                        }
                        bracketDepth = 0;
                        openStack.clear();
                    }
                }

                // Track rule brace depth (in both IN_PATTERN and IN_CONSEQUENCE)
                if (c == '{') {
                    ruleBraceDepth++;
                } else if (c == '}') {
                    ruleBraceDepth--;
                    if (ruleBraceDepth <= 0) {
                        // Rule body closed — flush remaining unclosed '[' if in pattern
                        if (state == State.IN_PATTERN) {
                            for (int[] pos : openStack) {
                                if (result.size() < MAX_DIAGNOSTICS) {
                                    result.add(makeDiag(pos[0], pos[1], pos[1] + 1, severity,
                                            "Unclosed '[' in OOPath filter — missing ']'"));
                                }
                            }
                        }
                        bracketDepth = 0;
                        openStack.clear();
                        state = State.OUTSIDE_RULE;
                        break; // done with this line
                    }
                }
            }
        }
        return result;
    }

    private static Diagnostic makeDiag(int line, int startCol, int endCol,
                                        DiagnosticSeverity severity, String message) {
        Diagnostic d = new Diagnostic();
        d.setRange(new Range(new Position(line, startCol), new Position(line, endCol)));
        d.setSeverity(severity);
        d.setSource("drlx-lint");
        d.setMessage(message);
        return d;
    }
}
```

- [ ] **Step 4: Run the unit tests to verify they pass**

```bash
cd drlx-completion && mvn -pl . -am test -Dtest=DrlxLintHelperTest -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, all 9 tests green.

- [ ] **Step 5: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxLintHelper.java \
        drlx-completion/src/test/java/org/drools/drlx/completion/DrlxLintHelperTest.java
git commit -m "feat: add DrlxLintHelper for OOPath filter bracket balance (#11)"
```

---

### Task 2: Server integration — `DrlxLspDocumentService.validate()` + integration test

**Files:**
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java:89-95`
- Modify: `drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/TestHelperMethods.java`
- Modify: `drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspDocumentServiceTest.java`

**Interfaces:**
- Consumes: `DrlxLintHelper.lint(String text) → List<Diagnostic>` (from Task 1)

---

- [ ] **Step 1: Write the failing integration test**

First, expose the captured diagnostics in `TestHelperMethods` by changing the field from a local variable to an accessible list. Replace `TestHelperMethods` contents:

```java
package org.drools.drlx.lsp.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.services.LanguageClient;

public class TestHelperMethods {

    private TestHelperMethods() {
    }

    public static DrlxLspDocumentService getDrlxLspDocumentService(String drlx) {
        DrlxLspServer ls = getDrlxLspServerForDocument(drlx);
        return ls.getTextDocumentService();
    }

    /**
     * Returns a server pre-loaded with the given document. The last
     * {@code publishDiagnostics} call is captured in {@code capturedDiagnostics}.
     */
    public static DrlxLspServer getDrlxLspServerForDocument(String drlx) {
        return getDrlxLspServerForDocument(drlx, new ArrayList<>());
    }

    public static DrlxLspServer getDrlxLspServerForDocument(String drlx,
                                                              List<Diagnostic> capturedDiagnostics) {
        DrlxLspServer ls = new DrlxLspServer();
        ls.connect(new LanguageClient() {
            @Override
            public void telemetryEvent(Object object) {
            }

            @Override
            public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
                return null;
            }

            @Override
            public void showMessage(MessageParams messageParams) {
            }

            @Override
            public void publishDiagnostics(PublishDiagnosticsParams d) {
                capturedDiagnostics.clear();
                capturedDiagnostics.addAll(d.getDiagnostics());
            }

            @Override
            public void logMessage(MessageParams message) {
            }
        });

        TextDocumentItem doc = new TextDocumentItem();
        doc.setUri("myDocument");
        doc.setText(drlx);
        ls.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(doc));
        return ls;
    }
}
```

Then add the integration test to `DrlxLspDocumentServiceTest.java` (append before the closing `}`):

```java
    @Test
    void lint_reportsUnclosedOopathBracket() throws Exception {
        List<Diagnostic> captured = new ArrayList<>();
        String drlx = """
                rule R1 {
                    var $p = /persons[ age > 18,
                    do { }
                }
                """;

        TestHelperMethods.getDrlxLspServerForDocument(drlx, captured);
        // publishDiagnostics is called asynchronously; wait briefly
        Thread.sleep(200);

        assertThat(captured)
                .anyMatch(d -> "drlx-lint".equals(d.getSource()));
    }
```

Also add `import java.util.ArrayList;` to the imports section of `DrlxLspDocumentServiceTest.java` if not already present.

- [ ] **Step 2: Run the integration test to confirm it fails**

```bash
cd drlx-lsp-server && mvn -pl . -am test -Dtest=DrlxLspDocumentServiceTest#lint_reportsUnclosedOopathBracket -q 2>&1 | tail -20
```

Expected: FAIL (lint diagnostics not yet merged).

- [ ] **Step 3: Merge lint results into `DrlxLspDocumentService.validate()`**

In `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java`, replace:

```java
import org.drools.drlx.completion.DrlxDiagnosticHelper;
```
with:
```java
import org.drools.drlx.completion.DrlxDiagnosticHelper;
import org.drools.drlx.completion.DrlxLintHelper;
```

And replace the `validate` method (lines 89–95):

```java
    private List<Diagnostic> validate(String uri) {
        String text = sourcesMap.get(uri);
        if (text == null) {
            return Collections.emptyList();
        }
        return DrlxDiagnosticHelper.validate(text);
    }
```

with:

```java
    private List<Diagnostic> validate(String uri) {
        String text = sourcesMap.get(uri);
        if (text == null) {
            return Collections.emptyList();
        }
        List<Diagnostic> result = new ArrayList<>(DrlxDiagnosticHelper.validate(text));
        result.addAll(DrlxLintHelper.lint(text));
        return result;
    }
```

Also add `import java.util.ArrayList;` to the imports in `DrlxLspDocumentService.java` if not already present.

- [ ] **Step 4: Build the server module and run the full test suite**

```bash
mvn -pl drlx-lsp-server -am test -q 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`, all tests green including the new integration test.

- [ ] **Step 5: Commit**

```bash
git add drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java \
        drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/TestHelperMethods.java \
        drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspDocumentServiceTest.java
git commit -m "feat: integrate DrlxLintHelper into document validation pipeline (#11)"
```

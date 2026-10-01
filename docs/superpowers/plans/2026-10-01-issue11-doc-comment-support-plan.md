# Doc Comment Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two static utility classes (`DrlxDocCommentParser`, `DrlxDocFormatter`) that parse and render Javadoc-style doc comments for DRLX declarations.

**Architecture:** Regex-based, direct port from drools-lsp adapted for DRLX syntax. `DrlxDocCommentParser` finds `/** ... */` blocks preceding `rule`, `unit`, or `window` declarations and extracts their bodies. `DrlxDocFormatter` expands inline Javadoc tags (`{@code}`, `{@literal}`, `{@link}`) into markdown. Both are pure static utilities with no dependencies on the parser or completion pipeline.

**Tech Stack:** Java 17, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-10-01-issue11-doc-comment-support-spec.md`

## Global Constraints

- Package: `org.drools.drlx.completion`
- No consumers wired — infrastructure only
- No new dependencies required

## Review Focus

1. **Multi-line doc body with mixed indentation** — `stripDocStars` should preserve internal indentation that survives `*`-stripping but trim leading/trailing blank lines
2. **Doc block between two declarations** — only the immediately following declaration should capture the doc, not a later one
3. **Annotation with nested parentheses before rule** — e.g. `@Salience(Math.max(1,2)) rule R1` — the regex uses `[^)]*` so nested parens would break the match. Known limitation; real-world DRLX annotations use simple values like `@Salience(10)`. If needed later, switch to a character-counting approach
4. **Empty doc body (`/** */`)** — should be silently skipped, not produce a blank entry
5. **`{@link}` with `#member` reference** — the `#member` portion must be stripped for map lookup but preserved in the display label

---

### Task 1: DrlxDocFormatter + DrlxDocFormatterTest

This is a straight port — no DRLX-specific adaptation needed. Building it first so Task 2 can reference it if needed.

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDocFormatter.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDocFormatterTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `public static String format(String body, Map<String, String> linkTargets)`

- [ ] **Step 1: Write all formatter tests**

Create `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDocFormatterTest.java`:

```java
package org.drools.drlx.completion;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxDocFormatterTest {

    @Test
    void inlineCodeBecomesBackticks() {
        assertThat(DrlxDocFormatter.format("uses {@code getX()} here", null))
                .isEqualTo("uses `getX()` here");
    }

    @Test
    void literalContentIsMarkdownEscaped() {
        assertThat(DrlxDocFormatter.format("{@literal *not bold*}", null))
                .isEqualTo("\\*not bold\\*");
    }

    @Test
    void linkWithTargetRendersMarkdownLink() {
        Map<String, String> targets = Map.of("Person", "file:///types.drlx");
        assertThat(DrlxDocFormatter.format("see {@link Person}", targets))
                .isEqualTo("see [Person](file:///types.drlx)");
    }

    @Test
    void linkLabelIsUsedWhenPresent() {
        Map<String, String> targets = Map.of("Person", "file:///types.drlx");
        assertThat(DrlxDocFormatter.format("see {@link Person the patient}", targets))
                .isEqualTo("see [the patient](file:///types.drlx)");
    }

    @Test
    void memberReferenceLooksUpTheTypePart() {
        Map<String, String> targets = Map.of("Person", "file:///types.drlx");
        assertThat(DrlxDocFormatter.format("{@link Person#name}", targets))
                .isEqualTo("[Person#name](file:///types.drlx)");
    }

    @Test
    void unresolvedLinkFallsBackToCode() {
        assertThat(DrlxDocFormatter.format("see {@link Person}", null))
                .isEqualTo("see `Person`");
    }

    @Test
    void unresolvedLinkplainFallsBackToPlainText() {
        assertThat(DrlxDocFormatter.format("see {@linkplain Person}", null))
                .isEqualTo("see Person");
    }

    @Test
    void unsupportedTagsAreLeftUntouched() {
        assertThat(DrlxDocFormatter.format("{@value Config#MAX}", null))
                .isEqualTo("{@value Config#MAX}");
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertThat(DrlxDocFormatter.format(null, null)).isNull();
        assertThat(DrlxDocFormatter.format("", null)).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl drlx-completion test -Dtest="DrlxDocFormatterTest"`
Expected: compilation failure — `DrlxDocFormatter` does not exist yet.

- [ ] **Step 3: Implement DrlxDocFormatter**

Create `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDocFormatter.java`:

```java
package org.drools.drlx.completion;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DrlxDocFormatter {

    private static final Pattern INLINE_CODE =
            Pattern.compile("\\{@code\\s+([^}]*)\\}");

    private static final Pattern INLINE_LITERAL =
            Pattern.compile("\\{@literal\\s+([^}]*)\\}");

    private static final Pattern INLINE_LINK = Pattern.compile(
            "\\{@link(plain)?\\s+([^}\\s]+)(?:\\s+([^}]+))?\\}");

    private DrlxDocFormatter() {
    }

    public static String format(String body, Map<String, String> linkTargets) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        String out = replaceWith(body, INLINE_CODE, m -> "`" + m.group(1) + "`");
        out = replaceWith(out, INLINE_LITERAL, m -> escapeMarkdown(m.group(1)));
        out = replaceWith(out, INLINE_LINK, m -> renderLink(m, linkTargets));
        return out;
    }

    private static String renderLink(Matcher m, Map<String, String> linkTargets) {
        boolean plain = m.group(1) != null;
        String ref = m.group(2);
        String label = m.group(3);
        if (label == null || label.isBlank()) {
            label = ref;
        }

        String typeKey = ref;
        int hash = typeKey.indexOf('#');
        if (hash >= 0) {
            typeKey = typeKey.substring(0, hash);
        }

        String href = linkTargets == null ? null : linkTargets.get(typeKey);
        if (href != null && !href.isEmpty()) {
            return "[" + label + "](" + href + ")";
        }
        return plain ? label : ("`" + label + "`");
    }

    private static String escapeMarkdown(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '`' || c == '*' || c == '_' || c == '{'
                    || c == '}' || c == '[' || c == ']' || c == '<' || c == '>'
                    || c == '#' || c == '+' || c == '-' || c == '.' || c == '!') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String replaceWith(String input, Pattern p,
                                      Function<Matcher, String> mapper) {
        Matcher m = p.matcher(input);
        StringBuilder sb = new StringBuilder(input.length());
        int last = 0;
        while (m.find()) {
            sb.append(input, last, m.start());
            sb.append(mapper.apply(m));
            last = m.end();
        }
        sb.append(input, last, input.length());
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl drlx-completion test -Dtest="DrlxDocFormatterTest"`
Expected: all 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDocFormatter.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDocFormatterTest.java
git commit -m "feat: add DrlxDocFormatter for inline Javadoc tag expansion (#11)"
```

---

### Task 2: DrlxDocCommentParser + DrlxDocCommentParserTest

Adapted from drools-lsp with DRLX-specific declaration regex.

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDocCommentParser.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDocCommentParserTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `public static Map<String, String> parseDocs(String text)`, `public static String docFor(String text, String name)`

- [ ] **Step 1: Write all parser tests**

Create `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDocCommentParserTest.java`:

```java
package org.drools.drlx.completion;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxDocCommentParserTest {

    @Test
    void emptyOrNullReturnsEmpty() {
        assertThat(DrlxDocCommentParser.parseDocs(null)).isEmpty();
        assertThat(DrlxDocCommentParser.parseDocs("")).isEmpty();
    }

    @Test
    void docOnRuleIsCaptured() {
        String drlx =
            "/** Matches high-risk patients. */\n"
            + "rule HighRisk {\n"
            + "    var p : /persons[age > 60],\n"
            + "    do { System.out.println(p); }\n"
            + "}\n";

        Map<String, String> docs = DrlxDocCommentParser.parseDocs(drlx);

        assertThat(docs).containsOnlyKeys("HighRisk");
        assertThat(docs.get("HighRisk"))
            .isEqualTo("Matches high-risk patients.");
    }

    @Test
    void docOnUnitIsCaptured() {
        String drlx =
            "/** The main patient-processing unit. */\n"
            + "unit org.example.PatientUnit;\n";

        Map<String, String> docs = DrlxDocCommentParser.parseDocs(drlx);

        assertThat(docs).containsEntry("org.example.PatientUnit",
            "The main patient-processing unit.");
    }

    @Test
    void docOnWindowIsCaptured() {
        String drlx =
            "/** Last 10 sensor events. */\n"
            + "window LastEvents {\n"
            + "    /events |time 10s|\n"
            + "}\n";

        Map<String, String> docs = DrlxDocCommentParser.parseDocs(drlx);

        assertThat(docs).containsEntry("LastEvents",
            "Last 10 sensor events.");
    }

    @Test
    void docOnAnnotatedRuleIsCaptured() {
        String drlx =
            "/**\n"
            + " * High-priority rule.\n"
            + " */\n"
            + "@Salience(10)\n"
            + "rule HighPriority {\n"
            + "    var p : /persons,\n"
            + "    do { System.out.println(p); }\n"
            + "}\n";

        Map<String, String> docs = DrlxDocCommentParser.parseDocs(drlx);

        assertThat(docs).containsOnlyKeys("HighPriority");
        assertThat(docs.get("HighPriority"))
            .isEqualTo("High-priority rule.");
    }

    @Test
    void docNotFollowedByDeclIsIgnored() {
        String drlx =
            "/** This is just floating text. */\n"
            + "\n"
            + "// Some comment.\n"
            + "import org.example.Foo;\n";

        assertThat(DrlxDocCommentParser.parseDocs(drlx)).isEmpty();
    }

    @Test
    void multipleDocsOnDifferentDeclsAreAllCaptured() {
        String drlx =
            "/** Doc for R1. */\n"
            + "rule R1 {\n"
            + "    var p : /persons,\n"
            + "    do { System.out.println(p); }\n"
            + "}\n"
            + "\n"
            + "/** Doc for R2. */\n"
            + "rule R2 {\n"
            + "    var p : /persons,\n"
            + "    do { System.out.println(p); }\n"
            + "}\n";

        Map<String, String> docs = DrlxDocCommentParser.parseDocs(drlx);

        assertThat(docs)
            .containsEntry("R1", "Doc for R1.")
            .containsEntry("R2", "Doc for R2.");
    }

    @Test
    void bannerCommentIsNotADoc() {
        String drlx =
            "/**************************\n"
            + "Banner above rule\n"
            + "**************************/\n"
            + "rule R1 {\n"
            + "    var p : /persons,\n"
            + "    do { System.out.println(p); }\n"
            + "}\n";

        assertThat(DrlxDocCommentParser.parseDocs(drlx)).isEmpty();
    }

    @Test
    void decorativeBannerDoesNotPolluteRealDoc() {
        String drlx =
            "package org.example;\n"
            + "\n"
            + "/**************************\n"
            + "Imports\n"
            + "**************************/\n"
            + "import org.example.Foo;\n"
            + "\n"
            + "/** Real doc for R1. */\n"
            + "rule R1 {\n"
            + "    var p : /persons,\n"
            + "    do { System.out.println(p); }\n"
            + "}\n";

        Map<String, String> docs = DrlxDocCommentParser.parseDocs(drlx);

        assertThat(docs).containsOnlyKeys("R1");
        assertThat(docs.get("R1")).isEqualTo("Real doc for R1.");
    }

    @Test
    void multiLineDocBodyPreservesInternalStructure() {
        String drlx =
            "/**\n"
            + " * First line of description.\n"
            + " * Second line of description.\n"
            + " */\n"
            + "rule MultiLine {\n"
            + "    var p : /persons,\n"
            + "    do { System.out.println(p); }\n"
            + "}\n";

        Map<String, String> docs = DrlxDocCommentParser.parseDocs(drlx);

        assertThat(docs.get("MultiLine"))
            .isEqualTo("First line of description.\nSecond line of description.");
    }

    @Test
    void emptyDocBodyIsSkipped() {
        String drlx = "/** */\nrule R1 {\n    var p : /persons,\n    do { System.out.println(p); }\n}\n";
        assertThat(DrlxDocCommentParser.parseDocs(drlx)).isEmpty();
    }

    @Test
    void docConvenienceLookup() {
        String drlx = "/** Hello. */\nrule Foo {\n    var p : /persons,\n    do { System.out.println(p); }\n}\n";
        assertThat(DrlxDocCommentParser.docFor(drlx, "Foo")).isEqualTo("Hello.");
        assertThat(DrlxDocCommentParser.docFor(drlx, "Bar")).isNull();
        assertThat(DrlxDocCommentParser.docFor(null, "Foo")).isNull();
        assertThat(DrlxDocCommentParser.docFor(drlx, null)).isNull();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl drlx-completion test -Dtest="DrlxDocCommentParserTest"`
Expected: compilation failure — `DrlxDocCommentParser` does not exist yet.

- [ ] **Step 3: Implement DrlxDocCommentParser**

Create `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDocCommentParser.java`:

```java
package org.drools.drlx.completion;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DrlxDocCommentParser {

    private static final Pattern DOC_BLOCK =
            Pattern.compile("(?s)/\\*\\*(?!\\*)(.*?)\\*/");

    private static final Pattern DECL_AFTER_DOC = Pattern.compile(
            "(?s)\\A(?:\\s*//[^\\n]*\\n|\\s|@\\w+(?:\\([^)]*\\))?)*"
            + "(?:"
            +   "rule\\s+(\\w+)"
            +   "|unit\\s+([\\w.]+)"
            +   "|window\\s+(\\w+)"
            + ")");

    private DrlxDocCommentParser() {
    }

    public static Map<String, String> parseDocs(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> docs = new HashMap<>();
        Matcher docMatcher = DOC_BLOCK.matcher(text);
        while (docMatcher.find()) {
            String body = stripDocStars(docMatcher.group(1));
            if (body.isEmpty()) {
                continue;
            }
            Matcher declMatcher = DECL_AFTER_DOC.matcher(text.substring(docMatcher.end()));
            if (!declMatcher.find()) {
                continue;
            }
            String name = firstNonNull(declMatcher.group(1), declMatcher.group(2),
                                       declMatcher.group(3));
            if (name != null && !name.isEmpty()) {
                docs.putIfAbsent(name, body);
            }
        }
        return docs;
    }

    public static String docFor(String text, String name) {
        if (text == null || name == null || name.isEmpty()) {
            return null;
        }
        return parseDocs(text).get(name);
    }

    static String stripDocStars(String raw) {
        if (raw == null) {
            return "";
        }
        String[] lines = raw.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].replaceFirst("^\\s*\\*\\s?", "");
            int end = stripped.length();
            while (end > 0 && Character.isWhitespace(stripped.charAt(end - 1))) {
                end--;
            }
            sb.append(stripped, 0, end);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString().strip();
    }

    private static String firstNonNull(String... candidates) {
        for (String c : candidates) {
            if (c != null) {
                return c;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl drlx-completion test -Dtest="DrlxDocCommentParserTest"`
Expected: all 12 tests PASS.

- [ ] **Step 5: Run full test suite to check for regressions**

Run: `mvn -pl drlx-completion test`
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDocCommentParser.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDocCommentParserTest.java
git commit -m "feat: add DrlxDocCommentParser for DRLX doc comment extraction (#11)"
```

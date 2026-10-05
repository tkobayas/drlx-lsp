# JavaSourceLocator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable go-to-definition to jump directly from type names in `.drlx` files to their `.java` source files via Maven convention mapping.

**Architecture:** A static utility `JavaSourceLocator` maps FQCNs to project Java sources by navigating from `target/classes` up to the module root and checking `src/main/java`. `DrlxDefinitionHelper` gains a new resolution stage (binding → Java source → import fallback) that calls `JavaSourceLocator` after resolving the word to an FQCN from imports or `ClassIndex`.

**Tech Stack:** Java 17, LSP4J, JUnit 5, AssertJ, `@TempDir`

**Spec:** `docs/superpowers/specs/2026-10-05-issue11-java-source-locator-spec.md`

## Global Constraints

- Package-private visibility for `JavaSourceLocator` (same package as `DrlxDefinitionHelper`)
- Only `src/main/java` is searched (not `src/test/java`, not Gradle layouts)
- JAR-only classes return `null` — no navigable location
- Existing `DrlxDefinitionHelper.definition()` signature is unchanged
- All existing tests must continue to pass without modification

## Review Focus

1. **FQCN with no import and multiple ClassIndex hits** — `resolveFqcn` must return `null` (not pick arbitrarily) when `ClassIndex.getBySimpleName` returns more than one candidate. Test added in Task 2 step 11.
2. **Inner class FQCN with `$`** — `JavaSourceLocator.locate("com.example.Outer$Inner", ...)` should return `null` (the relative path would contain `$` and not match a `.java` file). Test added in Task 1 step 11.
3. **Binding name that shadows a type name** — if both a binding `Pet` and an import `org.example.Pet` exist, binding resolution must win (stage 1 beats stage 2). Covered by existing test pattern; verified by resolution order in Task 2.
4. **Default-package class (no dots in FQCN)** — `resolveFqcn` returns `"Pet"` (no package), `JavaSourceLocator` derives `relClass = "Pet.class"`, navigates correctly. Test added in Task 1 step 13.
5. **`buildOutputDirs()` called before `rebuild()`** — returns `Set.of()` (empty), so `JavaSourceLocator.locate` returns `null`. Safe by field initializer.

---

### Task 1: JavaSourceLocator + Unit Tests

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/JavaSourceLocator.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/JavaSourceLocatorTest.java`

**Interfaces:**
- Consumes: nothing (self-contained utility)
- Produces: `JavaSourceLocator.locate(String fqcn, Set<Path> buildOutputDirs) → Result` and `JavaSourceLocator.Result` (fields: `Location location`, `SymbolKind kind`), consumed by Task 2

- [ ] **Step 1: Write the test class scaffold with the first failing test — `locate_findsJavaSource`**

Create `drlx-completion/src/test/java/org/drools/drlx/completion/JavaSourceLocatorTest.java`:

```java
package org.drools.drlx.completion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSourceLocatorTest {

    @Test
    void locate_findsJavaSource(@TempDir Path module) throws Exception {
        Path classes = module.resolve("target/classes/org/example");
        Files.createDirectories(classes);
        Files.createFile(classes.resolve("Pet.class"));
        Path srcDir = module.resolve("src/main/java/org/example");
        Files.createDirectories(srcDir);
        Path petJava = srcDir.resolve("Pet.java");
        Files.writeString(petJava, "package org.example;\npublic class Pet {\n}\n");

        JavaSourceLocator.Result result = JavaSourceLocator.locate(
                "org.example.Pet", Set.of(module.resolve("target/classes")));

        assertThat(result).isNotNull();
        assertThat(result.location.getUri()).isEqualTo(petJava.toUri().toString());
        assertThat(result.location.getRange().getStart().getLine()).isEqualTo(1);
        assertThat(result.location.getRange().getStart().getCharacter()).isEqualTo(13);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl drlx-completion test -Dtest="JavaSourceLocatorTest#locate_findsJavaSource" -q`
Expected: compilation error — `JavaSourceLocator` does not exist yet.

- [ ] **Step 3: Write `JavaSourceLocator` implementation**

Create `drlx-completion/src/main/java/org/drools/drlx/completion/JavaSourceLocator.java`:

```java
package org.drools.drlx.completion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JavaSourceLocator {

    private static final Logger logger = LoggerFactory.getLogger(JavaSourceLocator.class);

    private static final String TYPE_DECL_TEMPLATE = "\\b(class|interface|enum|record)\\s+(%s)\\b";

    private JavaSourceLocator() {
    }

    static final class Result {
        final Location location;
        final SymbolKind kind;

        Result(Location location, SymbolKind kind) {
            this.location = location;
            this.kind = kind;
        }
    }

    static Result locate(String fqcn, Set<Path> buildOutputDirs) {
        if (fqcn == null || fqcn.isEmpty() || buildOutputDirs == null || buildOutputDirs.isEmpty()) {
            return null;
        }
        String relClass = fqcn.replace('.', '/') + ".class";
        String relJava = fqcn.replace('.', '/') + ".java";
        String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);

        for (Path outputDir : buildOutputDirs) {
            if (!Files.isRegularFile(outputDir.resolve(relClass))) {
                continue;
            }
            Path target = outputDir.getParent();
            Path module = target == null ? null : target.getParent();
            if (module == null) {
                continue;
            }
            Path javaFile = module.resolve("src/main/java").resolve(relJava);
            if (!Files.isRegularFile(javaFile)) {
                continue;
            }
            return readDeclaration(javaFile, simpleName);
        }
        return null;
    }

    private static Result readDeclaration(Path javaFile, String simpleName) {
        Pattern decl = Pattern.compile(String.format(TYPE_DECL_TEMPLATE, Pattern.quote(simpleName)));
        String uri = javaFile.toUri().toString();
        try {
            List<String> lines = Files.readAllLines(javaFile);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = decl.matcher(lines.get(i));
                if (m.find()) {
                    Range range = new Range(new Position(i, m.start(2)),
                                            new Position(i, m.start(2) + simpleName.length()));
                    return new Result(new Location(uri, range), kindOf(m.group(1)));
                }
            }
        } catch (Exception e) {
            logger.debug("Could not locate declaration in {}: {}", javaFile, e.getMessage());
        }
        return new Result(new Location(uri, new Range(new Position(0, 0), new Position(0, 0))),
                          SymbolKind.Class);
    }

    private static SymbolKind kindOf(String keyword) {
        switch (keyword) {
            case "interface":
                return SymbolKind.Interface;
            case "enum":
                return SymbolKind.Enum;
            default:
                return SymbolKind.Class;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl drlx-completion test -Dtest="JavaSourceLocatorTest#locate_findsJavaSource" -q`
Expected: PASS

- [ ] **Step 5: Add `locate_interface` test**

Append to `JavaSourceLocatorTest.java`:

```java
@Test
void locate_interface(@TempDir Path module) throws Exception {
    Path classes = module.resolve("target/classes/org/example");
    Files.createDirectories(classes);
    Files.createFile(classes.resolve("Pet.class"));
    Path srcDir = module.resolve("src/main/java/org/example");
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("Pet.java"),
            "package org.example;\npublic interface Pet {\n}\n");

    JavaSourceLocator.Result result = JavaSourceLocator.locate(
            "org.example.Pet", Set.of(module.resolve("target/classes")));

    assertThat(result).isNotNull();
    assertThat(result.kind).isEqualTo(org.eclipse.lsp4j.SymbolKind.Interface);
}
```

- [ ] **Step 6: Add `locate_enum` test**

Append to `JavaSourceLocatorTest.java`:

```java
@Test
void locate_enum(@TempDir Path module) throws Exception {
    Path classes = module.resolve("target/classes/org/example");
    Files.createDirectories(classes);
    Files.createFile(classes.resolve("Color.class"));
    Path srcDir = module.resolve("src/main/java/org/example");
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("Color.java"),
            "package org.example;\npublic enum Color { RED, GREEN }\n");

    JavaSourceLocator.Result result = JavaSourceLocator.locate(
            "org.example.Color", Set.of(module.resolve("target/classes")));

    assertThat(result).isNotNull();
    assertThat(result.kind).isEqualTo(org.eclipse.lsp4j.SymbolKind.Enum);
    assertThat(result.location.getRange().getStart().getLine()).isEqualTo(1);
}
```

- [ ] **Step 7: Add guard-clause tests**

Append to `JavaSourceLocatorTest.java`:

```java
@Test
void locate_nullFqcn_returnsNull() {
    assertThat(JavaSourceLocator.locate(null, Set.of(Path.of("/tmp")))).isNull();
}

@Test
void locate_emptyBuildOutputDirs_returnsNull() {
    assertThat(JavaSourceLocator.locate("org.example.Pet", Set.of())).isNull();
}

@Test
void locate_noClassFile_returnsNull(@TempDir Path module) throws Exception {
    Path srcDir = module.resolve("src/main/java/org/example");
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("Pet.java"), "package org.example;\npublic class Pet {}\n");
    Files.createDirectories(module.resolve("target/classes"));

    JavaSourceLocator.Result result = JavaSourceLocator.locate(
            "org.example.Pet", Set.of(module.resolve("target/classes")));

    assertThat(result).isNull();
}

@Test
void locate_noJavaSource_returnsNull(@TempDir Path module) throws Exception {
    Path classes = module.resolve("target/classes/org/example");
    Files.createDirectories(classes);
    Files.createFile(classes.resolve("Pet.class"));

    JavaSourceLocator.Result result = JavaSourceLocator.locate(
            "org.example.Pet", Set.of(module.resolve("target/classes")));

    assertThat(result).isNull();
}
```

- [ ] **Step 8: Add `locate_fallbackToLineZero` test**

Append to `JavaSourceLocatorTest.java`:

```java
@Test
void locate_fallbackToLineZero(@TempDir Path module) throws Exception {
    Path classes = module.resolve("target/classes/org/example");
    Files.createDirectories(classes);
    Files.createFile(classes.resolve("Pet.class"));
    Path srcDir = module.resolve("src/main/java/org/example");
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("Pet.java"), "// no type declaration here\n");

    JavaSourceLocator.Result result = JavaSourceLocator.locate(
            "org.example.Pet", Set.of(module.resolve("target/classes")));

    assertThat(result).isNotNull();
    assertThat(result.location.getRange().getStart().getLine()).isEqualTo(0);
    assertThat(result.location.getRange().getStart().getCharacter()).isEqualTo(0);
    assertThat(result.kind).isEqualTo(org.eclipse.lsp4j.SymbolKind.Class);
}
```

- [ ] **Step 9: Run all JavaSourceLocator tests**

Run: `mvn -pl drlx-completion test -Dtest="JavaSourceLocatorTest" -q`
Expected: all PASS

- [ ] **Step 10: Install drlx-completion**

Run: `mvn -pl drlx-completion -am install -DskipTests -q`

- [ ] **Step 11: Add Review Focus test — inner class FQCN with `$`**

Append to `JavaSourceLocatorTest.java`:

```java
@Test
void locate_innerClass_returnsNull(@TempDir Path module) throws Exception {
    Path classes = module.resolve("target/classes/org/example");
    Files.createDirectories(classes);
    Files.createFile(classes.resolve("Outer$Inner.class"));
    Path srcDir = module.resolve("src/main/java/org/example");
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("Outer.java"),
            "package org.example;\npublic class Outer { public class Inner {} }\n");

    JavaSourceLocator.Result result = JavaSourceLocator.locate(
            "org.example.Outer$Inner", Set.of(module.resolve("target/classes")));

    assertThat(result).isNull();
}
```

The `$` in the FQCN maps to `Outer$Inner.java` which does not exist as a file — only `Outer.java` exists. So `locate` correctly returns `null`.

- [ ] **Step 12: Run test to verify**

Run: `mvn -pl drlx-completion test -Dtest="JavaSourceLocatorTest#locate_innerClass_returnsNull" -q`
Expected: PASS

- [ ] **Step 13: Add Review Focus test — default-package class**

Append to `JavaSourceLocatorTest.java`:

```java
@Test
void locate_defaultPackage(@TempDir Path module) throws Exception {
    Files.createDirectories(module.resolve("target/classes"));
    Files.createFile(module.resolve("target/classes/Pet.class"));
    Path srcDir = module.resolve("src/main/java");
    Files.createDirectories(srcDir);
    Path petJava = srcDir.resolve("Pet.java");
    Files.writeString(petJava, "public class Pet {}\n");

    JavaSourceLocator.Result result = JavaSourceLocator.locate(
            "Pet", Set.of(module.resolve("target/classes")));

    assertThat(result).isNotNull();
    assertThat(result.location.getUri()).isEqualTo(petJava.toUri().toString());
}
```

- [ ] **Step 14: Run all JavaSourceLocator tests**

Run: `mvn -pl drlx-completion test -Dtest="JavaSourceLocatorTest" -q`
Expected: all PASS

- [ ] **Step 15: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/JavaSourceLocator.java \
        drlx-completion/src/test/java/org/drools/drlx/completion/JavaSourceLocatorTest.java
git commit -m "$(cat <<'EOF'
feat(#11): add JavaSourceLocator — FQCN to Maven source mapping

Maps a classpath FQCN to its project Java source file using Maven
convention (target/classes → src/main/java). Returns the precise
declaration line via regex. JAR-only classes yield null.

Issue: #11 item #18

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: WorkspaceSemanticModel + DrlxDefinitionHelper Integration + Tests

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModel.java`
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDefinitionHelper.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDefinitionHelperTest.java`

**Interfaces:**
- Consumes: `JavaSourceLocator.locate(String, Set<Path>) → Result` from Task 1
- Produces: enhanced `DrlxDefinitionHelper.definition()` (same signature, new resolution stage)

- [ ] **Step 1: Write the first failing test — `javaSourceDefinition_directJump`**

Add to `DrlxDefinitionHelperTest.java`:

```java
@Test
void javaSourceDefinition_directJump(@TempDir Path module) throws Exception {
    Path classes = module.resolve("target/classes/org/example");
    Files.createDirectories(classes);
    Files.createFile(classes.resolve("Pet.class"));
    Path srcDir = module.resolve("src/main/java/org/example");
    Files.createDirectories(srcDir);
    Path petJava = srcDir.resolve("Pet.java");
    Files.writeString(petJava, "package org.example;\npublic class Pet {\n}\n");

    Set<Path> entries = new java.util.LinkedHashSet<>(
            new CurrentClassloaderProvider().classpathEntries());
    entries.add(module.resolve("target/classes"));
    WorkspaceSemanticModel testModel = new WorkspaceSemanticModel(() -> entries);

    // Line 0: import org.example.Pet;
    // Line 1:
    // Line 2: rule R1 {
    // Line 3:     do { Pet }
    // Line 4: }
    String text = """
            import org.example.Pet;

            rule R1 {
                do { Pet }
            }
            """;
    // "Pet" in "do { Pet }" — line 3, char 9
    List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(3, 9), testModel);

    assertThat(defs).hasSize(1);
    assertThat(defs.get(0).getUri()).isEqualTo(petJava.toUri().toString());
    assertThat(defs.get(0).getRange().getStart().getLine()).isEqualTo(1);
    assertThat(defs.get(0).getRange().getStart().getCharacter()).isEqualTo(13);
}
```

Also add these imports at the top of the file:

```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl drlx-completion test -Dtest="DrlxDefinitionHelperTest#javaSourceDefinition_directJump" -q`
Expected: FAIL — `buildOutputDirs()` method does not exist on `WorkspaceSemanticModel`, or the Java source resolution stage is not implemented.

- [ ] **Step 3: Add `classpathEntries` field and `buildOutputDirs()` to `WorkspaceSemanticModel`**

In `WorkspaceSemanticModel.java`, add the field alongside existing volatile fields:

```java
private volatile Set<Path> classpathEntries = Set.of();
```

In the `rebuild(ClasspathProvider classpathProvider, boolean resolved)` method, add the storage line **immediately after** `Set<Path> entries = classpathProvider.classpathEntries();`:

```java
this.classpathEntries = Set.copyOf(entries);
```

Add the new public method after `isClasspathResolved()`:

```java
public Set<Path> buildOutputDirs() {
    Set<Path> dirs = new java.util.LinkedHashSet<>();
    for (Path entry : classpathEntries) {
        if (java.nio.file.Files.isDirectory(entry)) {
            dirs.add(entry);
        }
    }
    return java.util.Collections.unmodifiableSet(dirs);
}
```

Add the necessary imports at the top of the file (if not already present):

```java
import java.util.Collections;
import java.util.LinkedHashSet;
```

- [ ] **Step 4: Add `resolveFqcn()` to `DrlxDefinitionHelper`**

Add a new private static method to `DrlxDefinitionHelper.java`:

```java
private static String resolveFqcn(String word, ParseTree parseTree, WorkspaceSemanticModel model) {
    DrlxCompilationUnitContext cu = findCompilationUnit(parseTree);
    if (cu != null) {
        for (ImportDeclarationContext imp : cu.importDeclaration()) {
            if (imp.qualifiedName() == null) continue;
            String fqcn = imp.qualifiedName().getText();
            String simpleName = fqcn.contains(".")
                    ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
            if (simpleName.equals(word)) {
                return fqcn;
            }
        }
    }
    List<String> candidates = model.classIndex().getBySimpleName(word);
    if (candidates.size() == 1) {
        return candidates.get(0);
    }
    return null;
}
```

Add the necessary import at the top:

```java
import java.util.List;
```

(`List` is already imported as `java.util.List` — verify and skip if present.)

- [ ] **Step 5: Restructure `definition()` method — insert Java source resolution between binding and import**

In `DrlxDefinitionHelper.definition()`, replace the block after binding resolution (lines 57–62 in the current file):

Current code:

```java
        List<Location> importDef = resolveImportDefinition(word, position, parseTree, uri);
        if (!importDef.isEmpty()) {
            return importDef;
        }

        return Collections.emptyList();
```

Replace with:

```java
        String fqcn = resolveFqcn(word, parseTree, model);
        if (fqcn != null) {
            JavaSourceLocator.Result javaSource = JavaSourceLocator.locate(fqcn, model.buildOutputDirs());
            if (javaSource != null) {
                return List.of(javaSource.location);
            }
        }

        List<Location> importDef = resolveImportDefinition(word, position, parseTree, uri);
        if (!importDef.isEmpty()) {
            return importDef;
        }

        return Collections.emptyList();
```

- [ ] **Step 6: Run the new test to verify it passes**

Run: `mvn -pl drlx-completion test -Dtest="DrlxDefinitionHelperTest#javaSourceDefinition_directJump" -q`
Expected: PASS

- [ ] **Step 7: Run all existing tests to verify no regressions**

Run: `mvn -pl drlx-completion test -Dtest="DrlxDefinitionHelperTest" -q`
Expected: all PASS (existing `importType`, `cursorOnImportLine`, etc. still work because `JavaSourceLocator` returns `null` for test domain classes in `src/test/java`)

- [ ] **Step 8: Add `javaSourceDefinition_fromImportLine` test**

Append to `DrlxDefinitionHelperTest.java`:

```java
@Test
void javaSourceDefinition_fromImportLine(@TempDir Path module) throws Exception {
    Path classes = module.resolve("target/classes/org/example");
    Files.createDirectories(classes);
    Files.createFile(classes.resolve("Pet.class"));
    Path srcDir = module.resolve("src/main/java/org/example");
    Files.createDirectories(srcDir);
    Path petJava = srcDir.resolve("Pet.java");
    Files.writeString(petJava, "package org.example;\npublic class Pet {\n}\n");

    Set<Path> entries = new java.util.LinkedHashSet<>(
            new CurrentClassloaderProvider().classpathEntries());
    entries.add(module.resolve("target/classes"));
    WorkspaceSemanticModel testModel = new WorkspaceSemanticModel(() -> entries);

    // Line 0: import org.example.Pet;
    String text = """
            import org.example.Pet;

            rule R1 {
                do { Pet }
            }
            """;
    // "Pet" on the import line — line 0, char 19
    // "import org.example.Pet;" → "Pet" starts at position 19
    List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(0, 19), testModel);

    assertThat(defs).hasSize(1);
    assertThat(defs.get(0).getUri()).isEqualTo(petJava.toUri().toString());
}
```

- [ ] **Step 9: Run test to verify**

Run: `mvn -pl drlx-completion test -Dtest="DrlxDefinitionHelperTest#javaSourceDefinition_fromImportLine" -q`
Expected: PASS — clicking on import line now resolves to `.java` source (no self-reference guard blocks it because Java source resolution runs before import resolution)

- [ ] **Step 10: Add `importFallback_whenNoJavaSource` test**

Append to `DrlxDefinitionHelperTest.java`:

```java
@Test
void importFallback_whenNoJavaSource() {
    // Uses the default model (CurrentClassloaderProvider).
    // Domain class Person is in src/test/java, not src/main/java,
    // so JavaSourceLocator returns null → falls back to import line.
    String text = """
            import org.drools.drlx.domain.Person;
            import org.drools.drlx.domain.MyUnit;

            unit MyUnit;

            rule R1(Person p) {
                do { p }
            }
            """;
    // "Person" in "rule R1(Person p)" — line 5, char 8
    List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(5, 8), model);

    assertThat(defs).hasSize(1);
    assertThat(defs.get(0).getUri()).isEqualTo(URI);
    // Falls back to import line — line 0
    assertThat(defs.get(0).getRange().getStart().getLine()).isEqualTo(0);
}
```

- [ ] **Step 11: Add Review Focus test — multiple ClassIndex hits returns empty (no arbitrary pick)**

Append to `DrlxDefinitionHelperTest.java`:

```java
@Test
void ambiguousTypeName_noJavaSource_noImport_returnsEmpty() {
    // "String" is not imported and ClassIndex likely has multiple
    // candidates (java.lang.String, etc.) — resolveFqcn should return null,
    // and with no import match, result is empty.
    String text = """
            rule R1 {
                do { String }
            }
            """;
    // "String" — line 1, char 9
    List<Location> defs = DrlxDefinitionHelper.definition(URI, text, new Position(1, 9), model);

    assertThat(defs).isEmpty();
}
```

- [ ] **Step 12: Run all DrlxDefinitionHelperTest tests**

Run: `mvn -pl drlx-completion test -Dtest="DrlxDefinitionHelperTest" -q`
Expected: all PASS

- [ ] **Step 13: Run the full test suite**

Run: `mvn -pl drlx-completion test -q`
Expected: all PASS

- [ ] **Step 14: Install**

Run: `mvn -pl drlx-completion -am install -DskipTests -q`

- [ ] **Step 15: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDefinitionHelper.java \
        drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModel.java \
        drlx-completion/src/test/java/org/drools/drlx/completion/DrlxDefinitionHelperTest.java
git commit -m "$(cat <<'EOF'
feat(#11): integrate JavaSourceLocator into go-to-definition

Type names now jump directly to .java source files instead of the
import line. Resolution order: binding → Java source → import fallback.
WorkspaceSemanticModel gains buildOutputDirs() for Maven convention
mapping.

Issue: #11 item #18

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

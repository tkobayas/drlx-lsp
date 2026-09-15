# Issue #6: Maven Classpath Resolution — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the `mvn dependency:copy-dependencies` prerequisite by resolving Maven dependency JARs via `mvn dependency:build-classpath` with two-phase async initialization.

**Architecture:** Add `MavenClasspathResolver` (shells out to `mvn`) and `MavenClasspathProvider` (thin `ClasspathProvider` wrapper) to `drlx-lsp-server`. Modify `DrlxLspServer.initialize()` to do Phase 1 (instant `target/classes`) then Phase 2 (background Maven resolution). Make `WorkspaceSemanticModel` fields `volatile` for thread safety.

**Tech Stack:** Java 17, Maven, LSP4J 0.24.0, JUnit 5, AssertJ

## Global Constraints

- Source code is at `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`
- Single-module only (no multi-module pom discovery)
- `CurrentClassloaderProvider` must remain unchanged (kept as fallback)
- After modifying a module, run `mvn -pl <module> -am install` before testing dependents
- Reference implementation: `/home/tkobayas/usr/work/mvel3-development/drools-lsp/drools-lsp-server/src/main/java/org/drools/lsp/server/MavenClasspathResolver.java`

---

### Task 1: MavenClasspathResolver

**Files:**
- Create: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/MavenClasspathResolver.java`
- Create: `drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/MavenClasspathResolverTest.java`

**Interfaces:**
- Consumes: nothing (standalone utility class)
- Produces:
  - `static Set<Path> resolve(Path workspaceRoot)` — returns `target/classes` + all Maven dependency JARs
  - `static Set<Path> resolveBuildOutputDirs(Path workspaceRoot)` — returns `target/classes` only (no Maven)

- [ ] **Step 1: Write the failing test for `resolveBuildOutputDirs`**

```java
package org.drools.drlx.lsp.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class MavenClasspathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveBuildOutputDirs_returnsTargetClasses() throws IOException {
        Path classes = tempDir.resolve("target/classes");
        Files.createDirectories(classes);

        Set<Path> dirs = MavenClasspathResolver.resolveBuildOutputDirs(tempDir);

        assertThat(dirs).containsExactly(classes);
    }

    @Test
    void resolveBuildOutputDirs_emptyWhenNoTargetClasses() {
        Set<Path> dirs = MavenClasspathResolver.resolveBuildOutputDirs(tempDir);

        assertThat(dirs).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl drlx-lsp-server -am test -Dtest=MavenClasspathResolverTest -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp/pom.xml`
Expected: FAIL — `MavenClasspathResolver` does not exist yet

- [ ] **Step 3: Implement `MavenClasspathResolver` with `resolveBuildOutputDirs`**

```java
package org.drools.drlx.lsp.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MavenClasspathResolver {

    private static final Logger logger = LoggerFactory.getLogger(MavenClasspathResolver.class);

    private MavenClasspathResolver() {
    }

    public static Set<Path> resolveBuildOutputDirs(Path workspaceRoot) {
        Set<Path> dirs = new LinkedHashSet<>();
        Path targetClasses = workspaceRoot.resolve("target/classes");
        if (Files.isDirectory(targetClasses)) {
            dirs.add(targetClasses);
        }
        return dirs;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl drlx-lsp-server -am test -Dtest=MavenClasspathResolverTest -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp/pom.xml`
Expected: PASS — both `resolveBuildOutputDirs` tests green

- [ ] **Step 5: Write the failing test for `resolve`**

Add to `MavenClasspathResolverTest.java`:

```java
    @Test
    void resolve_includesTargetClasses() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Path classes = tempDir.resolve("target/classes");
        Files.createDirectories(classes);

        Set<Path> entries = MavenClasspathResolver.resolve(tempDir);

        assertThat(entries).contains(classes);
    }

    @Test
    void resolve_returnsJarsFromRealProject() {
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        if (!Files.exists(projectRoot.resolve("pom.xml"))) {
            return; // skip if not running from the project
        }

        Set<Path> entries = MavenClasspathResolver.resolve(projectRoot);

        assertThat(entries).isNotEmpty();
        assertThat(entries).anyMatch(p -> p.toString().endsWith(".jar"));
    }
```

- [ ] **Step 6: Run test to verify it fails**

Run: `mvn -pl drlx-lsp-server -am test -Dtest=MavenClasspathResolverTest -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp/pom.xml`
Expected: FAIL — `resolve` method does not exist yet

- [ ] **Step 7: Implement `resolve` method**

Add to `MavenClasspathResolver.java`:

```java
    public static Set<Path> resolve(Path workspaceRoot) {
        Set<Path> entries = new LinkedHashSet<>();

        Path targetClasses = workspaceRoot.resolve("target/classes");
        if (Files.isDirectory(targetClasses)) {
            entries.add(targetClasses);
        }

        resolveDependencyClasspath(workspaceRoot, entries);

        return entries;
    }

    private static void resolveDependencyClasspath(Path workspaceRoot, Set<Path> entries) {
        Path pomFile = workspaceRoot.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            logger.warn("No pom.xml found at {}", workspaceRoot);
            return;
        }

        Path cpFile;
        try {
            cpFile = Files.createTempFile("drlx-lsp-cp-", ".txt");
        } catch (IOException e) {
            logger.warn("Failed to create temp file for classpath resolution", e);
            return;
        }
        try {
            String mvnCommand = System.getProperty("os.name").toLowerCase().contains("win")
                    ? "mvn.cmd" : "mvn";
            ProcessBuilder pb = new ProcessBuilder(
                    mvnCommand, "-f", pomFile.toString(),
                    "dependency:build-classpath",
                    "-Dmdep.outputFile=" + cpFile.toAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // drain stdout to prevent blocking
                }
            }

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.warn("mvn dependency:build-classpath timed out for {}", workspaceRoot);
                return;
            }
            if (process.exitValue() != 0) {
                logger.warn("mvn dependency:build-classpath failed for {} (exit code {})",
                        workspaceRoot, process.exitValue());
                return;
            }

            String cpContent = Files.readString(cpFile).trim();
            if (!cpContent.isEmpty()) {
                Arrays.stream(cpContent.split(File.pathSeparator))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Path::of)
                        .forEach(entries::add);
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve dependency classpath for {}: {}",
                    workspaceRoot, e.getMessage());
        } finally {
            try { Files.deleteIfExists(cpFile); } catch (IOException ignored) {}
        }
    }
```

- [ ] **Step 8: Run test to verify it passes**

Run: `mvn -pl drlx-lsp-server -am test -Dtest=MavenClasspathResolverTest -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp/pom.xml`
Expected: PASS — all tests green

- [ ] **Step 9: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add \
  drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/MavenClasspathResolver.java \
  drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/MavenClasspathResolverTest.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: add MavenClasspathResolver for mvn dependency:build-classpath resolution"
```

---

### Task 2: MavenClasspathProvider + Two-Phase Async Init

**Files:**
- Create: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/MavenClasspathProvider.java`
- Create: `drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/MavenClasspathProviderTest.java`
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModel.java:25-26` — make `typeSolver` and `projectClassLoader` volatile
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java:48-67` — two-phase async init

**Interfaces:**
- Consumes:
  - `MavenClasspathResolver.resolveBuildOutputDirs(Path)` → `Set<Path>`
  - `MavenClasspathResolver.resolve(Path)` → `Set<Path>`
  - `ClasspathProvider` interface (from `drlx-completion`)
  - `WorkspaceSemanticModel.rebuild(ClasspathProvider)`
- Produces:
  - `MavenClasspathProvider(Set<Path>)` constructor — implements `ClasspathProvider`
  - `MavenClasspathProvider.classpathEntries()` → `Set<Path>`

- [ ] **Step 1: Write the failing test for `MavenClasspathProvider`**

```java
package org.drools.drlx.lsp.server;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MavenClasspathProviderTest {

    @Test
    void classpathEntries_returnsConstructedEntries() {
        Set<Path> expected = new LinkedHashSet<>();
        expected.add(Path.of("/project/target/classes"));
        expected.add(Path.of("/home/user/.m2/repository/org/example/lib/1.0/lib-1.0.jar"));

        MavenClasspathProvider provider = new MavenClasspathProvider(expected);

        assertThat(provider.classpathEntries()).isEqualTo(expected);
    }

    @Test
    void classpathEntries_emptySet() {
        MavenClasspathProvider provider = new MavenClasspathProvider(Set.of());

        assertThat(provider.classpathEntries()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl drlx-lsp-server -am test -Dtest=MavenClasspathProviderTest -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp/pom.xml`
Expected: FAIL — `MavenClasspathProvider` does not exist yet

- [ ] **Step 3: Implement `MavenClasspathProvider`**

```java
package org.drools.drlx.lsp.server;

import java.nio.file.Path;
import java.util.Set;

import org.drools.drlx.completion.semantic.ClasspathProvider;

public class MavenClasspathProvider implements ClasspathProvider {

    private final Set<Path> entries;

    public MavenClasspathProvider(Set<Path> entries) {
        this.entries = entries;
    }

    @Override
    public Set<Path> classpathEntries() {
        return entries;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl drlx-lsp-server -am test -Dtest=MavenClasspathProviderTest -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp/pom.xml`
Expected: PASS

- [ ] **Step 5: Make `WorkspaceSemanticModel` fields volatile**

In `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModel.java`, change lines 25-26 from:

```java
    private CombinedTypeSolver typeSolver;
    private ClassLoader projectClassLoader;
```

to:

```java
    private volatile CombinedTypeSolver typeSolver;
    private volatile ClassLoader projectClassLoader;
```

- [ ] **Step 6: Run existing tests to verify the volatile change doesn't break anything**

Run: `mvn -pl drlx-completion -am test -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp/pom.xml`
Expected: PASS — all existing tests still green

- [ ] **Step 7: Install drlx-completion before modifying the server**

Run: `mvn -pl drlx-completion -am install -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 8: Modify `DrlxLspServer.initialize()` for two-phase async init**

Replace the `initialize` method in `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java` (lines 48-67) with:

```java
    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        String rootUri = params.getRootUri();
        logger.info("initialize: rootUri={}", rootUri);

        if (rootUri != null) {
            try {
                Path workspaceRoot = Paths.get(URI.create(rootUri));
                logger.info("initialize: workspaceRoot={}", workspaceRoot);

                // Phase 1: instant — target/classes only (no Maven invocation)
                Set<Path> buildOutputDirs = MavenClasspathResolver.resolveBuildOutputDirs(workspaceRoot);
                logger.info("initialize phase 1: buildOutputDirs={}", buildOutputDirs);
                model.rebuild(new MavenClasspathProvider(buildOutputDirs));

                // Phase 2: background — full Maven dependency resolution
                CompletableFuture.runAsync(() -> {
                    try {
                        logger.info("initialize phase 2: resolving Maven classpath...");
                        Set<Path> fullClasspath = MavenClasspathResolver.resolve(workspaceRoot);
                        logger.info("initialize phase 2: resolved {} classpath entries", fullClasspath.size());
                        model.rebuild(new MavenClasspathProvider(fullClasspath));
                    } catch (Exception e) {
                        logger.error("initialize phase 2: Maven classpath resolution failed", e);
                        if (client != null) {
                            client.showMessage(new MessageParams(MessageType.Info,
                                    "Maven classpath resolution failed; completions limited to project classes."));
                        }
                    }
                });
            } catch (Exception e) {
                logger.error("initialize: failed to set up workspace classpath", e);
            }
        }

        InitializeResult initializeResult = new InitializeResult(new ServerCapabilities());
        initializeResult.getCapabilities().setTextDocumentSync(TextDocumentSyncKind.Full);
        CompletionOptions completionOptions = new CompletionOptions();
        initializeResult.getCapabilities().setCompletionProvider(completionOptions);
        return CompletableFuture.supplyAsync(() -> initializeResult);
    }
```

Add these imports to the top of `DrlxLspServer.java`:

```java
import java.nio.file.Path;
import java.util.Set;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
```

Note: `CurrentClassloaderProvider` import stays — it's still used in the constructor (line 33) for the initial no-workspace model.

- [ ] **Step 9: Run all server tests to verify nothing breaks**

Run: `mvn -pl drlx-lsp-server -am test -f /home/tkobayas/usr/work/mvel3-development/drlx-lsp/pom.xml`
Expected: PASS — all tests green including existing `DrlxLspDocumentServiceTest`

- [ ] **Step 10: Commit**

```bash
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp add \
  drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/MavenClasspathProvider.java \
  drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/MavenClasspathProviderTest.java \
  drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModel.java \
  drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java
git -C /home/tkobayas/usr/work/mvel3-development/drlx-lsp commit -m "feat: two-phase async Maven classpath init with MavenClasspathProvider"
```

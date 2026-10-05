# File-change class index rebuild Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect `.class` file changes under `target/classes/` and rebuild the class index without restarting the LSP server.

**Architecture:** VS Code file watcher sends `didChangeWatchedFiles` notifications → `DrlxLspWorkspaceService` debounces them (1 second) → `DrlxLspServer.rebuildClassIndex()` → `WorkspaceSemanticModel.rebuildOutputDirs()` re-scans only build output directories and merges with a cached JAR-only index → `revalidateOpenDocuments()` refreshes diagnostics.

**Tech Stack:** LSP4J, VS Code Language Client, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-10-05-issue11-file-change-rebuild-spec.md`

## Global Constraints

- Java 17+
- All fields shared across threads must be `volatile` (existing pattern in `WorkspaceSemanticModel`)
- Test assertions use AssertJ (`assertThat`)
- Install modified modules before running dependent tests: `mvn -pl drlx-completion -am install -DskipTests -q`

## Review Focus

1. **Concurrent rebuild during active completion** — a `rebuildOutputDirs()` call swapping `classIndex` while a completion request reads it mid-iteration could return partial results. All swapped fields are `volatile` references to immutable or independent objects, so each reader sees a consistent snapshot of one field, but reads of multiple fields (e.g. `classIndex` + `classMemberIndex`) are not atomic. This matches the existing `rebuild()` behavior and is acceptable for an LSP server — the next request will see the fully-consistent state.
2. **Scheduler leak on shutdown** — if `DrlxLspWorkspaceService.shutdown()` is not called, the daemon scheduler thread prevents clean JVM exit in tests. Task 2 wires `shutdown()` from `DrlxLspServer.shutdown()`.
3. **Empty `buildOutputDirs` after phase-1 init** — if `rebuildClassIndex()` is called before phase-2 completes, `jarClassIndex` is still `ClassIndex.empty()` and `buildOutputDirs()` may be the only source. The early-return guard (`dirs.isEmpty() && jarClassIndex.size() == 0`) handles this correctly.
4. **`ClassMemberIndex.close()` called on stale instance during concurrent rebuild** — `rebuildOutputDirs()` calls `close()` on the old `classMemberIndex` before assigning the new one. A concurrent reader holding the old reference will get a closed classloader. This matches the existing `rebuild()` behavior — accepted trade-off.
5. **File watcher glob doesn't cover Gradle/IntelliJ output dirs** — the watcher is `**/target/classes/**/*.class` (Maven only). This matches drools-lsp and the project's Maven-only scope. Add in Review Focus: if Gradle support is needed later, extend the glob.

---

### Task 1: Add JAR index cache and `rebuildOutputDirs()` to WorkspaceSemanticModel

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModel.java`
- Test: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModelTest.java`

**Interfaces:**
- Consumes: `ClassIndex.build(Set<Path>)`, `ClassIndex.merge(ClassIndex, ClassIndex)`, `ClassIndex.empty()`, `ClassIndex.size()`, `ClassMemberIndex.of(Set<Path>)`, `ClassMemberIndex.close()`
- Produces: `void rebuildOutputDirs()` — re-scans only build output directories, merges with cached JAR index, rebuilds classloader/typeSolver/classMemberIndex. Called by `DrlxLspServer.rebuildClassIndex()` in Task 2.

- [ ] **Step 1: Write failing test — `rebuildOutputDirsPicksUpNewClasses`**

Add to `WorkspaceSemanticModelTest.java`:

```java
@Test
void rebuildOutputDirsPicksUpNewClasses(@TempDir Path tempDir) throws IOException {
    Path classDir = tempDir.resolve("classes");
    Path pkgDir = classDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.createFile(pkgDir.resolve("Foo.class"));

    WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
    model.rebuild(() -> Set.of(classDir), true);

    assertThat(model.classIndex().getMatching("Foo")).contains("com.example.Foo");

    // Add a new class file and call rebuildOutputDirs
    Files.createFile(pkgDir.resolve("Bar.class"));
    model.rebuildOutputDirs();

    assertThat(model.classIndex().getMatching("Bar")).contains("com.example.Bar");
    assertThat(model.classIndex().getMatching("Foo")).contains("com.example.Foo");
}
```

Add imports at the top of the file:

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
```

- [ ] **Step 2: Write failing test — `rebuildOutputDirsPreservesJarClasses`**

Add to `WorkspaceSemanticModelTest.java`:

```java
@Test
void rebuildOutputDirsPreservesJarClasses(@TempDir Path tempDir) throws IOException {
    Path classDir = tempDir.resolve("classes");
    Path pkgDir = classDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.createFile(pkgDir.resolve("Foo.class"));

    Path jarPath = tempDir.resolve("dep.jar");
    try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
            Files.newOutputStream(jarPath))) {
        jos.putNextEntry(new java.util.jar.JarEntry("com/acme/Order.class"));
        jos.closeEntry();
    }

    WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
    model.rebuild(() -> Set.of(classDir, jarPath), true);

    assertThat(model.classIndex().getMatching("Foo")).contains("com.example.Foo");
    assertThat(model.classIndex().getMatching("Or")).contains("com.acme.Order");

    // Delete the JAR — rebuildOutputDirs should still have JAR classes from cache
    Files.delete(jarPath);
    model.rebuildOutputDirs();

    assertThat(model.classIndex().getMatching("Foo")).contains("com.example.Foo");
    assertThat(model.classIndex().getMatching("Or")).contains("com.acme.Order");
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -pl drlx-completion test -Dtest="WorkspaceSemanticModelTest#rebuildOutputDirsPicksUpNewClasses+rebuildOutputDirsPreservesJarClasses"`

Expected: compilation error — `rebuildOutputDirs()` method does not exist.

- [ ] **Step 4: Implement `jarClassIndex` field and modify `rebuild()`**

In `WorkspaceSemanticModel.java`, add a new field after the existing `volatile` fields:

```java
private volatile ClassIndex jarClassIndex = ClassIndex.empty();
```

Modify the `rebuild(ClasspathProvider, boolean)` method. Replace the body with:

```java
public void rebuild(ClasspathProvider classpathProvider, boolean resolved) {
    Set<Path> entries = classpathProvider.classpathEntries();
    this.classpathEntries = Set.copyOf(entries);
    this.projectClassLoader = buildClassLoader(entries);
    this.typeSolver = buildTypeSolver(projectClassLoader);

    if (resolved) {
        Set<Path> jars = new LinkedHashSet<>();
        for (Path e : entries) {
            if (!Files.isDirectory(e)) {
                jars.add(e);
            }
        }
        this.jarClassIndex = jars.isEmpty() ? ClassIndex.empty() : ClassIndex.build(jars);
        ClassIndex outputIndex = ClassIndex.build(buildOutputDirs());
        this.classIndex = ClassIndex.merge(jarClassIndex, outputIndex);
    } else {
        this.classIndex = ClassIndex.build(entries);
    }

    this.classMemberIndex.close();
    this.classMemberIndex = ClassMemberIndex.of(entries);
    this.classpathResolved = resolved;
}
```

- [ ] **Step 5: Implement `rebuildOutputDirs()`**

Add to `WorkspaceSemanticModel.java`:

```java
public void rebuildOutputDirs() {
    Set<Path> dirs = buildOutputDirs();
    if (dirs.isEmpty() && jarClassIndex.size() == 0) {
        return;
    }
    ClassIndex outputIndex = ClassIndex.build(dirs);
    this.classIndex = ClassIndex.merge(jarClassIndex, outputIndex);
    this.projectClassLoader = buildClassLoader(classpathEntries);
    this.typeSolver = buildTypeSolver(projectClassLoader);
    this.classMemberIndex.close();
    this.classMemberIndex = ClassMemberIndex.of(classpathEntries);
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -pl drlx-completion test -Dtest="WorkspaceSemanticModelTest"`

Expected: all tests pass (including the existing 3 tests).

- [ ] **Step 7: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModel.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModelTest.java
git commit -m "feat(#11): add jarClassIndex cache and rebuildOutputDirs() to WorkspaceSemanticModel"
```

---

### Task 2: Wire debounced rebuild — DrlxLspWorkspaceService + DrlxLspServer

**Files:**
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspWorkspaceService.java`
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java`
- Create: `drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspServerRebuildTest.java`

**Interfaces:**
- Consumes: `WorkspaceSemanticModel.rebuildOutputDirs()` (from Task 1), `WorkspaceSemanticModel.classIndex()`, `WorkspaceSemanticModel.rebuild(ClasspathProvider, boolean)`, `DrlxLspDocumentService.revalidateOpenDocuments()`, `TestHelperMethods.getDrlxLspServerForDocument(String)`
- Produces: `DrlxLspServer.rebuildClassIndex()` — calls `model.rebuildOutputDirs()` + `textService.revalidateOpenDocuments()`. `DrlxLspWorkspaceService.DEBOUNCE_DELAY_MS` — the debounce constant (1000). `DrlxLspWorkspaceService.shutdown()` — shuts down the scheduler.

- [ ] **Step 1: Install drlx-completion with Task 1 changes**

Run: `mvn -pl drlx-completion -am install -DskipTests -q`

- [ ] **Step 2: Implement `DrlxLspWorkspaceService` with debounce**

Replace the entire content of `DrlxLspWorkspaceService.java`:

```java
package org.drools.drlx.lsp.server;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

public class DrlxLspWorkspaceService implements WorkspaceService {

    static final long DEBOUNCE_DELAY_MS = 1000;

    private final DrlxLspServer server;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "drlx-lsp-rebuild");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pendingRebuild;

    DrlxLspWorkspaceService(DrlxLspServer server) {
        this.server = server;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        ScheduledFuture<?> existing = pendingRebuild;
        if (existing != null) {
            existing.cancel(false);
        }
        pendingRebuild = scheduler.schedule(
                () -> server.rebuildClassIndex(),
                DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    void shutdown() {
        scheduler.shutdownNow();
    }
}
```

- [ ] **Step 3: Modify `DrlxLspServer` — add `model()`, `rebuildClassIndex()`, wire workspace service**

In `DrlxLspServer.java`, make these changes:

Change the constructor to pass `this` to the workspace service:

```java
public DrlxLspServer() {
    model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
    textService = new DrlxLspDocumentService(this, model);
    workspaceService = new DrlxLspWorkspaceService(this);
}
```

Change the `workspaceService` field type from `WorkspaceService` to `DrlxLspWorkspaceService`:

```java
private final DrlxLspWorkspaceService workspaceService;
```

Add the `model()` accessor (package-private, for tests):

```java
WorkspaceSemanticModel model() {
    return model;
}
```

Add `rebuildClassIndex()`:

```java
public void rebuildClassIndex() {
    model.rebuildOutputDirs();
    textService.revalidateOpenDocuments();
}
```

Modify `shutdown()` to shut down the workspace service scheduler:

```java
@Override
public CompletableFuture<Object> shutdown() {
    workspaceService.shutdown();
    model.classMemberIndex().close();
    return CompletableFuture.completedFuture(null);
}
```

- [ ] **Step 4: Write test — `rebuildClassIndexUpdatesClassIndex`**

Create `DrlxLspServerRebuildTest.java`:

```java
package org.drools.drlx.lsp.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.drools.drlx.completion.semantic.ClassIndex;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxLspServerRebuildTest {

    @TempDir
    Path tempDir;

    @Test
    void rebuildClassIndexUpdatesClassIndex() throws IOException {
        DrlxLspServer server = TestHelperMethods.getDrlxLspServerForDocument("");

        Path classDir = createClassDir("com/example/Foo.class");
        server.model().rebuild(() -> Set.of(classDir), true);

        server.rebuildClassIndex();

        ClassIndex index = server.model().classIndex();
        assertThat(index.getMatching("Foo")).contains("com.example.Foo");
    }

    private Path createClassDir(String classFilePath) throws IOException {
        Path classFile = tempDir.resolve(classFilePath);
        Files.createDirectories(classFile.getParent());
        Files.createFile(classFile);
        return tempDir;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl drlx-lsp-server test -Dtest="DrlxLspServerRebuildTest#rebuildClassIndexUpdatesClassIndex"`

Expected: PASS

- [ ] **Step 6: Write test — `didChangeWatchedFilesTriggersRebuild`**

Add to `DrlxLspServerRebuildTest.java`:

```java
@Test
void didChangeWatchedFilesTriggersRebuild() throws Exception {
    DrlxLspServer server = TestHelperMethods.getDrlxLspServerForDocument("");

    Path classDir = createClassDir("com/example/Bar.class");
    server.model().rebuild(() -> Set.of(classDir), true);

    DrlxLspWorkspaceService workspaceService =
            (DrlxLspWorkspaceService) server.getWorkspaceService();
    workspaceService.didChangeWatchedFiles(new DidChangeWatchedFilesParams());

    Thread.sleep(DrlxLspWorkspaceService.DEBOUNCE_DELAY_MS + 500);

    ClassIndex index = server.model().classIndex();
    assertThat(index.getMatching("Bar")).contains("com.example.Bar");
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `mvn -pl drlx-lsp-server test -Dtest="DrlxLspServerRebuildTest#didChangeWatchedFilesTriggersRebuild"`

Expected: PASS

- [ ] **Step 8: Write test — `rapidFileChangesCoalesceIntoOneRebuild`**

Add to `DrlxLspServerRebuildTest.java`:

```java
@Test
void rapidFileChangesCoalesceIntoOneRebuild() throws Exception {
    DrlxLspServer server = TestHelperMethods.getDrlxLspServerForDocument("");

    Path classDir = createClassDir("com/example/Baz.class");
    server.model().rebuild(() -> Set.of(classDir), true);

    DrlxLspWorkspaceService workspaceService =
            (DrlxLspWorkspaceService) server.getWorkspaceService();
    for (int i = 0; i < 100; i++) {
        workspaceService.didChangeWatchedFiles(new DidChangeWatchedFilesParams());
    }

    // Index should not be rebuilt yet (still within debounce window)
    ClassIndex indexBefore = server.model().classIndex();
    assertThat(indexBefore.getMatching("Baz")).isEmpty();

    Thread.sleep(DrlxLspWorkspaceService.DEBOUNCE_DELAY_MS + 500);

    ClassIndex indexAfter = server.model().classIndex();
    assertThat(indexAfter.getMatching("Baz")).contains("com.example.Baz");
}
```

- [ ] **Step 9: Run all rebuild tests**

Run: `mvn -pl drlx-lsp-server test -Dtest="DrlxLspServerRebuildTest"`

Expected: all 3 tests pass.

- [ ] **Step 10: Run all tests in both modules**

Run: `mvn -pl drlx-completion test` and `mvn -pl drlx-lsp-server test`

Expected: all tests pass.

- [ ] **Step 11: Commit**

```bash
git add drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspWorkspaceService.java \
       drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java \
       drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspServerRebuildTest.java
git commit -m "feat(#11): wire debounced file-change rebuild in workspace service"
```

---

### Task 3: Client file watcher

**Files:**
- Modify: `client/src/extension.ts`

**Interfaces:**
- Consumes: VS Code `workspace.createFileSystemWatcher` API
- Produces: `workspace/didChangeWatchedFiles` LSP notifications for `.class` file changes

- [ ] **Step 1: Add `synchronize.fileEvents` to `LanguageClientOptions`**

In `client/src/extension.ts`, replace the `clientOptions` block (lines 73-76):

```typescript
        let clientOptions: LanguageClientOptions = {
            // Register the server for drlx documents
            documentSelector: [{scheme: 'file', language: 'drlx'}],
            synchronize: {
                fileEvents: vscode.workspace.createFileSystemWatcher('**/target/classes/**/*.class')
            }
        };
```

- [ ] **Step 2: Commit**

```bash
git add client/src/extension.ts
git commit -m "feat(#11): add VS Code file watcher for class file changes"
```

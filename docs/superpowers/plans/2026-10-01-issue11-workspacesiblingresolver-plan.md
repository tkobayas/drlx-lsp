# WorkspaceSiblingResolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a pluggable mechanism for resolving sibling `.drlx` files in the same directory, as infrastructure for future cross-file features.

**Architecture:** An interface (`WorkspaceSiblingResolver`) defines the contract: given a file path, return the list of sibling `.drlx` files. A final utility class (`WorkspaceSiblingResolvers`) acts as a process-wide registry with a default same-directory resolver. No consumers are wired — this is infrastructure only.

**Tech Stack:** Java 21, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-10-01-issue11-workspacesiblingresolver-spec.md`

## Global Constraints

- Java 21 language features
- No new dependencies
- Package: `org.drools.drlx.completion.semantic`
- Build: `mvn -pl drlx-completion -am install -DskipTests -q` before running tests
- File extension: `.drlx` (not `.drl`)

## Review Focus

1. **Symlinked `.drlx` file** — `resolveSiblings` normalizes paths via `toAbsolutePath().normalize()`, so a symlinked file that resolves to the same absolute path as `currentFile` must be excluded, not returned as a sibling.
2. **Empty directory** — a `.drlx` file alone in its directory must get an empty list, not an error.
3. **Read-only directory** — if `Files.newDirectoryStream` throws due to permissions, the default resolver must return an empty list, not propagate the exception.
4. **Concurrent `setActive`** — the `volatile` field ensures visibility across threads; a caller reading `active()` while another thread calls `setActive()` must see a consistent (old or new) resolver, never a torn reference.
5. **Non-`.drlx` files in directory** — files like `.drl`, `.txt`, `.java` in the same directory must never appear in the sibling list.

---

### Task 1: Create WorkspaceSiblingResolver interface, WorkspaceSiblingResolvers registry, and unit tests

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSiblingResolver.java`
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSiblingResolvers.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/WorkspaceSiblingResolversTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `WorkspaceSiblingResolver.resolveSiblings(Path) → List<Path>`, `WorkspaceSiblingResolvers.active() → WorkspaceSiblingResolver`, `WorkspaceSiblingResolvers.setActive(WorkspaceSiblingResolver) → void`

- [ ] **Step 1: Write the failing tests**

Create `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/WorkspaceSiblingResolversTest.java`:

```java
package org.drools.drlx.completion.semantic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceSiblingResolversTest {

    @AfterEach
    void resetResolver() {
        WorkspaceSiblingResolvers.setActive(null);
    }

    @Test
    void defaultResolverReturnsSameDirectoryDrlxFiles(@TempDir Path tmp) throws Exception {
        Path a = Files.createFile(tmp.resolve("A.drlx"));
        Path b = Files.createFile(tmp.resolve("B.drlx"));
        Files.createFile(tmp.resolve("notes.txt"));

        List<Path> siblings = WorkspaceSiblingResolvers.active().resolveSiblings(a);

        assertThat(siblings).containsExactly(b);
    }

    @Test
    void defaultResolverHandlesNullAndMissingPaths(@TempDir Path tmp) {
        assertThat(WorkspaceSiblingResolvers.active().resolveSiblings(null)).isEmpty();
        assertThat(WorkspaceSiblingResolvers.active()
                .resolveSiblings(tmp.resolve("missing/X.drlx"))).isEmpty();
    }

    @Test
    void setActiveSwapsResolverAndNullRestoresDefault(@TempDir Path tmp) throws Exception {
        Path a = Files.createFile(tmp.resolve("A.drlx"));
        Path b = Files.createFile(tmp.resolve("B.drlx"));

        WorkspaceSiblingResolvers.setActive(file -> List.of());
        assertThat(WorkspaceSiblingResolvers.active().resolveSiblings(a)).isEmpty();

        WorkspaceSiblingResolvers.setActive(null);
        assertThat(WorkspaceSiblingResolvers.active().resolveSiblings(a)).containsExactly(b);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl drlx-completion test -Dtest="org.drools.drlx.completion.semantic.WorkspaceSiblingResolversTest" -q`

Expected: compilation error — `WorkspaceSiblingResolver` and `WorkspaceSiblingResolvers` do not exist yet.

- [ ] **Step 3: Create the interface**

Create `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSiblingResolver.java`:

```java
package org.drools.drlx.completion.semantic;

import java.nio.file.Path;
import java.util.List;

public interface WorkspaceSiblingResolver {

    List<Path> resolveSiblings(Path currentFile);
}
```

- [ ] **Step 4: Create the registry with default resolver**

Create `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSiblingResolvers.java`:

```java
package org.drools.drlx.completion.semantic;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorkspaceSiblingResolvers {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceSiblingResolvers.class);

    private static final WorkspaceSiblingResolver SAME_DIRECTORY =
            WorkspaceSiblingResolvers::sameDirectorySiblings;

    private static volatile WorkspaceSiblingResolver active = SAME_DIRECTORY;

    private WorkspaceSiblingResolvers() {
    }

    public static WorkspaceSiblingResolver active() {
        return active;
    }

    public static void setActive(WorkspaceSiblingResolver resolver) {
        active = (resolver == null) ? SAME_DIRECTORY : resolver;
    }

    private static List<Path> sameDirectorySiblings(Path currentFile) {
        if (currentFile == null) {
            return Collections.emptyList();
        }
        Path dir = currentFile.toAbsolutePath().getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        Path normalizedCurrent = currentFile.toAbsolutePath().normalize();
        List<Path> siblings = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.drlx")) {
            for (Path candidate : stream) {
                if (!candidate.toAbsolutePath().normalize().equals(normalizedCurrent)) {
                    siblings.add(candidate);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve sibling DRLX files for {}", currentFile, e);
            return Collections.emptyList();
        }
        siblings.sort(Path::compareTo);
        return siblings;
    }
}
```

- [ ] **Step 5: Build and run tests**

Run: `mvn -pl drlx-completion -am install -DskipTests -q`
Run: `mvn -pl drlx-completion test -Dtest="org.drools.drlx.completion.semantic.WorkspaceSiblingResolversTest" -q`

Expected: all 3 tests pass.

- [ ] **Step 6: Run full test suite to check for regressions**

Run: `mvn -pl drlx-completion test -q`

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSiblingResolver.java \
       drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSiblingResolvers.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/semantic/WorkspaceSiblingResolversTest.java
git commit -m "feat: add WorkspaceSiblingResolver for cross-file .drlx resolution (#11)"
```

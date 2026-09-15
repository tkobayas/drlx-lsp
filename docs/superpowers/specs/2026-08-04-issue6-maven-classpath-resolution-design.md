# Issue #6: Full Maven Classpath Resolution

## Problem

Users must run `mvn compile dependency:copy-dependencies` before opening a
project in VSCode, otherwise the LSP server cannot resolve dependency types
for completions. `CurrentClassloaderProvider` scans `target/dependency/*.jar`
which only exists after that manual step.

## Goal

Eliminate the `dependency:copy-dependencies` prerequisite by resolving Maven
dependency JARs directly from `~/.m2/repository` via
`mvn dependency:build-classpath`.

## Approach

Shell out to `mvn dependency:build-classpath` (same approach as drools-lsp).
Two-phase async initialization hides the Maven subprocess latency. Keep
`CurrentClassloaderProvider` as a fallback; add `MavenClasspathProvider` as
the new default.

## New Classes

### MavenClasspathResolver (drlx-lsp-server)

Package: `org.drools.drlx.lsp.server`

Ported from drools-lsp, trimmed to single-module.

**`resolve(Path workspaceRoot)`** — full classpath resolution:
- Collects `target/classes` if it exists
- Shells out to `mvn -f <root>/pom.xml dependency:build-classpath -Dmdep.outputFile=<tmpfile>`
- Uses `mvn.cmd` on Windows, `mvn` on Unix
- 60-second timeout, stdout drained to prevent blocking
- Parses the temp file (split on `File.pathSeparator`), returns `Set<Path>`
- Graceful failure: logs warning, returns whatever was collected so far

**`resolveBuildOutputDirs(Path workspaceRoot)`** — instant, filesystem only:
- Returns `target/classes` if the directory exists, otherwise empty set
- No Maven invocation

### MavenClasspathProvider (drlx-lsp-server)

Package: `org.drools.drlx.lsp.server`

Implements `ClasspathProvider` (from `drlx-completion`).

**Constructor:** `MavenClasspathProvider(Set<Path> resolvedEntries)`

Immutable thin wrapper. `classpathEntries()` returns `resolvedEntries`.

The server constructs it twice:
- Phase 1: `new MavenClasspathProvider(MavenClasspathResolver.resolveBuildOutputDirs(root))`
- Phase 2: `new MavenClasspathProvider(MavenClasspathResolver.resolve(root))`

### CurrentClassloaderProvider (unchanged)

Kept as-is. Available as fallback when no `rootUri` is provided.

## DrlxLspServer.initialize() — Two-Phase Async

**Phase 1 (synchronous, inside initialize()):**
1. Extract `workspaceRoot` from `rootUri`
2. Call `MavenClasspathResolver.resolveBuildOutputDirs(workspaceRoot)`
3. Build `MavenClasspathProvider` from the build output entries
4. Call `model.rebuild(provider)`
5. Return `InitializeResult` — project class completions work immediately

**Phase 2 (asynchronous, CompletableFuture.runAsync):**
1. Call `MavenClasspathResolver.resolve(workspaceRoot)`
2. On success: build `MavenClasspathProvider` with full entries, call `model.rebuild(provider)`
3. On failure: log warning, send `window/showMessage` info to client

**Fallback:** When `rootUri` is null, use `CurrentClassloaderProvider()` (no-arg).

## Thread Safety

`WorkspaceSemanticModel.typeSolver` and `projectClassLoader` become `volatile`.
`rebuild()` does full-field replacement (not incremental mutation), so volatile
is sufficient — no locks needed.

## Error Handling & Graceful Degradation

| Failure | Behavior |
|---------|----------|
| `mvn` not on PATH | `ProcessBuilder.start()` throws IOException. Logged. Phase 1 result stays. |
| Maven non-zero exit | Logged with exit code. Phase 1 result stays. |
| Maven timeout (>60s) | Process destroyed forcibly. Logged. Phase 1 result stays. |
| No `target/classes` | Phase 1 returns empty. System classloader fallback (keyword completions only). |
| No `pom.xml` | Maven fails. Caught, logged. Phase 1 result stays. |

On Phase 2 failure, send `window/showMessage` info: "Maven classpath resolution
failed; completions limited to project classes."

## Testing

**MavenClasspathResolver unit tests:**
- `resolveBuildOutputDirs()` with/without `target/classes` directory
- `resolve()` integration test against the project's own `pom.xml` — verify
  result contains known JARs (e.g., `antlr4-runtime`). Skip if `mvn` unavailable.

**MavenClasspathProvider unit tests:**
- Verify `classpathEntries()` returns constructed entries.

**DrlxLspServer integration test:**
- `initialize()` with `rootUri` pointing to workspace with `target/classes` populated
- Verify completions work after Phase 1 (project classes available)

**Existing tests:** No changes. `CurrentClassloaderProvider` unchanged.

## Scope Boundaries

**In scope:** Replace `target/dependency` scanning with `mvn dependency:build-classpath`.
Two-phase async init. `MavenClasspathProvider` as default.

**Out of scope:** Multi-module support (single workspace root only). Gradle/IDE
build output detection. File-change watching for rebuild. These can be added later.

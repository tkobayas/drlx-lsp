# Spec: File-change class index rebuild (#11 item #22)

Status: Approved

## Problem

When a user recompiles their project (e.g. `mvn compile`), `.class` files change under `target/classes/`. The LSP server currently does not detect this — completions, hover, diagnostics, and other features continue using the stale class index until the server is restarted. drools-lsp solves this with a debounced rebuild triggered by VS Code file-watcher notifications.

## Design

Three coordinated changes: a client-side file watcher, a server-side debounced handler, and an optimized rebuild path that avoids re-scanning JARs.

### 1. Client file watcher (`client/src/extension.ts`)

Add `synchronize.fileEvents` to the existing `LanguageClientOptions`:

```typescript
let clientOptions: LanguageClientOptions = {
    documentSelector: [{scheme: 'file', language: 'drlx'}],
    synchronize: {
        fileEvents: vscode.workspace.createFileSystemWatcher('**/target/classes/**/*.class')
    }
};
```

VS Code will send `workspace/didChangeWatchedFiles` notifications whenever `.class` files under `target/classes/` are created, changed, or deleted.

### 2. Debounced handler (`DrlxLspWorkspaceService`)

The workspace service gains:

- A constructor that receives `DrlxLspServer`.
- A `ScheduledExecutorService` (single daemon thread, named `"drlx-lsp-rebuild"`).
- A cancel-and-reschedule debounce pattern with `DEBOUNCE_DELAY_MS = 1000`.

`didChangeWatchedFiles()` cancels any pending rebuild future and schedules a new one 1 second out. Rapid successive file changes (typical during a Maven build) coalesce into a single rebuild after the last change.

A `shutdown()` method shuts down the scheduler, called from `DrlxLspServer.shutdown()`.

### 3. Optimized rebuild (`DrlxLspServer` + `WorkspaceSemanticModel`)

#### DrlxLspServer

- Constructor passes `this` to `DrlxLspWorkspaceService`.
- New `rebuildClassIndex()` method calls `model.rebuildOutputDirs()` then `textService.revalidateOpenDocuments()`.

#### WorkspaceSemanticModel

The current `rebuild()` scans all classpath entries (JARs + directories) every time. JARs don't change during development — only build output directories do. The optimization:

- New `volatile ClassIndex jarClassIndex` field — cached JAR-only index, built once.
- During `rebuild(classpathProvider, resolved=true)` (phase-2 init), compute and cache `jarClassIndex` from JAR entries only.
- New `rebuildOutputDirs()` method:
  1. Re-scan only `buildOutputDirs()` via `ClassIndex.build(dirs)`.
  2. Merge with cached `jarClassIndex` via `ClassIndex.merge(jarClassIndex, outputIndex)`.
  3. Rebuild classloader, typeSolver, and classMemberIndex from the full `classpathEntries` (unchanged).
  4. All fields are `volatile` — no additional synchronization needed (same pattern as current code).

This keeps rebuild fast: scanning a few directories is much cheaper than scanning dozens of dependency JARs.

## Files touched

| File | Change |
|------|--------|
| `client/src/extension.ts` | Add `synchronize.fileEvents` watcher |
| `DrlxLspWorkspaceService.java` | Constructor with server ref, scheduler, debounced `didChangeWatchedFiles` |
| `DrlxLspServer.java` | Pass `this` to workspace service; add `rebuildClassIndex()`; call `workspaceService.shutdown()` |
| `WorkspaceSemanticModel.java` | Add `jarClassIndex` cache; split JAR vs directory scanning; add `rebuildOutputDirs()` |

## Testing

Tests in `drlx-lsp-server` module, mirroring `DroolsLspServerTest` patterns:

1. **`rebuildClassIndexUpdatesDocumentService`** — configure build output dirs with `.class` files, call `rebuildClassIndex()`, verify the class index contains the new classes.
2. **`didChangeWatchedFilesTriggersRebuild`** — send a `didChangeWatchedFiles` notification, wait past the debounce window, verify the rebuild occurred.
3. **`rapidFileChangesCoalesceIntoOneRebuild`** — send many rapid notifications, verify the index is not yet updated before the debounce window, then verify it is correct after.

## Reference

drools-lsp implementation:
- `DroolsLspWorkspaceService` — debounce pattern
- `DroolsLspServer.rebuildClassIndex()` — JAR/directory split, merge, cache clear
- `DroolsLspServerTest` — three rebuild tests

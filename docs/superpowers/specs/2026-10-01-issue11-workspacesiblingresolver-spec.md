# WorkspaceSiblingResolver — Spec (#11 item #17)

## Goal

Provide a pluggable mechanism for resolving which `.drlx` files are
"siblings" of a given document — files in the same logical group whose
contents should be visible for cross-file features (references, rename,
future WorkspaceTypeIndex, etc.).

This issue delivers the resolver infrastructure only.  No consumer is
wired up here; each consumer (#19 WorkspaceTypeIndex, cross-file
references/rename, etc.) connects in its own issue.

## Approach

Direct port of drools-lsp's design with one change: file extension
`.drl` → `.drlx`.

## Components

### `WorkspaceSiblingResolver` (interface)

Package: `org.drools.drlx.completion.semantic`

```java
public interface WorkspaceSiblingResolver {
    List<Path> resolveSiblings(Path currentFile);
}
```

- Returns absolute paths of sibling files grouped with `currentFile`,
  excluding `currentFile` itself.
- Returns an empty list when no grouping applies or `currentFile` is null.

### `WorkspaceSiblingResolvers` (final utility class)

Package: `org.drools.drlx.completion.semantic`

- `private static volatile WorkspaceSiblingResolver active` — defaults to
  the same-directory resolver.
- `active()` — returns the current resolver.
- `setActive(WorkspaceSiblingResolver)` — installs a custom resolver;
  passing `null` restores the default.

**Default resolver (same-directory):**

Lists all `*.drlx` files in the same directory as `currentFile`,
excluding `currentFile` itself, sorted by `Path::compareTo` for stable
ordering.  Uses `Files.newDirectoryStream(dir, "*.drlx")`.  Returns an
empty list on I/O errors.

## Testing

`WorkspaceSiblingResolversTest` in `drlx-completion` test sources:

1. **defaultResolverReturnsSameDirectoryDrlxFiles** — `@TempDir` with
   `A.drlx`, `B.drlx`, `notes.txt`; verify `resolveSiblings(A)` returns
   only `B.drlx`.
2. **defaultResolverHandlesNullAndMissingPaths** — null and nonexistent
   path both return empty list.
3. **setActiveSwapsResolverAndNullRestoresDefault** — install custom
   resolver returning empty, verify; reset with null, verify default
   behavior restored.
4. `@AfterEach` resets via `setActive(null)`.

## Design decisions

- **Process-wide static registry** — matches drools-lsp.  Acceptable
  because the LSP server runs one instance per process.
- **`.drlx` extension** — DRLX files use `.drlx`, not `.drl`.
- **No consumers wired** — scope limited to the resolver itself;
  consumers connect in their own issues.

## Out of scope

- WorkspaceTypeIndex (#19)
- Cross-file references/rename
- Declared type parsing (#15 — skipped)
- Any consumer integration

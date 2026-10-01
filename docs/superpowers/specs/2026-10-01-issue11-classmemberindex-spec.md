# ClassMemberIndex — Design Spec

**Issue:** #11, item #14
**Date:** 2026-10-01

## Problem

drlx-lsp has no standalone API for looking up class members by FQCN. Member resolution is currently embedded inside `MemberCompletionProvider` and tightly coupled to the completion pipeline (requires a JavaParser `ResolvedType`). Features like hover, lint, and future infrastructure need direct FQCN → members lookup without going through the completion path.

## Solution

A reflection-based `ClassMemberIndex` class that lazily loads and caches class members from the project classpath. Follows the drools-lsp design: `URLClassLoader` + `Class.forName(fqcn, false, loader)` with `ConcurrentHashMap` caching.

## Scope

### In scope

- `ClassMemberIndex` class in `drlx-completion`
- Bean property extraction from `getX()`/`isX()` methods (MVEL-compatible)
- Public instance field extraction
- Enum constant extraction
- `memberNames()` for lint (distinguishes "no members" from "class not loadable")
- Unit tests against domain classes

### Out of scope

- `supertypesOf()` — #12 TypeHierarchy is skipped
- Replacing `MemberCompletionProvider` — completion continues using JavaParser
- Wiring ClassMemberIndex into hover/lint — deferred to those items' implementation

## Design

### Class: `ClassMemberIndex`

**Package:** `org.drools.drlx.completion`
**Module:** `drlx-completion`

### Record: `Field`

```java
public record Field(String name, String typeFqcn, List<String> args) {}
```

- `name`: member name (bean property name or field name)
- `typeFqcn`: fully qualified type name of the member
- `args`: method parameter types (empty list for properties/fields)

### Factory methods

| Method | Description |
|--------|-------------|
| `static ClassMemberIndex of(Set<Path> classpathEntries)` | Creates index with a `URLClassLoader` built from the given JARs/class directories. Uses platform class loader as parent to isolate server classes. |
| `static ClassMemberIndex empty()` | Returns an index that resolves nothing (for tests or when classpath is unavailable). |

### Public API

| Method | Returns | Description |
|--------|---------|-------------|
| `List<Field> membersOf(String fqcn)` | Cached member list | Bean properties + public instance fields + enum constants. Empty list if class is loadable but has no matching members. Empty list if class cannot be loaded. |
| `Set<String> memberNames(String fqcn)` | Member name set or `null` | Returns `null` if class cannot be loaded (distinguishes "no members" from "unverifiable"). Used by lint to avoid false positives on classes not on the classpath. |
| `void close()` | void | Clears cache and closes the owned `URLClassLoader`. |

### Loading strategy

1. `Class.forName(fqcn, false, loader)` — `false` prevents static initializer execution
2. Reflect public methods → extract bean properties (`getX()` → `x`, `isX()` → `x` for boolean)
3. Reflect public instance fields (non-static)
4. If enum, reflect enum constants
5. Cache result in `ConcurrentHashMap<String, List<Field>>`
6. Cache misses (class not found) separately to avoid repeated `ClassNotFoundException`

### Bean property rules

- `getXxx()` with no parameters and non-void return → property `xxx` (first char lowercased)
- `isXxx()` with no parameters and boolean/Boolean return → property `xxx`
- Methods from `Object` are excluded (`getClass()`, `hashCode()`, `equals()`, etc.)
- `get()` alone (no suffix) is excluded

### Lifecycle

- Created once in `DrlxLspServer.initialize()` alongside existing `ClassIndex`
- Passed to `DrlxLspDocumentService` for forwarding to helpers
- Closed in `DrlxLspServer.shutdown()`

### Thread safety

`ConcurrentHashMap` provides thread-safe lazy population. No external synchronization needed.

## Test plan

`ClassMemberIndexTest` in `drlx-completion`:

| Test case | Verifies |
|-----------|----------|
| `membersOf_beanProperties` | `Person.name`, `Person.age`, `Person.address` extracted from getters |
| `membersOf_publicFields` | Public instance fields are included |
| `membersOf_enumConstants` | Enum constants are listed |
| `membersOf_excludesObjectMethods` | `getClass()`, `hashCode()` etc. not in results |
| `membersOf_unknownClass` | Returns empty list, does not throw |
| `memberNames_knownClass` | Returns non-null set of member names |
| `memberNames_unknownClass` | Returns `null` |
| `membersOf_caching` | Second call returns same instance (cached) |
| `empty_resolvesNothing` | `ClassMemberIndex.empty()` returns empty for any FQCN |

# LhsBindingResolver — Spec (issue #11, item #21)

**Date:** 2026-10-01
**Issue:** [#11](https://github.com/tkobayas/drlx-lsp/issues/11) Item #21
**Status:** Proposed

---

## 1. Problem

Five call sites across four Helper classes share an identical three-step
boilerplate to look up the `VisibleSymbols` for a given token position:

```java
// repeated in DrlxHoverHelper, DrlxDefinitionHelper,
//             DrlxReferencesHelper (×1), DrlxRenameHelper (×2)
DrlxParser parser   = DrlxHoverHelper.createParser(text);
ParseTree  parseTree = parser.drlxStart();
CompletionContext ctx = model.createContext(parser, parseTree, tokenIndex);
VisibleSymbols symbols = ctx.buildVisibleSymbols();
```

There is no single place to unit-test this lookup in isolation, and each
Helper re-implements the same null-guard / token-find sequence around it.

**drools-lsp** solves this with a `LhsBindingResolver` utility class that
exposes two coarse-grained operations:

- `resolve(text, …)` — bindings across all rules (completion / hover)
- `resolveAt(text, offset, …)` — bindings scoped to the rule containing a
  given character offset (caret-aware hover, go-to-definition, rename)

drlx-lsp has a far richer equivalent already inside `CompletionContext`
(ANTLR parse-tree based, full `SemanticType`, caret-aware), but it is not
exposed as a reusable standalone entry point.

---

## 2. Goal

Introduce a thin **façade** class `LhsBindingResolver` that:

1. Encapsulates the parse → context → `buildVisibleSymbols()` pipeline.
2. Exposes two clean methods for the two distinct call patterns that exist
   across the Helpers.
3. Allows the four call sites to be reduced to a single delegating line
   each, without changing any observable behaviour.

---

## 3. Class Design

**Package:** `org.drools.drlx.completion.semantic`

```java
public final class LhsBindingResolver {

    private LhsBindingResolver() {}

    /**
     * Returns the bindings visible at {@code tokenIndex} within {@code text},
     * scoped to the enclosing rule only.
     *
     * <p>This is the caret-aware variant used by Hover, Definition,
     * References, and Rename — they already have a token index and need only
     * the symbols that are in scope at that position.</p>
     *
     * @param text        full source text of the .drlx file
     * @param tokenIndex  token-stream index of the cursor token
     * @param model       workspace semantic model (type solver, class loader)
     * @return            {@link VisibleSymbols} at the given position;
     *                    never {@code null}, may be {@link VisibleSymbols#empty()}
     */
    public static VisibleSymbols resolve(String text, int tokenIndex,
                                         WorkspaceSemanticModel model) { … }

    /**
     * Returns all bindings declared anywhere in {@code text}, across all
     * rules, without caret-position filtering.
     *
     * <p>Uses {@code Integer.MAX_VALUE} as the token index so that
     * {@code CompletionContext.buildVisibleSymbols()} treats every
     * declaration as "before the caret" and includes them all.</p>
     *
     * <p>Intended for consumers that need a whole-file view — e.g. inlay
     * hints, which walk every rule in the file.  (Note: inlay hints already
     * call this internally via their own parse pass; this method provides
     * a consistent entry point for any future whole-file consumer.)</p>
     *
     * @param text   full source text of the .drlx file
     * @param model  workspace semantic model
     * @return       {@link VisibleSymbols} containing all bindings; never
     *               {@code null}, may be {@link VisibleSymbols#empty()}
     */
    public static VisibleSymbols resolveAll(String text,
                                             WorkspaceSemanticModel model) { … }
}
```

### Internal implementation (both methods)

Both methods contain the parser construction inline (3 lines) rather than
calling `DrlxHoverHelper.createParser()`, because that method is
package-private in `org.drools.drlx.completion` and is not accessible from
`org.drools.drlx.completion.semantic`:

```
new ANTLRInputStream(text) → DrlxLexer → CommonTokenStream → DrlxParser
  → parser.drlxStart()          (parse tree)
  → model.createContext(parser, parseTree, tokenIndex)
  → ctx.buildVisibleSymbols()
```

`resolveAll` passes `Integer.MAX_VALUE` as `tokenIndex`.

Both return `VisibleSymbols.empty()` when `text` is `null` or empty.

---

## 4. Call-Site Changes

Three Helper classes are updated to delegate to `LhsBindingResolver`.
No other logic changes.

### 4.1 `DrlxHoverHelper` — NOT changed

`DrlxHoverHelper.hover()` constructs a `CompletionContext` (`ctx`) that is
used for more than just `buildVisibleSymbols()` — it is also passed to
`resolveDotHover(…, ctx, …)` and used for `ctx.imports()` in
`resolveImportHover`. Replacing `buildVisibleSymbols()` alone would require
keeping `ctx` anyway, yielding no simplification and adding a second parse
pass. `DrlxHoverHelper` is therefore out of scope for this item.

### 4.2 `DrlxDefinitionHelper`

**Before (lines 32–44):**
```java
DrlxParser parser = DrlxHoverHelper.createParser(text);
ParseTree parseTree = parser.drlxStart();
…
CompletionContext ctx = model.createContext(parser, parseTree, tokenIndex);
VisibleSymbols symbols = ctx.buildVisibleSymbols();
```

**After:**
```java
VisibleSymbols symbols = LhsBindingResolver.resolve(text, tokenIndex, model);
```

### 4.3 `DrlxReferencesHelper` — NOT changed

`DrlxReferencesHelper.references()` passes `ctx` to
`findImportTypeReferences(uri, word, tokens, ctx, includeDeclaration)`,
so the `CompletionContext` construction (= one parse pass) must be kept.
Calling `LhsBindingResolver.resolve()` on top of it would introduce a
second parse pass just to avoid one line (`ctx.buildVisibleSymbols()`),
which costs more than it saves. `DrlxReferencesHelper` is therefore out
of scope for this item.

### 4.4 `DrlxRenameHelper`

`prepare()` and `rename()` each contain the same three-step boilerplate.
Both are replaced by a `LhsBindingResolver.resolve()` call.

---

## 5. What does NOT change

- `CompletionContext` — no modifications; `LhsBindingResolver` calls its
  public `buildVisibleSymbols()` method.
- `DrlxHoverHelper` — `ctx` is needed for `imports()` and `resolveDotHover`;
  replacing only `buildVisibleSymbols()` would add a second parse pass.
- `DrlxReferencesHelper` — `ctx` is needed for `findImportTypeReferences`;
  same double-parse argument applies.
- `DrlxInlayHintHelper` — already uses `Integer.MAX_VALUE` as token index
  and builds its own `CompletionContext` per rule; leave untouched.
- `DrlxCompletionHelper` — has its own caret-index-based context; leave
  untouched.
- All existing tests — observable behaviour is identical; tests pass
  without modification.

---

## 6. Testing

### 6.1 Unit tests: `LhsBindingResolverTest`

Location: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/`

Test fixture: a minimal `.drlx` string with `import org.drools.drlx.domain.MyUnit`,
`unit MyUnit`, and a single rule containing a `var $p : /persons` OOPath binding
(uses the real `MyUnit` class already in the test classpath).

| Test | Assertion |
|------|-----------|
| `resolve_returnsBinding` | `resolve(text, tokenIndex, model).lookup("p")` is present |
| `resolve_nullText_returnsEmpty` | `resolve(null, 0, model)` returns `VisibleSymbols.empty()` |
| `resolve_emptyText_returnsEmpty` | `resolve("", 0, model)` returns `VisibleSymbols.empty()` |
| `resolveAll_returnsAllBindings` | `resolveAll(text, model).lookup("p")` is present without needing a token index |
| `resolveAll_nullText_returnsEmpty` | returns `VisibleSymbols.empty()` |

### 6.2 Integration: existing tests continue to pass

No new integration tests are required. The call-site changes are purely
mechanical; the existing test suites for `DrlxHoverHelper`,
`DrlxDefinitionHelper`, `DrlxReferencesHelper`, and `DrlxRenameHelper`
verify correct end-to-end behaviour after the refactor.

Run: `mvn -pl drlx-completion test -q`

---

## 7. Design Decisions

### Why a façade and not a full extraction?

`CompletionContext` contains caret-position-sensitive logic that is
necessary for completion but would complicate a binding-only resolver.
Extracting it would be a larger refactor with more risk and no additional
user-visible value. The façade delegates to the proven implementation and
adds a thin, testable entry point.

### Why not use `DrlxHoverHelper.createParser` directly at call sites?

`DrlxHoverHelper` is not in the `semantic` package and exposes this method
only because it was the first Helper to need it. Centralising parsing in
`LhsBindingResolver` removes the semantic package's dependency on a
presentation-layer helper and gives the correct layering: `semantic.*`
classes do not depend on `DrlxHoverHelper`.

### Why keep `DrlxInlayHintHelper` untouched?

The inlay-hint helper processes every rule in the file in a single pass,
constructing one `CompletionContext` per rule with a fixed token index.
Wrapping this in `resolveAll` would either require multiple parse passes
or a different API shape. The scope of this item is the four caret-aware
Helpers; inlay hints are a separate concern.

---

## 8. Out of Scope

- Changes to `CompletionContext` internals
- Cross-file binding resolution (future item)
- Inlay hint refactoring
- Any new LSP protocol feature

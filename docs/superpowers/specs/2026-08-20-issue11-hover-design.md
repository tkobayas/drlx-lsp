# Design: Hover — Type Info on Hover (Issue #11, Item 2)

## Goal

Show type information when the user hovers over identifiers in DRLX files. This is the second non-completion LSP feature for drlx-lsp (after diagnostics).

```
rule R1 {
    var p : /persons,
    do { p.address.city }
}
         ^ hover on "p"       → p : Person  (fields: name, age, address, ...)
                   ^ "address" → address : Address  (field of Person)
                        ^ "city"    → city : String  (field of Address)
```

## Current state

`DrlxLspDocumentService` implements `TextDocumentService` but does not override `hover()` — the default returns null (no hover). The server does not advertise hover capability.

drlx-lsp already has:
- `CompletionContext.buildVisibleSymbols()` — resolves bindings (OOPath, constraint, accumulate, RHS locals, rule parameters) to `SemanticType`
- `SentinelExpressionTypeResolver` — resolves arbitrary expressions to types via MVEL transpile
- `MemberCompletionProvider` — enumerates fields/methods of a `SemanticType` (used by completion)
- `CompletionContext.resolvePropertyType()` — resolves a property name to its `SemanticType` given an owner type

## Reference

drools-lsp's `DRLHoverHelper` handles: declared types (with doc comments), bound variables (`$p`), constraint fields, and classpath types. It depends on `ClassIndex`, `ClassMemberIndex`, `LhsBindingResolver`, `DRLWorkspaceTypeIndex`, `DRLDeclaredTypeParser`, and `DRLDocFormatter` — none of which exist in drlx-lsp.

DRLX has no `declare` blocks — types are always Java classes. Bindings use OOPath (`var p : /persons`) not DRL patterns (`$p : Person(...)`). The existing `CompletionContext` + `SentinelExpressionTypeResolver` already covers all binding resolution and expression type resolution needed for hover.

## Approach

New `DrlxHoverHelper` class in `drlx-completion` that reuses `CompletionContext` and `SentinelExpressionTypeResolver` to resolve hover targets. Three hover scenarios in priority order:

1. **Visible symbol** — the hovered word is a binding name in `VisibleSymbols` → show type name + member list
2. **Dot-expression member** — the hovered word follows a `.` → resolve the prefix expression's type via sentinel, then look up the hovered member in that type
3. **Import type name** — the hovered word matches a simple name from an import → show FQCN + member list

The helper needs a `CompletionContext` at the hover position. `CompletionContext` currently requires a `caretTokenIndex` (for scoping visible symbols to before-caret). For hover, the "caret" is the hovered position — same contract. The existing `WorkspaceSemanticModel.createContext()` factory works unchanged.

### Rendering

Hover content is Markdown (`MarkupKind.MARKDOWN`). Format:

**Visible symbol:**
```
**p** : `Person`

- name : String
- age : int
- address : Address
```

**Dot-access member:**
```
**address** : `Address`

Field of `Person`
```

**Import type:**
```
**Person** — `org.drools.drlx.domain.Person`

- name : String
- age : int
- address : Address
```

## Changes

### 1. DrlxHoverHelper — new class in drlx-completion

`drlx-completion/src/main/java/org/drools/drlx/completion/DrlxHoverHelper.java`

```java
public final class DrlxHoverHelper {

    public static Hover hover(String text, Position position, WorkspaceSemanticModel model) { ... }
}
```

**`hover(String text, Position position, WorkspaceSemanticModel model)`:**
1. Return null for null/empty text or position
2. Parse the text with `DrlxParser`, compute the token index at position (reuse the same logic as `DrlxCompletionHelper.computeTokenIndex`)
3. Find the token at the position — extract the hovered word. If not a valid identifier, return null.
4. Build `CompletionContext` at the hover position via `model.createContext(parser, parseTree, tokenIndex)`
5. Check if the word is preceded by `.` — scan backward in the token stream (skip whitespace/hidden tokens):
   - **Yes (dot-expression):** extract the prefix expression text from the expression boundary to the dot token. Resolve prefix type via `SentinelExpressionTypeResolver.resolveExpressionType(prefixText, symbols, imports, classLoader)`. Look up the hovered word as a property/method in the resolved type. Render as member hover.
   - **No (bare word):** Look up in `VisibleSymbols`. If found, render the symbol's type + members. If not found, check if it matches an imported class simple name — if so, resolve FQCN and render as type hover.
6. Return null if nothing matched.

**Token extraction / word-at-position:** Use the parsed token stream — find the token whose line/column range contains the position. This is more reliable than text substring extraction and naturally handles keywords vs identifiers.

**Type rendering helpers (private):**
- `renderSymbol(name, SemanticType, model)` — "**name** : \`TypeName\`" + member list
- `renderMember(name, SemanticType, ownerTypeName)` — "**name** : \`TypeName\`\n\nField of \`Owner\`"
- `renderImportType(simpleName, fqcn, SemanticType, model)` — "**name** — \`fqcn\`" + member list
- `memberList(SemanticType)` — enumerate public fields + getter-derived properties via JavaParser's resolved type API (same logic as `MemberCompletionProvider` but outputs Markdown lines instead of `CompletionItem`)

### 2. DrlxLspDocumentService — override hover()

```java
@Override
public CompletableFuture<Hover> hover(HoverParams params) {
    return CompletableFuture.supplyAsync(() -> {
        String uri = params.getTextDocument().getUri();
        String text = sourcesMap.get(uri);
        if (text == null) return null;
        return DrlxHoverHelper.hover(text, params.getPosition(), model());
    });
}
```

Expose `model` from `DrlxLspDocumentService` — currently it's stored as a field only in the server. Either pass the model to the document service constructor (already done for `DrlxCompletionHelper`) or add a `model()` accessor. Since the constructor already receives `WorkspaceSemanticModel`, just store and expose it.

### 3. DrlxLspServer — advertise hover capability

In `initialize()`, add:
```java
initializeResult.getCapabilities().setHoverProvider(true);
```

### 4. Tests — DrlxHoverHelperTest

`drlx-completion/src/test/java/org/drools/drlx/completion/DrlxHoverHelperTest.java`

Uses the same domain classes as completion tests: `MyUnit`, `Person`, `Address`, `Country`.

| Test | Hover target | Assertion |
|------|-------------|-----------|
| `hoverOnOopathBinding` | `p` in `var p : /persons` | Contains "Person", lists `name`, `age`, `address` |
| `hoverOnConstraintBinding` | `addr` in `[addr : address]` | Contains "Address", lists `city`, `country` |
| `hoverOnRhsLocal` | `x` in `Address x = p.getAddress();` | Contains "Address" |
| `hoverOnRuleParameter` | `p` in `rule R1(Person p)` | Contains "Person" |
| `hoverOnDotAccessMember` | `address` in `p.address` | Contains "Address", "Field of" + "Person" |
| `hoverOnChainedDotAccess` | `city` in `p.address.city` | Contains "String", "Field of" + "Address" |
| `hoverOnImportTypeName` | `Person` in `import ...Person` | Contains FQCN `org.drools.drlx.domain.Person`, lists members |
| `hoverOnUnknownSymbol` | random word | Returns null |
| `hoverOnKeyword` | `rule` keyword | Returns null |
| `nullInputs` | null text / null position | Returns null |

### 5. Server test — DrlxLspDocumentServiceTest

Add a test that constructs a `DrlxLspDocumentService`, calls `hover()`, and verifies non-null result for a valid hover position. Follows the pattern of existing completion tests in the server module.

## Files touched (source repo)

New:
- `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxHoverHelper.java`
- `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxHoverHelperTest.java`

Edit:
- `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java` — override `hover()`, store model reference
- `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java` — `setHoverProvider(true)`

## Risk

**Low.** The core type resolution is already battle-tested by 144 completion tests. The main new work is:
- Token-at-position extraction (straightforward ANTLR token stream traversal)
- Dot-expression prefix detection (reuses `TokenWalker.findExpressionBoundary`, already used by `SentinelExpressionTypeResolver`)
- Markdown rendering (string formatting, no logic risk)

The `CompletionContext` is designed for completion (walks parse tree to caret position). For hover, the "caret" is the hovered token's position, which happens to be the right scoping — symbols visible at position X are exactly what should be in scope for hover at X. No architectural mismatch.

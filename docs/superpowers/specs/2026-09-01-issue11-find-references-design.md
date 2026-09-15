# Find References — Design Spec (Issue #11, item 4)

## Goal

Add `textDocument/references` support to drlx-lsp. When the user invokes Find References on an identifier, the editor lists all locations where that symbol is used.

## Scope

Single-file references only — two categories:

1. **Bindings** — OOPath bindings (`var p : /persons`), constraint bindings (`[$addr : address]`), rule parameters (`rule R1(Person p)`), RHS local variables (`Address x = ...`). All occurrences within the enclosing rule.
2. **Import types** — Type names used in patterns, parameters, constraints, or expressions. All occurrences across the entire file.

Out of scope: cross-file references (requires `WorkspaceTypeIndex` and sibling file resolution — Issue #11 items 17/19).

## Resolution Logic

Entry point: `DrlxReferencesHelper.references(String uri, String text, Position position, WorkspaceSemanticModel model, boolean includeDeclaration)` → `List<Location>`.

Algorithm:

1. Parse text with ANTLR, build `CommonTokenStream`, call `findTokenAt(tokens, position)`.
2. If the token is not an `IDENTIFIER`, return empty.
3. Build `CompletionContext` and `VisibleSymbols` via `model.createContext(...)`.
4. **Binding path**: Call `symbols.lookupEntry(word)`. If found, this is a rule-scoped binding. Find the enclosing `RuleDeclarationContext` and scan all `IDENTIFIER` tokens within that rule's token span for exact text matches. Each match becomes a `Location`. If `!includeDeclaration` and the entry has a non-null `TokenRange`, filter out the occurrence at the declaration position.
5. **Import type path**: If not found in bindings, check whether `word` matches a simple name in `ctx.imports()`. If so, scan all `IDENTIFIER` tokens in the entire file for exact text matches. If `!includeDeclaration`, filter out the occurrence on the import line itself.
6. If neither path matches, return empty.

### Token scanning approach

DRLX's ANTLR grammar fully parses the RHS (`do { ... }` blocks) — unlike classic DRL where `then...end` is opaque `RHS_CHUNK` tokens. This means all identifiers in the file (LHS patterns, constraints, consequence code) are IDENTIFIER tokens on the default channel. A simple linear scan of `tokens.getTokens()` with an exact text match is sufficient.

For bindings, restrict the scan to tokens between `enclosingRule.getStart().getTokenIndex()` and `enclosingRule.getStop().getTokenIndex()`. For import types, scan all tokens in the file.

### Finding the enclosing rule

Reuse the same pattern as `CompletionContext.findEnclosingRule()`: walk `cu.ruleDeclaration()` to find the rule whose start/stop token span contains the cursor token index. This method is currently private; we need to find the rule independently in `DrlxReferencesHelper` using the same approach. Since `DrlxReferencesHelper` already has the parse tree and token index, it can walk `DrlxCompilationUnitContext.ruleDeclaration()` directly.

### Difference from Go to Definition

Go to Definition operates on `VisibleSymbols` which is caret-position-aware (symbols after the caret are invisible). Find References needs to find _all_ uses regardless of position, so we scan tokens directly rather than relying on `VisibleSymbols` for the occurrence list. We still use `VisibleSymbols` to _classify_ the symbol (binding vs. type) and to locate the declaration site (for `includeDeclaration` filtering).

## LSP Wiring

- `DrlxLspServer.initialize()`: add `setReferencesProvider(true)`.
- `DrlxLspDocumentService`: override `references(ReferenceParams)`. Retrieve text from `sourcesMap`, extract `includeDeclaration` from `params.getContext()`, delegate to `DrlxReferencesHelper.references()`.

## File Layout

All in existing modules, following the go-to-definition pattern:

| File | Module | Change |
|------|--------|--------|
| `DrlxReferencesHelper.java` | drlx-completion | New — reference scanning logic |
| `DrlxReferencesHelperTest.java` | drlx-completion | New — unit tests |
| `DrlxLspDocumentService.java` | drlx-lsp-server | Modified — override `references()` |
| `DrlxLspServer.java` | drlx-lsp-server | Modified — advertise `referencesProvider` |
| `DrlxLspDocumentServiceTest.java` | drlx-lsp-server | Modified — add references integration test |

## Test Plan

Unit tests (`DrlxReferencesHelperTest`):

| Test | Description |
|------|-------------|
| `oopathBinding_findsAllUsesInRule` | `var p : /persons` — cursor on `p` in consequence → finds binding declaration + usage in `do{}` |
| `oopathBinding_excludeDeclaration` | Same but with `includeDeclaration=false` → declaration site omitted |
| `constraintBinding_findsAllUsesInRule` | `[$addr : address]` — finds `$addr` in binding and in consequence |
| `ruleParameter_findsAllUsesInRule` | `rule R1(Person p)` — finds `p` in parameter list and consequence |
| `rhsLocalVariable_findsAllUsesInRule` | `Address x = expr; x.foo` — finds `x` at declaration and usage |
| `bindingScopedToEnclosingRule` | Same binding name in two rules → only returns occurrences from the rule containing the cursor |
| `importType_findsAllUsesInFile` | `Person` in import, pattern parameter, and consequence → all found |
| `importType_excludeDeclaration` | Same but `includeDeclaration=false` → import line occurrence omitted |
| `keywordReturnsEmpty` | Cursor on `rule` keyword → empty |
| `unknownSymbolReturnsEmpty` | Cursor on unrecognized identifier → empty |
| `nullTextReturnsEmpty` | Null/empty text → empty |

Server test (`DrlxLspDocumentServiceTest`):

| Test | Description |
|------|-------------|
| `references_findsBindingUses` | End-to-end through LSP service layer |

## Design Decisions

1. **Token scan, not parse-tree walk**: A flat scan of IDENTIFIER tokens is simpler and catches uses in all grammar positions (patterns, constraints, expressions, consequence) without enumerating each position type. This works because DRLX fully parses all code blocks.
2. **Bindings are rule-scoped**: A binding declared in one rule should not match identifiers in another rule, even if the name is the same. This matches the language's scoping semantics.
3. **Import types are file-scoped**: A type name imported at the top is visible everywhere in the file. All occurrences across all rules are relevant.
4. **`includeDeclaration` respected**: The LSP protocol's `ReferenceContext.includeDeclaration` flag controls whether the declaration site appears in the results. For bindings, we use the `TokenRange` from `SymbolEntry`; for imports, we check if the token is on the import line.
5. **Reuses existing infrastructure**: `DrlxHoverHelper.findTokenAt()` and `createParser()`, `CompletionContext`/`VisibleSymbols` for symbol classification, `TokenRange` for position comparison. No new data structures needed.

# Go to Definition — Design Spec (Issue #11, item 3)

## Goal

Add `textDocument/definition` support to drlx-lsp. When the user Ctrl-clicks (or invokes Go to Definition) on an identifier, the editor navigates to where that symbol is defined.

## Scope

In-file definitions only — two tiers:

1. **Bindings** — OOPath bindings (`var p : /persons`), constraint bindings (`[addr : address]`), rule parameters (`rule R1(Person p)`), RHS local variables (`Address x = ...`). Jump from a usage site to the declaration site within the same file.
2. **Import types** — Type names used in patterns, constraints, or expressions. Jump to the matching `import` statement.

Out of scope: Java source navigation (deferred to Issue #11 item #18, which will add `JavaSourceLocator` infrastructure).

## Resolution Logic

Entry point: `DrlxDefinitionHelper.definition(String text, Position position, WorkspaceSemanticModel model)` → `List<Location>`.

Algorithm:

1. Parse text, find the ANTLR token at `position`.
2. If the token is not an `IDENTIFIER`, return empty.
3. Build `CompletionContext` and `VisibleSymbols`.
4. **Tier 1 — Binding lookup**: Call `symbols.lookupEntry(word)`. If found and the cursor is not at the definition site itself, return a `Location` pointing to the binding's declaration position.
5. **Tier 2 — Import type lookup**: Scan the import list for a matching simple name. If found and the cursor is not on the import line itself, return a `Location` pointing to the import statement's type name token.
6. If neither matches, return empty list.

## VisibleSymbols Extension

Current state: `VisibleSymbols` maps `String → SemanticType`. It has no position information.

Change: introduce `SymbolEntry` record holding `SemanticType type` and `TokenRange range`. `TokenRange` is a record with `startLine`, `startCol`, `endLine`, `endCol` (all 0-based, matching LSP convention).

- Internal map becomes `Map<String, SymbolEntry>`.
- Existing `lookup(name)` returns `Optional<SemanticType>` (backward compatible).
- New `lookupEntry(name)` returns `Optional<SymbolEntry>` with position.
- `Builder.add(name, type)` still works (stores null range for callers that don't need positions).
- New `Builder.add(name, type, tokenRange)` overload for definition-aware extraction.

The six `extract*` methods in `CompletionContext` (`extractRuleParameters`, `extractOopathBindings`, `extractConstraintBindings`, `extractAccInitVars`, `extractLocalVariables`, `extractOopathConstraintProperties`) capture the ANTLR token's line/charPosition (converting from ANTLR 1-based lines to 0-based) when adding to the builder.

## LSP Wiring

- `DrlxLspServer.initialize()`: add `setDefinitionProvider(true)`.
- `DrlxLspDocumentService.definition(DefinitionParams)`: retrieve text from `sourcesMap`, delegate to `DrlxDefinitionHelper.definition()`, wrap in `Either.forLeft(...)`.

## File Layout

All in existing modules, following the hover pattern:

| File | Module | Change |
|------|--------|--------|
| `DrlxDefinitionHelper.java` | drlx-completion | New — resolution logic |
| `SymbolEntry.java` | drlx-completion | New — record wrapping SemanticType + TokenRange |
| `TokenRange.java` | drlx-completion | New — position record |
| `VisibleSymbols.java` | drlx-completion | Modified — use SymbolEntry internally |
| `CompletionContext.java` | drlx-completion | Modified — capture token positions in extract* methods |
| `DrlxDefinitionHelperTest.java` | drlx-completion | New — unit tests |
| `DrlxLspDocumentService.java` | drlx-lsp-server | Modified — override definition() |
| `DrlxLspServer.java` | drlx-lsp-server | Modified — advertise capability |
| `DrlxLspDocumentServiceTest.java` | drlx-lsp-server | Modified — add definition test |

## Test Plan

Unit tests (`DrlxDefinitionHelperTest`):

| Test | Description |
|------|-------------|
| `oopathBinding` | `var p : /persons` — cursor on `p` in consequence → jumps to binding declaration |
| `constraintBinding` | `[addr : address]` — cursor on `addr` in consequence → jumps to constraint binding |
| `ruleParameter` | `rule R1(Person p)` — cursor on `p` in body → jumps to parameter |
| `rhsLocalVariable` | `Address x = ...` — cursor on `x` later in RHS → jumps to declaration |
| `importType` | Cursor on `Person` in a pattern → jumps to import line |
| `cursorOnDefinitionSite` | Cursor already on binding declaration → empty |
| `cursorOnImportLine` | Cursor on type name in import statement → empty |
| `keywordReturnsEmpty` | Cursor on `rule` keyword → empty |
| `unknownSymbolReturnsEmpty` | Cursor on unrecognized identifier → empty |
| `nullTextReturnsEmpty` | Null text → empty |

Server test (`DrlxLspDocumentServiceTest`):

| Test | Description |
|------|-------------|
| `definitionJumpsToBinding` | End-to-end through LSP service layer |

## Design Decisions

1. **In-file only**: Java source navigation requires build-output-directory awareness and Maven convention mapping — separate infrastructure (item #18). The in-file scope is self-contained and immediately useful.
2. **Extend VisibleSymbols rather than separate walk**: The extract methods already walk the exact parse tree nodes containing binding tokens. Adding position capture there avoids duplicating the "which bindings exist" logic and benefits future features (Find References, Rename).
3. **Self-reference guard**: Jumping to yourself when already at the definition site is confusing. We check whether the cursor position matches the definition position and return empty if so.
4. **Import line guard**: Same principle — if you're on the import line, there's no further "definition" to navigate to within the file.

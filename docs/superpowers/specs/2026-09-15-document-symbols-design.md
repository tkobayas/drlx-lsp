# Design: Document Symbols (Issue #11, Item #5)

**Date:** 2026-09-15  
**Status:** Approved  
**Scope:** `drlx-completion` module + `drlx-lsp-server` module

---

## Summary

Implement `textDocument/documentSymbol` for DRLX files. The outline view surfaces
`unit` and `rule` declarations from `drlxCompilationUnit`-style files as a flat
list of `DocumentSymbol` objects. Java-style (`compilationUnit`) files return an
empty list and are silently ignored.

---

## Grammar scope

DRLX has two grammar branches under `drlxStart`:

| Branch | Trigger | In scope? |
|--------|---------|-----------|
| `drlxCompilationUnit` | starts with `unit` / `import` / `rule` | **Yes** |
| `compilationUnit` | starts with `class` / `package` (Java style) | No — returns empty list |

---

## Symbol mapping

| DRLX construct | Parse tree context | `SymbolKind` | `name` source | `detail` |
|----------------|--------------------|--------------|---------------|----------|
| `unit MyUnit;` | `UnitDeclarationContext` | `Namespace` | `qualifiedName().getText()` | — |
| `rule R1 { }` | `RuleDeclarationContext` | `Method` | `identifier().getText()` | — |

`import` declarations are skipped — they add no value to the outline.

---

## Range and selectionRange

Following the same convention as `DrlxReferencesHelper` and the drools-lsp
reference implementation:

- **`range`**: full span of the construct — `context.getStart()` to `context.getStop()`.
- **`selectionRange`**: name token only — `qualifiedName()` or `identifier()` context.
- LSP requires `selectionRange ⊆ range`. If this invariant fails (e.g. partial
  parse), fall back to using `range` for both.

---

## Example

Input:
```
unit MyUnit;
import org.example.Person;
rule R1 {
    var p : /persons,
    do { System.out.println(p); }
}
rule R2 {
    var p : /persons,
    do { }
}
```

Output `List<DocumentSymbol>`:
```
[
  { name="MyUnit", kind=Namespace, range=line0,    selectionRange=line0    },
  { name="R1",     kind=Method,    range=line2..6, selectionRange=line2    },
  { name="R2",     kind=Method,    range=line7..9, selectionRange=line7    }
]
```

---

## New class: `DrlxDocumentSymbolHelper`

**Location:** `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxDocumentSymbolHelper.java`

**API:**
```java
public final class DrlxDocumentSymbolHelper {
    private DrlxDocumentSymbolHelper() {}

    /** Returns the outline symbols for {@code text}, or an empty list. */
    public static List<DocumentSymbol> symbols(String text) { ... }
}
```

**Algorithm:**
1. Guard: `text` null or blank → return empty list.
2. `DrlxHoverHelper.createParser(text)` → parse `drlxStart()`.
3. `drlxStart.drlxCompilationUnit()` null → return empty list (Java-style file).
4. From `DrlxCompilationUnitContext`:
   - `unitDeclaration()` non-null → build `DocumentSymbol(Namespace, qualifiedName)`.
   - For each `ruleDeclaration()` → build `DocumentSymbol(Method, identifier)`.
5. Swallow exceptions per-symbol; a partial file still returns well-formed symbols.

**Range helper** (private static):
```java
private static Range rangeOf(ParserRuleContext ctx)       // start..stop tokens
private static DocumentSymbol symbol(String name, SymbolKind kind,
                                     ParserRuleContext rangeCtx,
                                     ParserRuleContext selectionCtx)
```

---

## Changes to existing classes

### `DrlxLspServer`

Add one line in `initialize()`:
```java
initializeResult.getCapabilities().setDocumentSymbolProvider(true);
```

### `DrlxLspDocumentService`

Override `documentSymbol()`:
```java
@Override
public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
        DocumentSymbolParams params) {
    return CompletableFuture.supplyAsync(() -> {
        String text = sourcesMap.get(params.getTextDocument().getUri());
        if (text == null) return Collections.emptyList();
        return DrlxDocumentSymbolHelper.symbols(text).stream()
                .map(Either::<SymbolInformation, DocumentSymbol>forRight)
                .collect(Collectors.toList());
    });
}
```

---

## Test plan

### Unit tests — `DrlxDocumentSymbolHelperTest`

| Test | Verifies |
|------|----------|
| `nullOrEmptyTextYieldsNoSymbols` | `null` and `""` → empty list |
| `unitAndRulesOutlined` | unit + multiple rules → correct order, name, kind |
| `rulesOnlyWhenNoUnit` | no `unit` declaration → only rules returned |
| `unitOnlyWhenNoRules` | unit with no rules → only unit symbol returned |
| `selectionRangeWithinRange` | `selectionRange ⊆ range` for all symbols |
| `javaStyleFileYieldsNoSymbols` | `class Foo { rule R1{} }` → empty list |
| `partialFileDoesNotThrow` | incomplete syntax does not throw |

### Integration test — `DrlxLspDocumentServiceTest`

| Test | Verifies |
|------|----------|
| `documentSymbol_returnsSymbolsForDrlxFile` | after `didOpen`, `documentSymbol` returns unit + rules |

---

## Out of scope

- `compilationUnit` (Java-style) files — returns empty list.
- Nested `children` — symbols are a flat list (no nesting).
- `import` declarations — not included in the outline.

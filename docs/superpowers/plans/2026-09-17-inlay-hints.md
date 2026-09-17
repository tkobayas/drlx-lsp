# Inlay Hints Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement LSP Inlay Hints (`textDocument/inlayHint`) for implicitly typed bindings (OOPath pattern `var`, constraint bindings, accumulate results, consequence `var` locals) in DRLX files.

**Architecture:** Create `DrlxInlayHintHelper` in `drlx-completion` which walks the AST via `DrlxParser.drlxStart()`, leverages `CompletionContext` semantic type inference to determine variable types, and generates `InlayHint` objects for implicitly typed bindings. Wire this into `DrlxLspDocumentService.inlayHint()` and enable `inlayHintProvider` in `DrlxLspServer`.

**Tech Stack:** Java 17+, ANTLR4, Eclipse LSP4J 0.24.0, AssertJ / JUnit 5.

## Global Constraints

- Must strictly conform to `docs/superpowers/specs/2026-09-17-inlay-hints-design.md`.
- Inlay hints return `: SimpleTypeName` at position immediately after the variable identifier token.
- No hints for variables with explicit types (e.g., `Person $p = /persons`).
- Range filtering: if `Range` is supplied, only return hints within that range.

---

### Task 1: Create `DrlxInlayHintHelper` and Comprehensive Unit Tests in `drlx-completion`

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxInlayHintHelper.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxInlayHintHelperTest.java`

**Interfaces:**
- Produces: `DrlxInlayHintHelper.inlayHints(String text, Range range, WorkspaceSemanticModel model) -> List<InlayHint>`

- [ ] **Step 1: Write failing unit test covering all target binding kinds in `DrlxInlayHintHelperTest`**

```java
package org.drools.drlx.completion;

import java.util.List;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class DrlxInlayHintHelperTest {

    @Test
    void testOopathVarAndConstraintHints() {
        String drlx = """
                package org.drools.test;
                import org.drools.drlx.completion.TestPerson;
                unit TestUnit;
                
                rule "TestRule"
                when
                    var $p = /persons[ $a: age, $n: name ]
                then
                end
                """;
        WorkspaceSemanticModel model = new WorkspaceSemanticModel();
        List<InlayHint> hints = DrlxInlayHintHelper.inlayHints(drlx, null, model);
        
        assertThat(hints)
                .extracting(
                        h -> h.getPosition().getLine(),
                        h -> h.getPosition().getCharacter(),
                        h -> h.getLabel().getLeft(),
                        InlayHint::getKind)
                .containsExactlyInAnyOrder(
                        tuple(6, 11, ": TestPerson", InlayHintKind.Type),
                        tuple(6, 25, ": int", InlayHintKind.Type),
                        tuple(6, 34, ": String", InlayHintKind.Type)
                );
    }

    @Test
    void testAccumulateVarHint() {
        String drlx = """
                package org.drools.test;
                import org.drools.drlx.completion.TestPerson;
                unit TestUnit;
                
                rule "AccumulateRule"
                when
                    var $cnt = count(/persons)
                then
                end
                """;
        WorkspaceSemanticModel model = new WorkspaceSemanticModel();
        List<InlayHint> hints = DrlxInlayHintHelper.inlayHints(drlx, null, model);
        
        assertThat(hints)
                .extracting(
                        h -> h.getPosition().getLine(),
                        h -> h.getPosition().getCharacter(),
                        h -> h.getLabel().getLeft())
                .containsExactly(tuple(6, 12, ": Long"));
    }

    @Test
    void testConsequenceVarHint() {
        String drlx = """
                package org.drools.test;
                unit TestUnit;
                
                rule "RhsRule"
                when
                then
                    var msg = "hello";
                end
                """;
        WorkspaceSemanticModel model = new WorkspaceSemanticModel();
        List<InlayHint> hints = DrlxInlayHintHelper.inlayHints(drlx, null, model);
        
        assertThat(hints)
                .extracting(
                        h -> h.getPosition().getLine(),
                        h -> h.getPosition().getCharacter(),
                        h -> h.getLabel().getLeft())
                .containsExactly(tuple(6, 11, ": String"));
    }

    @Test
    void testExplicitTypeHasNoHint() {
        String drlx = """
                package org.drools.test;
                import org.drools.drlx.completion.TestPerson;
                unit TestUnit;
                
                rule "ExplicitRule"
                when
                    TestPerson $p = /persons
                then
                    String s = "world";
                end
                """;
        WorkspaceSemanticModel model = new WorkspaceSemanticModel();
        List<InlayHint> hints = DrlxInlayHintHelper.inlayHints(drlx, null, model);
        assertThat(hints).isEmpty();
    }

    @Test
    void testRangeFiltering() {
        String drlx = """
                package org.drools.test;
                import org.drools.drlx.completion.TestPerson;
                unit TestUnit;
                
                rule "R1"
                when
                    var $p = /persons
                then
                end
                
                rule "R2"
                when
                    var $q = /persons
                then
                end
                """;
        WorkspaceSemanticModel model = new WorkspaceSemanticModel();
        // Request range only covering R1 (lines 5 to 9)
        Range range = new Range(new Position(5, 0), new Position(9, 0));
        List<InlayHint> hints = DrlxInlayHintHelper.inlayHints(drlx, range, model);
        
        assertThat(hints)
                .extracting(h -> h.getPosition().getLine())
                .containsExactly(6);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl drlx-completion test -Dtest=DrlxInlayHintHelperTest`
Expected: Compilation failure (`DrlxInlayHintHelper` symbol not found).

- [ ] **Step 3: Implement `DrlxInlayHintHelper`**

Implement AST traversal collecting type inlay hints from:
1. `BoundOopathContext`: if `bound.identifier(0).getText().equals("var")`, infer root type via `CompletionContext` and add hint for `bound.identifier(1)`.
2. `DrlxExpressionContext` constraint bindings: for `drlxExpr.bind != null`, infer property type from owner type and add hint for `drlxExpr.bind`.
3. `AccumulateItemContext`: if `accItem.VAR() != null`, infer type from accumulate function name (e.g. `count` -> `Long`, `sum` -> `Number`) and add hint for `accItem.identifier()`.
4. Consequence `LocalVariableDeclarationContext`: if `localVar.VAR() != null`, infer initializer type via `CompletionContext.inferVarInitializerType()` and add hint for `localVar.identifier()`.
5. Helper method `toSimpleName(String typeName)` or `SemanticType.simpleName()` to strip package qualifiers.
6. Check range bounds: if `range != null`, filter hint positions with `isWithinRange(hint.getPosition(), range)`.

- [ ] **Step 4: Run unit tests to verify they pass**

Run: `mvn -pl drlx-completion test -Dtest=DrlxInlayHintHelperTest`
Expected: All tests PASS.

- [ ] **Step 5: Commit changes**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxInlayHintHelper.java drlx-completion/src/test/java/org/drools/drlx/completion/DrlxInlayHintHelperTest.java
git commit -m "feat: add DrlxInlayHintHelper for LSP inlay hints"
```

---

### Task 2: Wire Inlay Hints into `drlx-lsp-server` and Add Integration Test

**Files:**
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java`
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java`
- Modify: `drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspDocumentServiceTest.java`

**Interfaces:**
- Consumes: `DrlxInlayHintHelper.inlayHints(String text, Range range, WorkspaceSemanticModel model)`
- Produces: `DrlxLspDocumentService.inlayHint(InlayHintParams params) -> CompletableFuture<List<InlayHint>>`

- [ ] **Step 1: Write integration test in `DrlxLspDocumentServiceTest`**

Add `inlayHint_returnsHintsForDrlxFile()` in `DrlxLspDocumentServiceTest`:

```java
@Test
void inlayHint_returnsHintsForDrlxFile() throws Exception {
    String content =
            "import org.example.Person;\n"     // 0
            + "unit MyUnit;\n"                  // 1
            + "rule R1 {\n"                     // 2
            + "    var p = /persons,\n"         // 3
            + "    do {\n"                      // 4
            + "        var x = \"abc\";\n"      // 5
            + "    }\n"                         // 6
            + "}\n";                            // 7

    DrlxLspDocumentService service = getDrlxLspDocumentService(content);

    InlayHintParams params = new InlayHintParams(
            new TextDocumentIdentifier("myDocument"),
            new Range(new Position(0, 0), new Position(7, 0)));

    List<InlayHint> hints = service.inlayHint(params).get();

    assertThat(hints).isNotEmpty();
    assertThat(hints)
            .extracting(h -> h.getPosition().getLine(), h -> h.getLabel().getLeft())
            .contains(tuple(3, ": Person"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl drlx-lsp-server test -Dtest=DrlxLspDocumentServiceTest#inlayHint_returnsHintsForDrlxFile`
Expected: Fails (`inlayHint` method not implemented / returns empty).

- [ ] **Step 3: Update `DrlxLspServer` and `DrlxLspDocumentService`**

1. In `DrlxLspServer.initialize()`:
   ```java
   initializeResult.getCapabilities().setInlayHintProvider(true);
   ```
2. In `DrlxLspDocumentService.java`:
   ```java
   @Override
   public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
       return CompletableFuture.supplyAsync(() -> {
           String uri = params.getTextDocument().getUri();
           String text = sourcesMap.get(uri);
           if (text == null) return Collections.emptyList();
           Range range = params.getRange();
           WorkspaceSemanticModel model = modelSupplier.get();
           return DrlxInlayHintHelper.inlayHints(text, range, model);
       });
   }
   ```

- [ ] **Step 4: Run full project tests**

Run: `mvn clean test`
Expected: All tests pass across all modules.

- [ ] **Step 5: Commit changes**

```bash
git add drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/DrlxLspDocumentServiceTest.java
git commit -m "feat: expose inlayHint LSP capability and service endpoint"
```

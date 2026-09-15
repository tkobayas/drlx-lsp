# Phase 3: Workspace Semantic Model — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `WorkspaceSemanticModel` (long-lived, owns `TypeSolver`) and `CompletionContext` (per-request, parse-tree-derived) into `drlx-completion`, wiring them into `DrlxCompletionHelper` and the LSP server to replace the hardcoded `TypeSolverBuilder` call.

**Architecture:** Two-layer model. `WorkspaceSemanticModel` is created once by the LSP server from a `ClasspathProvider` interface; it holds a configured JavaParser `CombinedTypeSolver`. `CompletionContext` is created per completion request from the parse tree and provides unit class name, imports, entry-point names, enclosing pattern type, and visible bindings. `DrlxCompletionHelper` becomes stateful, receives the model, and dispatches semantic completions via a `CompletionSite` switch.

**Tech Stack:** Java 17, ANTLR4 (DrlxParser), JavaParser Symbol Solver (custom fork 3.25.5-mvel3-SNAPSHOT), JUnit 5, AssertJ

## Global Constraints

- All new source files go in `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/`
- All new test files go in `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/`
- Project directory: `/home/tkobayas/usr/work/mvel3-development/drlx-lsp`
- Build: `mvn -pl drlx-completion -am install` after modifying `drlx-completion`; `mvn -pl drlx-lsp-server -am test` for server tests
- `ReflectionTypeSolver` is a temporary stand-in; if DOT_ACCESS tests break due to solver differences, that is acceptable
- JavaParser's `ResolvedType` is the type representation; no generics handling for raw reflection paths

---

### Task 1: ClasspathProvider interface and CurrentClassloaderProvider

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/ClasspathProvider.java`
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CurrentClassloaderProvider.java`
- Test: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/CurrentClassloaderProviderTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `ClasspathProvider` interface with `Set<Path> classpathEntries()` method; `CurrentClassloaderProvider` implementing it

- [ ] **Step 1: Write the test**

```java
package org.drools.drlx.completion.semantic;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class CurrentClassloaderProviderTest {

    @Test
    void classpathEntriesReturnsNonEmptySet() {
        CurrentClassloaderProvider provider = new CurrentClassloaderProvider();
        Set<Path> entries = provider.classpathEntries();
        assertThat(entries).isNotEmpty();
    }

    @Test
    void classpathEntriesContainsExistingPaths() {
        CurrentClassloaderProvider provider = new CurrentClassloaderProvider();
        Set<Path> entries = provider.classpathEntries();
        // At least some paths should exist on disk (JDK modules may not be file paths)
        assertThat(entries).anyMatch(p -> p.toFile().exists());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl drlx-completion -am test -Dtest="org.drools.drlx.completion.semantic.CurrentClassloaderProviderTest" -DfailIfNoTests=false`
Expected: compilation failure — classes don't exist yet

- [ ] **Step 3: Create ClasspathProvider interface**

```java
package org.drools.drlx.completion.semantic;

import java.nio.file.Path;
import java.util.Set;

public interface ClasspathProvider {
    Set<Path> classpathEntries();
}
```

- [ ] **Step 4: Create CurrentClassloaderProvider**

```java
package org.drools.drlx.completion.semantic;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

public class CurrentClassloaderProvider implements ClasspathProvider {

    @Override
    public Set<Path> classpathEntries() {
        Set<Path> entries = new LinkedHashSet<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ClassLoader.getSystemClassLoader();
        }
        while (cl != null) {
            if (cl instanceof URLClassLoader urlCl) {
                for (URL url : urlCl.getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        try {
                            entries.add(Paths.get(url.toURI()));
                        } catch (Exception e) {
                            // skip malformed URIs
                        }
                    }
                }
            }
            cl = cl.getParent();
        }
        // Fallback: parse java.class.path if no URLClassLoader found
        if (entries.isEmpty()) {
            String cp = System.getProperty("java.class.path", "");
            if (!cp.isEmpty()) {
                for (String entry : cp.split(System.getProperty("path.separator"))) {
                    entries.add(Paths.get(entry));
                }
            }
        }
        return entries;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl drlx-completion -am test -Dtest="org.drools.drlx.completion.semantic.CurrentClassloaderProviderTest" -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/ClasspathProvider.java \
       drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CurrentClassloaderProvider.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/semantic/CurrentClassloaderProviderTest.java
git commit -m "feat: add ClasspathProvider interface and CurrentClassloaderProvider"
```

---

### Task 2: WorkspaceSemanticModel

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModel.java`
- Test: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModelTest.java`

**Interfaces:**
- Consumes: `ClasspathProvider` from Task 1
- Produces: `WorkspaceSemanticModel` class with `TypeSolver typeSolver()`, `CompletionContext createContext(DrlxParser, ParseTree, int)`, and `void rebuild(ClasspathProvider)`

- [ ] **Step 1: Write the test**

```java
package org.drools.drlx.completion.semantic;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceSemanticModelTest {

    @Test
    void typeSolverResolvesJavaLangString() {
        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        TypeSolver solver = model.typeSolver();

        SymbolReference<ResolvedReferenceTypeDeclaration> ref = solver.tryToSolveType("java.lang.String");
        assertThat(ref.isSolved()).isTrue();
    }

    @Test
    void typeSolverResolvesJavaUtilList() {
        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        TypeSolver solver = model.typeSolver();

        SymbolReference<ResolvedReferenceTypeDeclaration> ref = solver.tryToSolveType("java.util.List");
        assertThat(ref.isSolved()).isTrue();
    }

    @Test
    void rebuildReplacesTypeSolver() {
        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        TypeSolver solverBefore = model.typeSolver();

        model.rebuild(new CurrentClassloaderProvider());
        TypeSolver solverAfter = model.typeSolver();

        assertThat(solverAfter).isNotSameAs(solverBefore);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl drlx-completion -am test -Dtest="org.drools.drlx.completion.semantic.WorkspaceSemanticModelTest" -DfailIfNoTests=false`
Expected: compilation failure — `WorkspaceSemanticModel` doesn't exist

- [ ] **Step 3: Write WorkspaceSemanticModel**

```java
package org.drools.drlx.completion.semantic;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxParser;

public class WorkspaceSemanticModel {

    private CombinedTypeSolver typeSolver;

    public WorkspaceSemanticModel(ClasspathProvider classpathProvider) {
        this.typeSolver = buildTypeSolver(classpathProvider);
    }

    public TypeSolver typeSolver() {
        return typeSolver;
    }

    public CompletionContext createContext(DrlxParser parser, ParseTree tree, int caretTokenIndex) {
        return new CompletionContext(this, parser, tree, caretTokenIndex);
    }

    public void rebuild(ClasspathProvider classpathProvider) {
        this.typeSolver = buildTypeSolver(classpathProvider);
    }

    private static CombinedTypeSolver buildTypeSolver(ClasspathProvider classpathProvider) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver());
        return solver;
    }
}
```

Note: `createContext` references `CompletionContext` which doesn't exist yet. For this task to compile, create a minimal stub:

```java
package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxParser;

public class CompletionContext {

    private final WorkspaceSemanticModel model;
    private final DrlxParser parser;
    private final ParseTree tree;
    private final int caretTokenIndex;

    CompletionContext(WorkspaceSemanticModel model, DrlxParser parser, ParseTree tree, int caretTokenIndex) {
        this.model = model;
        this.parser = parser;
        this.tree = tree;
        this.caretTokenIndex = caretTokenIndex;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl drlx-completion -am test -Dtest="org.drools.drlx.completion.semantic.WorkspaceSemanticModelTest" -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModel.java \
       drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModelTest.java
git commit -m "feat: add WorkspaceSemanticModel with CombinedTypeSolver"
```

---

### Task 3: CompletionContext — parse tree extraction

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java` (stub from Task 2)
- Test: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/CompletionContextTest.java`

**Interfaces:**
- Consumes: `WorkspaceSemanticModel` from Task 2; ANTLR `DrlxParser` and `ParseTree` from drlx-parser-core
- Produces: `CompletionContext` with methods `String unitClassName()`, `Set<String> imports()`, `List<String> entryPointNames()`, `TypeSolver typeSolver()`, `int caretTokenIndex()`, `ParseTree parseTree()`. Also stub methods `String findEnclosingPatternType()` (returns null) and `List<String> visibleBindings()` (returns empty) — real implementations deferred to when a CompletionSite consumer needs them.

- [ ] **Step 1: Write the test**

The test parses a DRLX text and verifies each accessor. We need a helper to parse DRLX text into a parser + tree, similar to how `DrlxCompletionHelper` does it internally.

```java
package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionContextTest {

    private static final String DRLX_TEXT = """
            package org.example;
            import org.example.Person;
            import org.example.Address;
            unit PersonUnit;
            rule FindAdults {
                Person p : /persons[age >= 18]
                Address a : /addresses
                ---
                System.out.println(p);
            }
            rule FindChildren(String name) {
                Person p : /persons[age < 18]
                ---
                System.out.println(p);
            }
            """;

    private DrlxParser parser;
    private ParseTree tree;

    private void parse(String text) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        parser = new DrlxParser(tokens);
        tree = parser.drlxStart();
    }

    @Test
    void unitClassName() {
        parse(DRLX_TEXT);
        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        CompletionContext ctx = model.createContext(parser, tree, 0);

        assertThat(ctx.unitClassName()).isEqualTo("PersonUnit");
    }

    @Test
    void imports() {
        parse(DRLX_TEXT);
        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        CompletionContext ctx = model.createContext(parser, tree, 0);

        assertThat(ctx.imports()).containsExactlyInAnyOrder(
                "org.example.Person", "org.example.Address");
    }

    @Test
    void entryPointNames() {
        parse(DRLX_TEXT);
        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        CompletionContext ctx = model.createContext(parser, tree, 0);

        assertThat(ctx.entryPointNames()).containsExactlyInAnyOrder("persons", "addresses");
    }

    @Test
    void unitClassNameWhenNoUnit() {
        parse("public class Foo {}");
        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        CompletionContext ctx = model.createContext(parser, tree, 0);

        assertThat(ctx.unitClassName()).isNull();
    }

    @Test
    void typeSolverDelegatesToModel() {
        parse(DRLX_TEXT);
        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        CompletionContext ctx = model.createContext(parser, tree, 0);

        assertThat(ctx.typeSolver()).isSameAs(model.typeSolver());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl drlx-completion -am test -Dtest="org.drools.drlx.completion.semantic.CompletionContextTest" -DfailIfNoTests=false`
Expected: compilation failure — methods don't exist on the stub

- [ ] **Step 3: Implement CompletionContext**

Replace the stub from Task 2 with the full implementation:

```java
package org.drools.drlx.completion.semantic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.github.javaparser.resolution.TypeSolver;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxParser;
import org.drools.drlx.parser.DrlxParser.BoundOopathContext;
import org.drools.drlx.parser.DrlxParser.DrlxCompilationUnitContext;
import org.drools.drlx.parser.DrlxParser.ImportDeclarationContext;
import org.drools.drlx.parser.DrlxParser.OopathRootContext;
import org.drools.drlx.parser.DrlxParser.RuleDeclarationContext;
import org.drools.drlx.parser.DrlxParser.RuleParameterContext;
import org.drools.drlx.parser.DrlxParser.RuleParameterListContext;
import org.drools.drlx.parser.DrlxParser.UnitDeclarationContext;

public class CompletionContext {

    private final WorkspaceSemanticModel model;
    private final DrlxParser parser;
    private final ParseTree tree;
    private final int caretTokenIndex;

    private String unitClassName;
    private boolean unitClassNameResolved;
    private Set<String> imports;
    private List<String> entryPointNames;

    CompletionContext(WorkspaceSemanticModel model, DrlxParser parser, ParseTree tree, int caretTokenIndex) {
        this.model = model;
        this.parser = parser;
        this.tree = tree;
        this.caretTokenIndex = caretTokenIndex;
    }

    public TypeSolver typeSolver() {
        return model.typeSolver();
    }

    public String unitClassName() {
        if (!unitClassNameResolved) {
            unitClassNameResolved = true;
            DrlxCompilationUnitContext cu = findDrlxCompilationUnit();
            if (cu != null) {
                UnitDeclarationContext unitDecl = cu.unitDeclaration();
                if (unitDecl != null && unitDecl.qualifiedName() != null) {
                    unitClassName = unitDecl.qualifiedName().getText();
                }
            }
        }
        return unitClassName;
    }

    public Set<String> imports() {
        if (imports == null) {
            imports = new LinkedHashSet<>();
            DrlxCompilationUnitContext cu = findDrlxCompilationUnit();
            if (cu != null) {
                for (ImportDeclarationContext imp : cu.importDeclaration()) {
                    if (imp.qualifiedName() != null) {
                        imports.add(imp.qualifiedName().getText());
                    }
                }
            }
        }
        return imports;
    }

    public List<String> entryPointNames() {
        if (entryPointNames == null) {
            entryPointNames = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            DrlxCompilationUnitContext cu = findDrlxCompilationUnit();
            if (cu != null) {
                for (RuleDeclarationContext rule : cu.ruleDeclaration()) {
                    collectEntryPoints(rule, seen);
                }
            }
            entryPointNames = new ArrayList<>(seen);
        }
        return entryPointNames;
    }

    private void collectEntryPoints(RuleDeclarationContext rule, Set<String> seen) {
        if (rule.ruleBody() == null) return;
        for (var ruleItem : rule.ruleBody().ruleItem()) {
            collectEntryPointsFromTree(ruleItem, seen);
        }
    }

    private void collectEntryPointsFromTree(ParseTree node, Set<String> seen) {
        if (node instanceof BoundOopathContext bound) {
            var oopathExpr = bound.oopathExpression();
            if (oopathExpr != null) {
                OopathRootContext root = oopathExpr.oopathRoot();
                if (root != null && root.identifier() != null) {
                    seen.add(root.identifier().getText());
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectEntryPointsFromTree(node.getChild(i), seen);
        }
    }

    private DrlxCompilationUnitContext findDrlxCompilationUnit() {
        return findContext(tree, DrlxCompilationUnitContext.class);
    }

    public int caretTokenIndex() {
        return caretTokenIndex;
    }

    public ParseTree parseTree() {
        return tree;
    }

    public String findEnclosingPatternType() {
        // Stub — real implementation when CONSTRAINT_EXPRESSION dispatch needs it
        return null;
    }

    public List<String> visibleBindings() {
        // Stub — real implementation when CONSEQUENCE_EXPRESSION dispatch needs it
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static <T> T findContext(ParseTree node, Class<T> type) {
        if (type.isInstance(node)) {
            return (T) node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            T found = findContext(node.getChild(i), type);
            if (found != null) return found;
        }
        return null;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl drlx-completion -am test -Dtest="org.drools.drlx.completion.semantic.CompletionContextTest" -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 5: Run all existing completion tests to check for regressions**

Run: `mvn -pl drlx-completion -am test`
Expected: all tests pass (CompletionContext is not wired into the helper yet)

- [ ] **Step 6: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/semantic/CompletionContextTest.java
git commit -m "feat: implement CompletionContext parse-tree extraction"
```

---

### Task 4: Wire WorkspaceSemanticModel into DrlxCompletionHelper

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java`
- Modify: `drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperNewConstructsTest.java`

**Interfaces:**
- Consumes: `WorkspaceSemanticModel` from Task 2, `CompletionContext` from Task 3
- Produces: `DrlxCompletionHelper(WorkspaceSemanticModel)` constructor; instance method `getCompletionItems(String, Position)`

- [ ] **Step 1: Make DrlxCompletionHelper stateful**

In `DrlxCompletionHelper.java`, make these changes:

1. Add a `model` field and constructor:

```java
private final WorkspaceSemanticModel model;

public DrlxCompletionHelper(WorkspaceSemanticModel model) {
    this.model = model;
}
```

2. Remove the private no-arg constructor (line 46-47).

3. Change `getCompletionItems(String, Position)` from `public static` to `public` (line 49). Change internal `getCompletionItems(DrlxParser, int, ParseTree)` from `static` to private instance (line 61).

4. Change `createSemanticCompletions` signature to accept `CompletionSite` and `CompletionContext`:

```java
private List<CompletionItem> createSemanticCompletions(CompletionSite site, CompletionContext ctx) {
    return switch (site) {
        case DOT_ACCESS -> resolveDotAccess(ctx);
        default -> List.of();
    };
}
```

5. Extract the current DOT_ACCESS logic into `resolveDotAccess(CompletionContext ctx)`. Replace the hardcoded `TypeSolverBuilder` block (lines 112-115) with `ctx.typeSolver()`:

```java
private List<CompletionItem> resolveDotAccess(CompletionContext ctx) {
    List<CompletionItem> semanticItems = new ArrayList<>();

    int previousTokenIndex = ctx.caretTokenIndex() - 1;
    int scopeTokenIndex = previousTokenIndex - 1;

    TolerantDrlxToJavaParserVisitor visitor = new TolerantDrlxToJavaParserVisitor();
    CompilationUnit compilationUnit = (CompilationUnit) visitor.visit(ctx.parseTree());

    JavaSymbolSolver solver = new JavaSymbolSolver(ctx.typeSolver());
    solver.inject(compilationUnit);

    Map<Integer, Node> tokenIdJPNodeMap = visitor.getTokenIdJPNodeMap();
    Expression scopeNode = (Expression) tokenIdJPNodeMap.get(scopeTokenIndex);
    if (scopeNode == null) {
        logger.info("scopeNode is null");
    } else {
        logger.info("scopeNode: " + scopeNode.getClass() + " , text => [" + scopeNode.toString() + "]");
        ResolvedType resolvedType = scopeNode.calculateResolvedType();
        semanticItems.addAll(createTypeBasedCompletions(resolvedType));
    }

    if (semanticItems.isEmpty()) {
        semanticItems.add(createCompletionItem("IDENTIFIER", CompletionItemKind.Text));
    }
    return semanticItems;
}
```

6. Update the main pipeline in `getCompletionItems(DrlxParser, int, ParseTree)` to create a `CompletionContext`:

```java
CompletionSite site = CompletionContextAnalyzer.analyze(candidates, parser, caretTokenIndex);
if (site.needsSemanticCompletions()) {
    CompletionContext ctx = model.createContext(parser, parseTree, caretTokenIndex);
    items.addAll(createSemanticCompletions(site, ctx));
}
```

7. `CompletionContext` needs a package-private accessor for `caretTokenIndex()` and `parseTree()`. Add to `CompletionContext.java`:

```java
public int caretTokenIndex() {
    return caretTokenIndex;
}

public ParseTree parseTree() {
    return tree;
}
```

8. Change remaining private methods from `static` to instance methods: `createSemanticCompletions`, `resolveDotAccess`, `deduplicateItems`, `createTypeBasedCompletions`, `addDirectPropertyAccess`, `isAccessible` (both overloads), `createDrlxParser`, `computeTokenIndex`. Keep `createCompletionItem` and `completionItemStrings` as `static` since they are used by tests and by `DrlxLspDocumentService`.

9. Remove the import for `TypeSolverBuilder` (line 23) since it's no longer used.

- [ ] **Step 2: Update test classes**

All three test classes currently call `DrlxCompletionHelper.getCompletionItems(text, pos)` statically. Update each to create an instance with a default model.

In each test class, add a field and helper:

```java
import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;

// ...

private final DrlxCompletionHelper helper = new DrlxCompletionHelper(
        new WorkspaceSemanticModel(new CurrentClassloaderProvider()));
```

Then replace all `DrlxCompletionHelper.getCompletionItems(text, caretPosition)` calls with `helper.getCompletionItems(text, caretPosition)`.

For `DrlxCompletionHelper.createCompletionItem(...)` — this remains static, no change needed.

For `DrlxCompletionHelper.completionItemStrings(...)` — this remains static, no change needed.

- [ ] **Step 3: Run all completion tests**

Run: `mvn -pl drlx-completion -am test`
Expected: all tests pass. If any DOT_ACCESS tests fail due to `ReflectionTypeSolver` differences, note but accept.

- [ ] **Step 4: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/DrlxCompletionHelper.java \
       drlx-completion/src/main/java/org/drools/drlx/completion/semantic/CompletionContext.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperTest.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperIncompleteCodeTest.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/DrlxCompletionHelperNewConstructsTest.java
git commit -m "refactor: wire WorkspaceSemanticModel into DrlxCompletionHelper"
```

---

### Task 5: Wire into LSP server

**Files:**
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java`
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java`
- Modify: `drlx-lsp-server/src/test/java/org/drools/drlx/lsp/server/TestHelperMethods.java`

**Interfaces:**
- Consumes: `WorkspaceSemanticModel` from Task 2, `DrlxCompletionHelper(WorkspaceSemanticModel)` from Task 4
- Produces: Updated server wiring

- [ ] **Step 1: Update DrlxLspServer**

In `DrlxLspServer.java`:

1. Add import:
```java
import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
```

2. Add model field and update constructor:
```java
private final WorkspaceSemanticModel model;

public DrlxLspServer() {
    this.model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
    textService = new DrlxLspDocumentService(this, model);
    workspaceService = new DrlxLspWorkspaceService();
}
```

- [ ] **Step 2: Update DrlxLspDocumentService**

In `DrlxLspDocumentService.java`:

1. Add imports:
```java
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
```

2. Add `completionHelper` field and update constructor:
```java
private final DrlxCompletionHelper completionHelper;

public DrlxLspDocumentService(DrlxLspServer server, WorkspaceSemanticModel model) {
    this.server = server;
    this.completionHelper = new DrlxCompletionHelper(model);
}
```

3. Update `getCompletionItems` method (line 100) — replace static call:
```java
// Before:
List<CompletionItem> completionItems = DrlxCompletionHelper.getCompletionItems(text, caretPosition);
// After:
List<CompletionItem> completionItems = completionHelper.getCompletionItems(text, caretPosition);
```

4. Update the static import of `completionItemStrings` (line 28) — this stays as a static import since the method remains static.

- [ ] **Step 3: Update TestHelperMethods**

In `TestHelperMethods.java`:

The `getDrlxLspServerForDocument` method creates a `DrlxLspServer` with `new DrlxLspServer()` — this continues to work since the no-arg constructor now internally creates the model. No change needed here.

However, the `DrlxLspDocumentService` constructor changed from `(DrlxLspServer)` to `(DrlxLspServer, WorkspaceSemanticModel)`. Since `TestHelperMethods` calls `new DrlxLspServer()` which internally calls the new `DrlxLspDocumentService(this, model)` constructor, no change is needed in `TestHelperMethods`.

- [ ] **Step 4: Run LSP server tests**

Run: `mvn -pl drlx-lsp-server -am test`
Expected: all tests pass

- [ ] **Step 5: Run full project tests**

Run: `mvn test`
Expected: all tests pass (with possible DOT_ACCESS exceptions noted)

- [ ] **Step 6: Commit**

```bash
git add drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java \
       drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java
git commit -m "refactor: wire WorkspaceSemanticModel into LSP server"
```

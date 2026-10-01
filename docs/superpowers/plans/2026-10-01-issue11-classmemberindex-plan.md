# ClassMemberIndex Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reflection-based `ClassMemberIndex` that lazily loads and caches class members (bean properties, public fields, enum constants) by FQCN, available to hover, lint, and other features independently of the completion pipeline.

**Architecture:** A standalone `ClassMemberIndex` class in `drlx-completion` uses `Class.forName(fqcn, false, loader)` with a `ConcurrentHashMap` cache. It is created alongside `ClassIndex` inside `WorkspaceSemanticModel.rebuild()` and exposed via a getter. A `Field` record holds each member's name, type FQCN, and parameter types.

**Tech Stack:** Java 17, reflection API, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-10-01-issue11-classmemberindex-spec.md`

## Global Constraints

- Java 17+
- No new dependencies — uses `java.lang.reflect` only
- Package: `org.drools.drlx.completion.semantic` (alongside `ClassIndex`)
- Thread safety via `ConcurrentHashMap` — no external synchronization
- `Class.forName(fqcn, false, loader)` — static initializers must never run

## Review Focus

1. **Primitive getter return types**: `getAge()` returns `int` — `typeFqcn` should be `"int"`, not `null` or an NPE from `getName()` on a primitive `Class`. Test added in Task 1.
2. **`isX()` with non-boolean return**: `isFoo()` returning `String` must NOT be extracted as a property. Test added in Task 1.
3. **Class with no accessible members**: e.g. `Object` itself — `membersOf("java.lang.Object")` should return an empty list (all its methods are excluded). Test added in Task 1.
4. **Generic getter return types**: `getPreviousAddresses()` returns `List<Address>` — reflection erases to `java.util.List`. The `typeFqcn` should be `"java.util.List"`, not crash. Test added in Task 1.
5. **Concurrent access**: two threads calling `membersOf()` for the same FQCN simultaneously — `ConcurrentHashMap.computeIfAbsent` handles this, but verify no duplicate reflection. Covered by `ConcurrentHashMap` contract; no explicit test needed.

---

### Task 1: Field record, ClassMemberIndex class, and tests

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/Field.java`
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/ClassMemberIndex.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/ClassMemberIndexTest.java`

**Interfaces:**
- Consumes: nothing (leaf infrastructure)
- Produces:
  - `record Field(String name, String typeFqcn, List<String> args)` — used by future hover/lint
  - `ClassMemberIndex.of(Set<Path>)` → `ClassMemberIndex` — used by Task 2 (`WorkspaceSemanticModel`)
  - `ClassMemberIndex.empty()` → `ClassMemberIndex`
  - `ClassMemberIndex.membersOf(String fqcn)` → `List<Field>`
  - `ClassMemberIndex.memberNames(String fqcn)` → `Set<String>` or `null`
  - `ClassMemberIndex.close()` → `void`

- [ ] **Step 1: Write the Field record**

Create `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/Field.java`:

```java
package org.drools.drlx.completion.semantic;

import java.util.List;

public record Field(String name, String typeFqcn, List<String> args) {

    public Field(String name, String typeFqcn) {
        this(name, typeFqcn, List.of());
    }
}
```

- [ ] **Step 2: Write the ClassMemberIndex class**

Create `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/ClassMemberIndex.java`:

```java
package org.drools.drlx.completion.semantic;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClassMemberIndex implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ClassMemberIndex.class);

    private static final ClassMemberIndex EMPTY = new ClassMemberIndex((ClassLoader) null);

    private final ClassLoader loader;
    private final boolean ownsLoader;
    private final Map<String, List<Field>> cache = new ConcurrentHashMap<>();

    ClassMemberIndex(ClassLoader loader) {
        this(loader, false);
    }

    private ClassMemberIndex(ClassLoader loader, boolean ownsLoader) {
        this.loader = loader;
        this.ownsLoader = ownsLoader;
    }

    public static ClassMemberIndex empty() {
        return EMPTY;
    }

    public static ClassMemberIndex of(Set<Path> classpathEntries) {
        if (classpathEntries == null || classpathEntries.isEmpty()) {
            return EMPTY;
        }
        List<URL> urls = new ArrayList<>(classpathEntries.size());
        for (Path entry : classpathEntries) {
            try {
                urls.add(entry.toUri().toURL());
            } catch (Exception e) {
                logger.debug("Skipping classpath entry {}: {}", entry, e.getMessage());
            }
        }
        URLClassLoader loader = new URLClassLoader(
                urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        return new ClassMemberIndex(loader, true);
    }

    public List<Field> membersOf(String fqcn) {
        if (loader == null || fqcn == null || fqcn.isEmpty()) {
            return Collections.emptyList();
        }
        return cache.computeIfAbsent(fqcn, this::reflectMembers);
    }

    public Set<String> memberNames(String fqcn) {
        if (loader == null || fqcn == null || fqcn.isEmpty()) {
            return null;
        }
        Class<?> clazz;
        try {
            clazz = Class.forName(fqcn, false, loader);
        } catch (Throwable t) {
            return null;
        }
        try {
            Set<String> names = new LinkedHashSet<>();
            for (java.lang.reflect.Field f : clazz.getFields()) {
                names.add(f.getName());
            }
            for (Class<?> nested : clazz.getClasses()) {
                names.add(nested.getSimpleName());
            }
            return names;
        } catch (Throwable t) {
            logger.debug("Failed to reflect member names of {}", fqcn, t);
            return null;
        }
    }

    @Override
    public void close() {
        cache.clear();
        if (ownsLoader && loader instanceof java.io.Closeable closeable) {
            try {
                closeable.close();
            } catch (java.io.IOException e) {
                logger.debug("Failed to close class member index loader", e);
            }
        }
    }

    private List<Field> reflectMembers(String fqcn) {
        Class<?> clazz;
        try {
            clazz = Class.forName(fqcn, false, loader);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        try {
            Map<String, Field> members = new LinkedHashMap<>();
            if (clazz.isEnum()) {
                for (java.lang.reflect.Field f : clazz.getFields()) {
                    if (f.isEnumConstant()) {
                        members.put(f.getName(),
                                new Field(f.getName(), clazz.getName()));
                    }
                }
            }
            for (Method m : clazz.getMethods()) {
                String property = propertyNameOf(m);
                if (property != null) {
                    members.putIfAbsent(property,
                            new Field(property, m.getReturnType().getName()));
                }
            }
            for (java.lang.reflect.Field f : clazz.getFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    members.putIfAbsent(f.getName(),
                            new Field(f.getName(), f.getType().getName()));
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(members.values()));
        } catch (Throwable t) {
            logger.debug("Failed to reflect members of {}", fqcn, t);
            return Collections.emptyList();
        }
    }

    private static String propertyNameOf(Method m) {
        if (m.getParameterCount() != 0 || m.getReturnType() == void.class
                || Modifier.isStatic(m.getModifiers())) {
            return null;
        }
        String name = m.getName();
        String raw;
        if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) {
            if ("getClass".equals(name)) {
                return null;
            }
            raw = name.substring(3);
        } else if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) {
            Class<?> returnType = m.getReturnType();
            if (returnType != boolean.class && returnType != Boolean.class) {
                return null;
            }
            raw = name.substring(2);
        } else {
            return null;
        }
        if (raw.length() > 1 && Character.isUpperCase(raw.charAt(0)) && Character.isUpperCase(raw.charAt(1))) {
            return raw;
        }
        char[] chars = raw.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }
}
```

- [ ] **Step 3: Write the tests**

Create `drlx-completion/src/test/java/org/drools/drlx/completion/ClassMemberIndexTest.java`:

```java
package org.drools.drlx.completion;

import java.util.List;
import java.util.Set;

import org.drools.drlx.completion.semantic.ClassMemberIndex;
import org.drools.drlx.completion.semantic.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassMemberIndexTest {

    private ClassMemberIndex index;

    @BeforeEach
    void setUp() {
        // Use the test classloader directly — domain classes are on the test classpath
        index = new ClassMemberIndex(Thread.currentThread().getContextClassLoader());
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    @Test
    void membersOf_beanProperties() {
        List<Field> members = index.membersOf("org.drools.drlx.domain.Person");
        assertThat(members).extracting(Field::name)
                .contains("name", "age", "address", "previousAddresses");
    }

    @Test
    void membersOf_propertyTypes() {
        List<Field> members = index.membersOf("org.drools.drlx.domain.Person");
        Field nameField = members.stream()
                .filter(f -> f.name().equals("name")).findFirst().orElseThrow();
        assertThat(nameField.typeFqcn()).isEqualTo("java.lang.String");

        Field ageField = members.stream()
                .filter(f -> f.name().equals("age")).findFirst().orElseThrow();
        assertThat(ageField.typeFqcn()).isEqualTo("int");
    }

    @Test
    void membersOf_genericReturnType_usesErasure() {
        List<Field> members = index.membersOf("org.drools.drlx.domain.Person");
        Field prevAddr = members.stream()
                .filter(f -> f.name().equals("previousAddresses")).findFirst().orElseThrow();
        assertThat(prevAddr.typeFqcn()).isEqualTo("java.util.List");
    }

    @Test
    void membersOf_excludesObjectMethods() {
        List<Field> members = index.membersOf("org.drools.drlx.domain.Person");
        assertThat(members).extracting(Field::name)
                .doesNotContain("class");
    }

    @Test
    void membersOf_objectClass_isEmpty() {
        List<Field> members = index.membersOf("java.lang.Object");
        assertThat(members).isEmpty();
    }

    @Test
    void membersOf_enumConstants() {
        List<Field> members = index.membersOf("java.time.DayOfWeek");
        assertThat(members).extracting(Field::name)
                .contains("MONDAY", "TUESDAY", "WEDNESDAY");
    }

    @Test
    void membersOf_unknownClass() {
        List<Field> members = index.membersOf("com.nonexistent.Foo");
        assertThat(members).isEmpty();
    }

    @Test
    void membersOf_caching() {
        List<Field> first = index.membersOf("org.drools.drlx.domain.Person");
        List<Field> second = index.membersOf("org.drools.drlx.domain.Person");
        assertThat(first).isSameAs(second);
    }

    @Test
    void memberNames_knownClass() {
        Set<String> names = index.memberNames("org.drools.drlx.domain.Person");
        assertThat(names).isNotNull();
    }

    @Test
    void memberNames_unknownClass() {
        Set<String> names = index.memberNames("com.nonexistent.Foo");
        assertThat(names).isNull();
    }

    @Test
    void empty_resolvesNothing() {
        ClassMemberIndex emptyIndex = ClassMemberIndex.empty();
        assertThat(emptyIndex.membersOf("org.drools.drlx.domain.Person")).isEmpty();
        assertThat(emptyIndex.memberNames("org.drools.drlx.domain.Person")).isNull();
    }
}
```

- [ ] **Step 4: Build and run tests**

Run: `mvn -pl drlx-completion -am install -DskipTests -q && mvn -pl drlx-completion test -Dtest="ClassMemberIndexTest"`

Expected: All 11 tests pass.

- [ ] **Step 5: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/Field.java \
       drlx-completion/src/main/java/org/drools/drlx/completion/semantic/ClassMemberIndex.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/ClassMemberIndexTest.java
git commit -m "feat: add ClassMemberIndex for reflection-based member lookup (#11)"
```

---

### Task 2: Wire ClassMemberIndex into WorkspaceSemanticModel and LSP server lifecycle

**Files:**
- Modify: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModel.java`
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java`

**Interfaces:**
- Consumes: `ClassMemberIndex.of(Set<Path>)`, `ClassMemberIndex.close()` from Task 1
- Produces:
  - `WorkspaceSemanticModel.classMemberIndex()` → `ClassMemberIndex` — used by helpers (hover, lint, etc.) in future items

- [ ] **Step 1: Write failing test — verify model exposes classMemberIndex**

Add to an existing test or create a small integration check. Since `WorkspaceSemanticModel` is tested indirectly through helper tests, verify the accessor exists by adding a compile-time usage in the test:

Create `drlx-completion/src/test/java/org/drools/drlx/completion/WorkspaceSemanticModelClassMemberIndexTest.java`:

```java
package org.drools.drlx.completion;

import org.drools.drlx.completion.semantic.ClassMemberIndex;
import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceSemanticModelClassMemberIndexTest {

    @Test
    void classMemberIndex_isAvailableAfterConstruction() {
        WorkspaceSemanticModel model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        ClassMemberIndex memberIndex = model.classMemberIndex();
        assertThat(memberIndex).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl drlx-completion test -Dtest="WorkspaceSemanticModelClassMemberIndexTest"`

Expected: Compilation error — `classMemberIndex()` method does not exist.

- [ ] **Step 3: Add classMemberIndex to WorkspaceSemanticModel**

Modify `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModel.java`:

Add field (after line 26 `private volatile ClassIndex classIndex = ClassIndex.empty();`):

```java
private volatile ClassMemberIndex classMemberIndex = ClassMemberIndex.empty();
```

Add getter (after the `classIndex()` method at line 42):

```java
public ClassMemberIndex classMemberIndex() {
    return classMemberIndex;
}
```

In the `rebuild(ClasspathProvider, boolean)` method (after line 61 `this.classIndex = ClassIndex.build(entries);`):

```java
this.classMemberIndex.close();
this.classMemberIndex = ClassMemberIndex.of(entries);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl drlx-completion -am install -DskipTests -q && mvn -pl drlx-completion test -Dtest="WorkspaceSemanticModelClassMemberIndexTest"`

Expected: PASS

- [ ] **Step 5: Add close() call in DrlxLspServer.shutdown()**

Modify `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java`, line 103-105:

Replace the `shutdown()` method:

```java
@Override
public CompletableFuture<Object> shutdown() {
    model.classMemberIndex().close();
    return CompletableFuture.completedFuture(null);
}
```

- [ ] **Step 6: Build to verify compilation**

Run: `mvn -pl drlx-lsp-server -am install -DskipTests -q`

Expected: BUILD SUCCESS

- [ ] **Step 7: Run full test suite**

Run: `mvn -pl drlx-completion test`

Expected: All tests pass (existing + new).

- [ ] **Step 8: Commit**

```bash
git add drlx-completion/src/main/java/org/drools/drlx/completion/semantic/WorkspaceSemanticModel.java \
       drlx-completion/src/test/java/org/drools/drlx/completion/WorkspaceSemanticModelClassMemberIndexTest.java \
       drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspServer.java
git commit -m "feat: wire ClassMemberIndex into WorkspaceSemanticModel lifecycle (#11)"
```

# DrlxWorkspaceFileIndex Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ワークスペース内の兄弟 `.drlx` ファイルを「未保存バッファ優先・ディスク次」でイテレートするインフラクラス `DrlxWorkspaceFileIndex` を追加する。併せて `DrlxLspDocumentService.openSiblings()` ヘルパーを整備する。実際の消費者は将来の item（#18 JavaSourceLocator 等）で追加するため、今回はインフラのみで完結する。

**Architecture:**
- `DrlxWorkspaceFileIndex`（`semantic` パッケージ）: `forEachSiblingFile(documentPath, openFiles, sink)` のみを持つ final utility クラス。内部で `WorkspaceSiblingResolvers.active().resolveSiblings()` を使用してディスク上の兄弟ファイルを列挙し、`openFiles` バッファで shadow する。
- `DrlxLspDocumentService.openSiblings()`: URI キーの `sourcesMap` を Path キーの兄弟マップに変換する private ヘルパー。今回は呼び出し箇所なし。
- 既存クラス（`DrlxReferencesHelper`、`DrlxRenameHelper`、`DrlxLintHelper`）への変更なし（import スコープはカレントファイルのみ）。

**Tech Stack:** Java 21, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-10-01-issue11-workspace-type-index-spec.md`

## Global Constraints

- Java 21 language features
- No new dependencies
- `DrlxWorkspaceFileIndex` のパッケージ: `org.drools.drlx.completion.semantic`
- `openSiblings()` のパッケージ: `org.drools.drlx.lsp.server` (DrlxLspDocumentService 内)
- Build: `mvn -pl drlx-completion -am install -DskipTests -q` before running tests

## Review Focus

1. **`DeclaredType` レイヤーなし** — `forEachSiblingFile` のみ。`build()`、`buildLinkTargets()`、`docFor()` は実装しない。
2. **`null` ガード** — `documentPath = null` および `openFiles = null` の両方を静かに処理する。
3. **カレントドキュメント自身は sink に渡さない** — `docNorm.equals(norm)` の場合はスキップ。
4. **open-buffer shadowing の正確な順序** — バッファのエントリを先に処理し `shadowed` に追加してから、ディスクファイルの重複チェックを行う。
5. **ディスク読み込み失敗は静かにスキップ** — `IOException` は `logger.debug` のみ。
6. **既存クラスへの変更なし** — `DrlxReferencesHelper`、`DrlxRenameHelper`、`DrlxLintHelper` は一切触らない。

---

### Task 1: DrlxWorkspaceFileIndex の実装とテスト

**Files:**
- Create: `drlx-completion/src/main/java/org/drools/drlx/completion/semantic/DrlxWorkspaceFileIndex.java`
- Create: `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/DrlxWorkspaceFileIndexTest.java`

**Interfaces:**
- Consumes: `WorkspaceSiblingResolvers.active().resolveSiblings(Path)`
- Produces: `DrlxWorkspaceFileIndex.forEachSiblingFile(Path, Map<Path,String>, BiConsumer<String,String>)`

- [ ] **Step 1: テストを先に書く（RED）**

`drlx-completion/src/test/java/org/drools/drlx/completion/semantic/DrlxWorkspaceFileIndexTest.java` を作成:

```java
package org.drools.drlx.completion.semantic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxWorkspaceFileIndexTest {

    // --- forEachSiblingFile ---

    @Test
    void forEachSiblingFile_diskSibling(@TempDir Path dir) throws Exception {
        Path sibling = dir.resolve("types.drlx");
        Path current = dir.resolve("rules.drlx");
        Files.writeString(sibling, "// sibling");

        List<String> uris = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        DrlxWorkspaceFileIndex.forEachSiblingFile(current, Map.of(), (uri, text) -> {
            uris.add(uri);
            texts.add(text);
        });

        assertThat(uris).containsExactly(sibling.toUri().toString());
        assertThat(texts).containsExactly("// sibling");
    }

    @Test
    void forEachSiblingFile_excludesCurrentDocument(@TempDir Path dir) throws Exception {
        Path current = dir.resolve("rules.drlx");
        Path norm = current.toAbsolutePath().normalize();
        // openFiles にカレント自身を含める
        Map<Path, String> openFiles = Map.of(norm, "// self");

        List<String> uris = new ArrayList<>();
        DrlxWorkspaceFileIndex.forEachSiblingFile(current, openFiles, (uri, text) -> uris.add(uri));

        assertThat(uris).isEmpty();
    }

    @Test
    void forEachSiblingFile_openBufferShadowsDisk(@TempDir Path dir) throws Exception {
        Path sibling = dir.resolve("types.drlx");
        Path current = dir.resolve("rules.drlx");
        Files.writeString(sibling, "// disk version");
        Path norm = sibling.toAbsolutePath().normalize();
        Map<Path, String> openFiles = Map.of(norm, "// buffer version");

        List<String> texts = new ArrayList<>();
        DrlxWorkspaceFileIndex.forEachSiblingFile(current, openFiles, (uri, text) -> texts.add(text));

        // ディスク版は渡されず、バッファ版のみ、1回だけ
        assertThat(texts).containsExactly("// buffer version");
    }

    @Test
    void forEachSiblingFile_nullPath_doesNothing(@TempDir Path dir) throws Exception {
        Path sibling = dir.resolve("types.drlx");
        Files.writeString(sibling, "// ignored");

        List<String> uris = new ArrayList<>();
        DrlxWorkspaceFileIndex.forEachSiblingFile(null, Map.of(), (uri, text) -> uris.add(uri));

        assertThat(uris).isEmpty();
    }

    @Test
    void forEachSiblingFile_nullOpenFiles_readsDisk(@TempDir Path dir) throws Exception {
        Path sibling = dir.resolve("types.drlx");
        Path current = dir.resolve("rules.drlx");
        Files.writeString(sibling, "// disk only");

        List<String> texts = new ArrayList<>();
        DrlxWorkspaceFileIndex.forEachSiblingFile(current, null, (uri, text) -> texts.add(text));

        assertThat(texts).containsExactly("// disk only");
    }

    @Test
    void forEachSiblingFile_multipleSiblings(@TempDir Path dir) throws Exception {
        Path current = dir.resolve("rules.drlx");
        Path s1 = dir.resolve("a.drlx");
        Path s2 = dir.resolve("b.drlx");
        Files.writeString(s1, "// a");
        Files.writeString(s2, "// b");

        List<String> uris = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        DrlxWorkspaceFileIndex.forEachSiblingFile(current, Map.of(), (uri, text) -> {
            uris.add(uri);
            texts.add(text);
        });

        assertThat(uris).containsExactlyInAnyOrder(s1.toUri().toString(), s2.toUri().toString());
        assertThat(texts).containsExactlyInAnyOrder("// a", "// b");
    }
}
```

- [ ] **Step 2: コンパイルエラーを確認（RED）**

```bash
mvn -pl drlx-completion test -Dtest="org.drools.drlx.completion.semantic.DrlxWorkspaceFileIndexTest" -q 2>&1 | head -20
```

Expected: `DrlxWorkspaceFileIndex` が存在しないためコンパイルエラー。

- [ ] **Step 3: DrlxWorkspaceFileIndex を実装する（GREEN）**

`drlx-completion/src/main/java/org/drools/drlx/completion/semantic/DrlxWorkspaceFileIndex.java` を作成:

```java
package org.drools.drlx.completion.semantic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ワークスペース内の兄弟 {@code .drlx} ファイルを走査するユーティリティ。
 *
 * <p>drools-lsp の {@code DRLWorkspaceTypeIndex} のうち、DRLX に必要な
 * ファイルイテレーション機能のみを提供する。DRLX には {@code declare} ブロックが
 * 存在しないため、型インデックスとしての機能は持たない。
 *
 * <p>将来の消費者（JavaSourceLocator 等）が兄弟ファイルを横断スキャンする際に
 * このクラスを利用する。
 */
public final class DrlxWorkspaceFileIndex {

    private static final Logger logger = LoggerFactory.getLogger(DrlxWorkspaceFileIndex.class);

    private DrlxWorkspaceFileIndex() {
    }

    /**
     * 兄弟 {@code .drlx} ファイルを URI と現在のテキストのペアで {@code sink} に渡す。
     *
     * <p>優先順位（高い順）：
     * <ol>
     *   <li>未保存バッファ（{@code openFiles} に含まれる同ディレクトリのファイル）</li>
     *   <li>ディスク上のファイル（未保存バッファで shadow されていないもの）</li>
     * </ol>
     *
     * <p>{@code documentPath} 自身は含まない。{@code documentPath} が {@code null}
     * の場合は何も渡さない。ファイル読み込みに失敗した場合は静かにスキップする。
     *
     * @param documentPath  カレントドキュメントのファイルシステムパス。{@code null} 可。
     * @param openFiles     未保存の兄弟バッファ。Path（絶対・正規化済み）→ テキスト。
     *                      {@code null} の場合は空マップと同等に扱う。
     * @param sink          URI（{@code file:///...} 形式の文字列）とテキストを受け取る消費者。
     */
    public static void forEachSiblingFile(
            Path documentPath,
            Map<Path, String> openFiles,
            BiConsumer<String, String> sink) {

        if (documentPath == null) {
            return;
        }

        Path docNorm = documentPath.toAbsolutePath().normalize();
        Path dir = docNorm.getParent();
        if (dir == null) {
            return;
        }

        Set<Path> shadowed = new HashSet<>();

        // レイヤー2: 未保存バッファ（同ディレクトリ、カレントファイル以外）
        if (openFiles != null) {
            for (Map.Entry<Path, String> e : openFiles.entrySet()) {
                Path norm = normalizedSibling(e.getKey(), docNorm, dir);
                if (norm == null) {
                    continue;
                }
                shadowed.add(norm);
                sink.accept(norm.toUri().toString(), e.getValue());
            }
        }

        // レイヤー3: ディスク上のファイル（shadow されていないもの）
        for (Path sibling : WorkspaceSiblingResolvers.active().resolveSiblings(documentPath)) {
            Path norm = sibling.toAbsolutePath().normalize();
            if (shadowed.contains(norm)) {
                continue;
            }
            String text = readFileSilently(sibling);
            if (text != null) {
                sink.accept(sibling.toUri().toString(), text);
            }
        }
    }

    /**
     * {@code candidate} が {@code docNorm} の同ディレクトリ兄弟である場合に
     * 正規化済みパスを返す。そうでない場合は {@code null} を返す。
     */
    private static Path normalizedSibling(Path candidate, Path docNorm, Path dir) {
        if (candidate == null || dir == null) {
            return null;
        }
        Path norm = candidate.toAbsolutePath().normalize();
        if (norm.equals(docNorm) || !dir.equals(norm.getParent())) {
            return null;
        }
        return norm;
    }

    private static String readFileSilently(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            logger.debug("Failed to read sibling file {}: {}", file, e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 4: ビルドしてテストをグリーンにする**

```bash
mvn -pl drlx-completion -am install -DskipTests -q
mvn -pl drlx-completion test -Dtest="org.drools.drlx.completion.semantic.DrlxWorkspaceFileIndexTest" -q
```

Expected: 6 tests pass.

- [ ] **Step 5: モジュール全体のテストが通ることを確認**

```bash
mvn -pl drlx-completion test -q
```

Expected: 既存テストも含めてすべてグリーン。

---

### Task 2: DrlxLspDocumentService.openSiblings() の追加

**Files:**
- Modify: `drlx-lsp-server/src/main/java/org/drools/drlx/lsp/server/DrlxLspDocumentService.java`

**Interfaces:**
- Produces: `private Map<Path, String> openSiblings(Path documentPath)`
- 今回は呼び出し箇所なし（将来の item で使用）

- [ ] **Step 1: openSiblings() メソッドを DrlxLspDocumentService に追加する**

`DrlxLspDocumentService.java` の `didSave()` メソッドの後（末尾）に追加:

```java
/**
 * カレントドキュメントと同じディレクトリにある未保存の兄弟 {@code .drlx} バッファを返す。
 *
 * <p>URI キーの {@code sourcesMap} を Path キー（絶対・正規化済み）に変換し、
 * カレントファイル以外の同ディレクトリの {@code .drlx} ファイルのみを含める。
 * {@code documentPath} が {@code null} の場合は空マップを返す。
 * ファイル URI 以外のエントリは無視する。
 *
 * @param documentPath  カレントドキュメントのパス。{@code null} 可。
 * @return              Path → テキストのマップ（変更不可）
 */
private Map<Path, String> openSiblings(Path documentPath) {
    if (documentPath == null) {
        return Collections.emptyMap();
    }
    Path docNorm = documentPath.toAbsolutePath().normalize();
    Path dir = docNorm.getParent();
    if (dir == null) {
        return Collections.emptyMap();
    }
    Map<Path, String> result = new java.util.HashMap<>();
    for (Map.Entry<String, String> e : sourcesMap.entrySet()) {
        Path p;
        try {
            p = java.nio.file.Paths.get(java.net.URI.create(e.getKey()));
        } catch (Exception ex) {
            continue;
        }
        Path norm = p.toAbsolutePath().normalize();
        if (norm.equals(docNorm)) {
            continue;
        }
        if (!norm.toString().endsWith(".drlx")) {
            continue;
        }
        if (!dir.equals(norm.getParent())) {
            continue;
        }
        result.put(norm, e.getValue());
    }
    return Collections.unmodifiableMap(result);
}
```

必要な import を追加:
```java
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
```

- [ ] **Step 2: ビルドが通ることを確認**

```bash
mvn -pl drlx-lsp-server -am install -DskipTests -q
```

Expected: コンパイルエラーなし。

- [ ] **Step 3: drlx-lsp-server のテストが通ることを確認**

```bash
mvn -pl drlx-lsp-server test -q
```

Expected: 既存テストすべてグリーン。

---

### Task 3: 全体ビルドとテストの確認

- [ ] **Step 1: ルートから全モジュールをビルド**

```bash
mvn install -DskipTests -q
```

- [ ] **Step 2: 全テストを実行**

```bash
mvn test -q
```

Expected: すべてグリーン。新規失敗なし。

- [ ] **Step 3: spec の Status を更新**

`docs/superpowers/specs/2026-10-01-issue11-workspace-type-index-spec.md` の先頭の `Status: Proposed` を `Status: Implemented` に変更する。

- [ ] **Step 4: HANDOFF.md を更新**

```
# Handoff: drlx-lsp — WorkspaceFileIndex (issue #11 item #19)

## What happened this session

- **#19 WorkspaceTypeIndex: DrlxWorkspaceFileIndex と openSiblings() を実装。**
  - `DrlxWorkspaceFileIndex.forEachSiblingFile()` — 兄弟 .drlx ファイルのイテレーション（未保存バッファ優先）
  - `DrlxLspDocumentService.openSiblings()` — URI キー → Path キーの兄弟マップ変換
  - 消費者はなし（インフラのみ）
  - Spec: docs/superpowers/specs/2026-10-01-issue11-workspace-type-index-spec.md
  - Plan: docs/superpowers/plans/2026-10-01-issue11-workspace-type-index-plan.md

## What's next

Full order: ~~#14~~, ~~#20~~, ~~#15 SKIP~~, ~~#17~~, ~~#16~~, ~~#21~~, ~~#19~~, #18 JavaSourceLocator, #22 File-change rebuild.
```

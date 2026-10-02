# DrlxWorkspaceFileIndex — Spec (issue #11, item #19)

**Date:** 2026-10-01  
**Issue:** [#11](https://github.com/tkobayas/drlx-lsp/issues/11) Item #19  
**Status:** Implemented

---

## 1. 背景と目的

drools-lsp には `DRLWorkspaceTypeIndex` という「宣言型のクロスファイルインデックス」が存在する。
このクラスは `declare Foo … end` ブロック（`DRLDeclaredTypeParser`）を基盤として、hover・references・rename・lint のすべてに型情報を横断的に提供している。

drlx-lsp では宣言型パーサー（#15）は **SKIP** のため、そのまま移植しても実体がない。  
DRLX において型の出所は以下のとおりであり、すべて既存の `ClassIndex` / `ClassMemberIndex` でカバー済みである：

- クラスパスの import（`ClassIndex`）
- ワークスペース内の Java クラス（`ClassIndex` + `ClassMemberIndex`）

`DRLWorkspaceTypeIndex` が担う機能のうち drlx-lsp で必要なものは、**宣言型インデックスではなく**、  
「兄弟 `.drlx` ファイルを *未保存バッファ優先・ディスク次* でイテレートするプリミティブ」のみである。

本 item の目的は、このプリミティブを **インフラクラス** として整備し、
将来の消費者（#18 JavaSourceLocator 等）が利用できるようにすることである。

---

## 2. DRLX における import のスコープポリシー

DRLX では Java ソースと同様、**import はカレントファイルに書く必要がある**。  
兄弟ファイルの import を「共有 import」として認識することはしない。

これにより以下が確定する：

| 操作 | スコープ |
|---|---|
| `findImportTypeReferences` | カレントファイルのみ（変更なし） |
| `DrlxRenameHelper`（import 型） | カレントファイルのみ（変更なし） |
| `DrlxLintHelper.isTypeKnown()` | クラスパスのみ（変更なし） |

---

## 3. 成果物一覧

| 成果物 | 種別 | 説明 |
|---|---|---|
| `DrlxWorkspaceFileIndex` | 新規クラス | `forEachSiblingFile` を提供する utility クラス |
| `DrlxLspDocumentService.openSiblings()` | 既存クラスへのメソッド追加 | URI キーの `sourcesMap` を Path キーの兄弟マップに変換するヘルパー |
| `DrlxWorkspaceFileIndexTest` | 新規テスト | `forEachSiblingFile` の単体テスト |

---

## 4. クラス設計

### 4.1 `DrlxWorkspaceFileIndex`

**パッケージ:** `org.drools.drlx.completion.semantic`

```java
/**
 * ワークスペース内の兄弟 .drlx ファイルを走査するユーティリティ。
 *
 * <p>drools-lsp の {@code DRLWorkspaceTypeIndex} のうち、DRLX に必要な
 * ファイルイテレーション機能のみを提供する。宣言型（{@code declare} ブロック）
 * は DRLX に存在しないため、型インデックスとしての機能は持たない。
 *
 * <p>将来の消費者（JavaSourceLocator 等）が兄弟ファイルを横断スキャンする際に
 * このクラスを利用する。
 */
public final class DrlxWorkspaceFileIndex {

    private DrlxWorkspaceFileIndex() {}

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
     * @param openFiles     未保存の兄弟バッファ。Path（絶対・正規化済み）→テキスト。
     *                      {@code null} の場合は空マップと同等に扱う。
     * @param sink          URI（{@code file:///...} 形式の文字列）とテキストを受け取る消費者。
     */
    public static void forEachSiblingFile(
            Path documentPath,
            Map<Path, String> openFiles,
            BiConsumer<String, String> sink) { … }
}
```

#### 内部実装

```
1. documentPath が null → 即リターン
2. docNorm = documentPath.toAbsolutePath().normalize()
3. dir    = docNorm.getParent()
4. shadowed = new HashSet<Path>()

// レイヤー2: 未保存バッファ（同ディレクトリ、カレントファイル以外）
5. openFiles をイテレート:
     norm = p.toAbsolutePath().normalize()
     norm == docNorm → スキップ
     norm.getParent() != dir → スキップ
     shadowed.add(norm)
     sink.accept(norm.toUri().toString(), バッファのテキスト)

// レイヤー3: ディスク上のファイル（shadow されていないもの）
6. WorkspaceSiblingResolvers.active().resolveSiblings(documentPath) をイテレート:
     norm = sibling.toAbsolutePath().normalize()
     shadowed.contains(norm) → スキップ
     text = Files.readString(sibling)（失敗したら静かにスキップ）
     sink.accept(sibling.toUri().toString(), text)
```

`openFiles` が `null` の場合はレイヤー2をスキップし、レイヤー3のみ実行する。

---

### 4.2 `DrlxLspDocumentService.openSiblings()`

**既存クラス:** `org.drools.drlx.lsp.server.DrlxLspDocumentService`

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
private Map<Path, String> openSiblings(Path documentPath) { … }
```

#### 内部実装

```
1. documentPath が null → 空マップを返す
2. docNorm = documentPath.toAbsolutePath().normalize()
3. dir    = docNorm.getParent()
4. result = new HashMap<>()

5. sourcesMap をイテレート:
     p = URI.create(e.getKey()) → Paths.get(p)（失敗したらスキップ）
     norm = p.toAbsolutePath().normalize()
     norm == docNorm → スキップ
     !norm.toString().endsWith(".drlx") → スキップ
     norm.getParent() != dir → スキップ
     result.put(norm, e.getValue())

6. return Collections.unmodifiableMap(result)
```

> **注意:** `openSiblings()` は今回の item では `forEachSiblingFile` への引数として渡す実際の呼び出し箇所がない（消費者は将来の item で追加）。メソッドとして定義するのみ。

---

## 5. 既存クラスへの変更

今回の item では以下のクラスは **変更しない**：

| クラス | 理由 |
|---|---|
| `DrlxReferencesHelper.findImportTypeReferences()` | import スコープはカレントファイルのみ |
| `DrlxRenameHelper` | import 型のリネームもカレントファイルのみ |
| `DrlxLintHelper.isTypeKnown()` | 兄弟ファイル import チェックは対象外 |
| `WorkspaceSiblingResolvers` | 変更なし（`forEachSiblingFile` の内部で使用する） |

---

## 6. テスト

### 6.1 `DrlxWorkspaceFileIndexTest`

**場所:** `drlx-completion/src/test/java/org/drools/drlx/completion/semantic/`

#### テストケース一覧

| テストメソッド | 内容 |
|---|---|
| `forEachSiblingFile_diskSibling` | `@TempDir` に兄弟ファイルを1つ作成。`openFiles` は空。`sink` がそのファイルの URI とテキストを受け取ることを確認。 |
| `forEachSiblingFile_excludesCurrentDocument` | カレントファイルと同じパスを `openFiles` に含めても sink に渡されないことを確認。 |
| `forEachSiblingFile_openBufferShadowsDisk` | ディスクに兄弟ファイルあり。同パスで `openFiles` にバッファを入れる。sink にはバッファのテキストのみが渡され（ディスク版は出ない）、計1回呼ばれることを確認。 |
| `forEachSiblingFile_nullPath_doesNothing` | `documentPath = null` → sink が1回も呼ばれないことを確認。 |
| `forEachSiblingFile_nullOpenFiles_readsDisk` | `openFiles = null` → ディスク上の兄弟ファイルは読まれることを確認。 |
| `forEachSiblingFile_multipleSiblings` | 兄弟ファイルが2つある場合、両方の URI とテキストが渡されることを確認（順序不問）。 |

#### テストフィクスチャのパターン

```java
@Test
void forEachSiblingFile_diskSibling(@TempDir Path dir) throws Exception {
    Path sibling  = dir.resolve("types.drlx");
    Path current  = dir.resolve("rules.drlx");
    Files.writeString(sibling, "// sibling content");

    List<String> uris  = new ArrayList<>();
    List<String> texts = new ArrayList<>();
    DrlxWorkspaceFileIndex.forEachSiblingFile(current, Map.of(), (uri, text) -> {
        uris.add(uri);
        texts.add(text);
    });

    assertThat(uris).containsExactly(sibling.toUri().toString());
    assertThat(texts).containsExactly("// sibling content");
}
```

---

## 7. 設計判断の記録

### なぜ `DeclaredType` レイヤーを持たないか

DRLX には `declare` ブロックが存在せず、#15 も SKIP である。型は Java クラスパスから来るため、`ClassIndex` がすでにカバーしている。`DRLWorkspaceTypeIndex` の `build()` / `buildLinkTargets()` / `docFor()` に相当するメソッドをスタブとして追加しても実体がなく、テストも書けない。将来 declare 対応が追加される場合はそのときに拡張する。

### なぜ `forEachSiblingType` を含めないか

`forEachSiblingType` は `DeclaredType` オブジェクトを渡すイテレータであり、`DeclaredTypeParser` なしには意味を持たない。`forEachSiblingFile`（ファイルテキストを渡す）の方が汎用性が高く、将来の消費者が自分でパース戦略を選べる。

### なぜ `WorkspaceSiblingResolvers` にメソッドを追加しないか

`WorkspaceSiblingResolvers` は「どのファイルが兄弟か」を解決する責務を持つ。「未保存バッファ優先でファイルテキストを走査する」のは別の責務（open-buffer shadowing ロジックを含む）であり、クラスを分けることが適切。

### `openSiblings` をライブラリ側でなくサーバー側に置く理由

`openSiblings` は `sourcesMap`（`Map<String, String>`、URI キー）を変換する操作であり、`sourcesMap` は LSP サーバー層の概念である。ライブラリ（`drlx-completion` モジュール）は LSP サーバーの内部状態に依存すべきでない。drools-lsp も同じ判断をしている（`DroolsLspDocumentService` 内にある）。

---

## 8. スコープ外

- `DrlxReferencesHelper` への `openFiles` パラメータ追加（import はカレントファイルスコープのため不要）
- `DrlxRenameHelper` のクロスファイル拡張（同上）
- `DrlxLintHelper` の兄弟ファイル import チェック拡張
- `DrlxDefinitionHelper` のクロスファイル定義ジャンプ（#18 JavaSourceLocator の範囲）
- `DrlxWorkspaceFileIndex` の実際の呼び出し配線（将来の item で追加）

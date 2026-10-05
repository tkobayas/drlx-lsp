# JavaSourceLocator — Spec (issue #11, item #18)

**Date:** 2026-10-05  
**Issue:** [#11](https://github.com/tkobayas/drlx-lsp/issues/11) Item #18  
**Status:** Draft

---

## 1. 背景と目的

drools-lsp には `JavaSourceLocator` という「FQCN からプロジェクト内の `.java` ソースファイルを見つける」ユーティリティが存在する。
Go-to-definition (#3) で型名をクリックしたときに、Maven 規約に基づいてソースファイルにジャンプする機能を提供する。

現在の drlx-lsp の `DrlxDefinitionHelper` は以下の2段階解決を行う：

1. **LHS binding 解決** — `$p`、`$addr` などのバインディング名 → 同ファイル内の宣言位置
2. **Import 解決** — 型名 → 同ファイル内の import 文

型名クリック時に `.java` ソースに直接ジャンプする機能がない。

本 item の目的は：
- `JavaSourceLocator` を drlx-lsp に移植し、FQCN → `.java` ソースのマッピングを提供する
- `DrlxDefinitionHelper` の解決順序を変更し、型名クリック時に `.java` ソースへ**直接ジャンプ**するようにする（import 行を経由しない）

---

## 2. 解決順序の設計

### 変更前（現在）

```
1. Binding 解決 → 同ファイル内のバインディング宣言へ
2. Import 解決 → 同ファイル内の import 文へ
3. (なし)
```

型名クリック → import 文に飛ぶ → import 文上でクリック → 何も起きない（自己参照ガード）

### 変更後

```
1. Binding 解決 → 同ファイル内のバインディング宣言へ
2. Java source 解決 → .java ソースファイルへ（直接ジャンプ）
3. Import fallback → .java ソースがない場合のみ import 文へ
```

型名クリック → `.java` ソースに直接飛ぶ（JAR のみのクラスの場合は import 行にフォールバック）

drools-lsp と同様の「直接ジャンプ」方式。drools-lsp では declare ブロック → 兄弟ファイル declare → Java source の順だが、DRLX には declare がないため、binding → Java source → import fallback となる。

---

## 3. 成果物一覧

| 成果物 | 種別 | 説明 |
|---|---|---|
| `JavaSourceLocator` | 新規クラス | FQCN → `.java` ソース `Location` のマッピングユーティリティ |
| `WorkspaceSemanticModel` | 既存クラス変更 | `classpathEntries` フィールド保持 + `buildOutputDirs()` メソッド追加 |
| `DrlxDefinitionHelper` | 既存クラス変更 | 解決順序変更 + `resolveFqcn()` + JavaSourceLocator 統合 |
| `JavaSourceLocatorTest` | 新規テスト | `locate()` の単体テスト |
| `DrlxDefinitionHelperTest` | 既存テスト変更 | Java source ジャンプのテスト追加 + 既存 importType テスト修正 |

---

## 4. クラス設計

### 4.1 `JavaSourceLocator`

**パッケージ:** `org.drools.drlx.completion`  
**可視性:** package-private（`DrlxDefinitionHelper` からのみ使用）

```java
/**
 * FQCN をプロジェクトの Maven ソースファイルにマッピングする。
 * classpath に {@code <module>/target/classes/pkg/Type.class} がある場合、
 * ソースは {@code <module>/src/main/java/pkg/Type.java}（存在時）。
 * JAR 由来のクラスは {@code null} を返す。
 */
final class JavaSourceLocator {

    private JavaSourceLocator() {}

    static final class Result {
        final Location location;
        final SymbolKind kind;  // Class, Interface, Enum
    }

    /**
     * @param fqcn            完全修飾クラス名
     * @param buildOutputDirs ビルド出力ディレクトリ（例: target/classes）
     * @return ソースの Location + SymbolKind、見つからなければ null
     */
    static Result locate(String fqcn, Set<Path> buildOutputDirs) { … }
}
```

#### アルゴリズム

```
1. ガード: fqcn が null/空、buildOutputDirs が null/空 → null
2. relClass = fqcn の "." を "/" に置換 + ".class"  (例: "org/example/Foo.class")
   relJava  = fqcn の "." を "/" に置換 + ".java"   (例: "org/example/Foo.java")
   simpleName = fqcn の最後の "." 以降              (例: "Foo")
3. 各 outputDir について:
   a. outputDir.resolve(relClass) が regular file でなければスキップ
   b. target = outputDir.getParent()         // <module>/target
      module = target.getParent()            // <module>
      module が null ならスキップ
   c. javaFile = module / "src/main/java" / relJava
      javaFile が regular file でなければスキップ
   d. readDeclaration(javaFile, simpleName) を返す
4. 見つからなければ null
```

#### `readDeclaration(Path javaFile, String simpleName)`

```
1. 正規表現: \b(class|interface|enum|record)\s+(<simpleName>)\b
2. ファイルを全行読み込み、正規表現にマッチする最初の行を探す
3. マッチした場合:
   - Range = 型名トークンの位置（行番号, 列番号）
   - SymbolKind = keyword から導出（"interface" → Interface, "enum" → Enum, その他 → Class）
   - Result(Location(javaFile URI, Range), SymbolKind) を返す
4. マッチしなかった場合（ソースはあるが宣言行が見つからない）:
   - フォールバック: Result(Location(javaFile URI, (0,0)-(0,0)), Class) を返す
```

---

### 4.2 `WorkspaceSemanticModel` の変更

**変更内容:**

```java
// 追加フィールド
private volatile Set<Path> classpathEntries = Set.of();

// rebuild() 内で保存
public void rebuild(ClasspathProvider classpathProvider, boolean resolved) {
    Set<Path> entries = classpathProvider.classpathEntries();
    this.classpathEntries = Set.copyOf(entries);  // 追加
    // ... 既存の処理
}

// 追加メソッド
public Set<Path> buildOutputDirs() {
    Set<Path> dirs = new LinkedHashSet<>();
    for (Path entry : classpathEntries) {
        if (Files.isDirectory(entry)) {
            dirs.add(entry);
        }
    }
    return Collections.unmodifiableSet(dirs);
}
```

`buildOutputDirs()` は classpath エントリのうちディレクトリ（JAR 以外）をフィルタして返す。
JavaSourceLocator はこのセットを受け取って Maven 規約マッピングを行う。

---

### 4.3 `DrlxDefinitionHelper` の変更

#### FQCN 解決

import 文から FQCN を解決する `resolveFqcn()` メソッドを追加：

```java
/**
 * word を import 宣言から FQCN に解決する。
 * 見つからなければ ClassIndex のユニーク候補を使用。
 * いずれもマッチしなければ null。
 */
private static String resolveFqcn(String word, ParseTree parseTree, WorkspaceSemanticModel model) {
    // 1. import 宣言を走査し、simple name が word に一致する FQCN を返す
    // 2. なければ model.classIndex().getBySimpleName(word) で候補が1件のみなら返す
    // 3. いずれもなければ null
}
```

#### definition() メソッドの変更

```java
public static List<Location> definition(String uri, String text, Position position,
                                         WorkspaceSemanticModel model) {
    // ... 既存の前処理（パース、トークン取得、word 抽出）

    // 1. Binding 解決（既存・変更なし）
    VisibleSymbols symbols = LhsBindingResolver.resolve(ctx);
    Optional<SymbolEntry> entry = symbols.lookupEntry(word);
    if (entry.isPresent() && entry.get().range() != null) {
        // 自己参照ガード + Location 返却（既存コード）
    }

    // 2. Java source 解決（新規）
    String fqcn = resolveFqcn(word, parseTree, model);
    if (fqcn != null) {
        JavaSourceLocator.Result result = JavaSourceLocator.locate(fqcn, model.buildOutputDirs());
        if (result != null) {
            return List.of(result.location);
        }
    }

    // 3. Import fallback（既存ロジック、Java source が見つからない場合のみ）
    List<Location> importDef = resolveImportDefinition(word, position, parseTree, uri);
    if (!importDef.isEmpty()) {
        return importDef;
    }

    return Collections.emptyList();
}
```

---

## 5. 既存テストへの影響

### `importType` テスト

現在のテスト: `Person` をルール本体でクリック → import 行に飛ぶ

変更後の動作: テストの `CurrentClassloaderProvider` による classpath はテスト実行環境の
classpath であり、`target/test-classes` を含む。ドメインクラス `Person` は
`src/test/java` に存在するが、JavaSourceLocator は `src/main/java` のみを探す。
したがって JavaSourceLocator は `null` を返し、**import fallback** が動作する。
既存テストは**変更なしで通る**。

### `cursorOnImportLine` テスト

同様に、JavaSourceLocator が `null` を返すため、import fallback の自己参照ガードが
動作し、空リストを返す。既存テストは**変更なしで通る**。

---

## 6. テスト

### 6.1 `JavaSourceLocatorTest`

**場所:** `drlx-completion/src/test/java/org/drools/drlx/completion/`

| テストメソッド | 内容 |
|---|---|
| `locate_findsJavaSource` | `@TempDir` に Maven レイアウト（`target/classes/org/example/Pet.class` + `src/main/java/org/example/Pet.java`）を作成。`locate()` が Pet.java の URI と宣言行位置を返すことを確認。 |
| `locate_findsDeclarationLine` | `.java` ファイルの `public class Pet {` 行を検出し、`Pet` トークンの正確な列位置を確認。 |
| `locate_interface` | `public interface Pet` → `SymbolKind.Interface` を返す。 |
| `locate_enum` | `public enum Pet` → `SymbolKind.Enum` を返す。 |
| `locate_noClassFile_returnsNull` | `.class` ファイルが存在しない場合 → `null`。 |
| `locate_noJavaSource_returnsNull` | `.class` はあるが `src/main/java` に `.java` がない場合 → `null`。 |
| `locate_nullFqcn_returnsNull` | `null` FQCN → `null`。 |
| `locate_emptyBuildOutputDirs_returnsNull` | 空セット → `null`。 |
| `locate_fallbackToLineZero` | `.java` ファイルは存在するが、型宣言行がマッチしない場合 → `(0,0)` にフォールバック。 |

### 6.2 `DrlxDefinitionHelperTest` への追加

| テストメソッド | 内容 |
|---|---|
| `javaSourceDefinition_directJump` | `@TempDir` に Maven レイアウトを作成。ルール本体の型名クリックが `.java` ソースに直接飛ぶことを確認（import 行ではない）。 |
| `javaSourceDefinition_fromImportLine` | import 行上の型名クリックも `.java` ソースに飛ぶことを確認。 |
| `importFallback_whenNoJavaSource` | `.java` ソースがない場合、import 行にフォールバックすることを確認。 |

### テストフィクスチャのパターン

```java
@Test
void javaSourceDefinition_directJump(@TempDir Path module) throws Exception {
    // Maven layout
    Path classes = module.resolve("target/classes/org/example");
    Files.createDirectories(classes);
    Files.createFile(classes.resolve("Pet.class"));
    Path srcDir = module.resolve("src/main/java/org/example");
    Files.createDirectories(srcDir);
    Path petJava = srcDir.resolve("Pet.java");
    Files.writeString(petJava, "package org.example;\npublic class Pet {\n}\n");

    // buildOutputDirs を含む WorkspaceSemanticModel を構築
    // ...

    String text = """
            import org.example.Pet;
            unit MyUnit;
            rule R1 {
                var p : /persons[pet : Pet],
                do { p }
            }
            """;

    // "Pet" をクリック → Pet.java にジャンプ
    List<Location> defs = DrlxDefinitionHelper.definition(URI, text, position, model);
    assertThat(defs).hasSize(1);
    assertThat(defs.get(0).getUri()).isEqualTo(petJava.toUri().toString());
}
```

> **注意:** `DrlxDefinitionHelperTest` は現在 `CurrentClassloaderProvider` で model を構築しているが、
> Java source テストでは `@TempDir` の `target/classes` を `buildOutputDirs` に含める model が必要。
> テスト用に `ClasspathProvider` を差し替えるか、`WorkspaceSemanticModel` に
> `buildOutputDirs` を追加設定するメソッドが必要になる可能性がある。
> 実装時にテストの書きやすさを考慮して判断する。

---

## 7. 設計判断の記録

### なぜ static ユーティリティクラスか

drools-lsp と同じ設計。`JavaSourceLocator` は状態を持たず、`locate()` の引数として
`fqcn` と `buildOutputDirs` を受け取るだけの純粋関数。インスタンス化する理由がない。

### なぜ `buildOutputDirs` を `WorkspaceSemanticModel` に持たせるか

`WorkspaceSemanticModel` は既に classpath エントリから `ClassIndex`、`ClassMemberIndex`、
`TypeSolver` を構築している。`buildOutputDirs` もクラスパスの派生データであり、
同じライフサイクルで管理するのが自然。drools-lsp では `DroolsLspServer` に別フィールドとして
持たせているが、drlx-lsp では `definition()` のシグネチャが既に `WorkspaceSemanticModel` を
受け取っているため、ここに含める方がシンプル。

### なぜ import fallback を残すか

JAR のみのクラス（プロジェクト内に `.java` ソースがない）の場合、
JavaSourceLocator は `null` を返す。その場合でも import 行へのジャンプは
「何もない」よりはユーザーにとって有用。drools-lsp にはこの fallback はないが、
drlx-lsp 固有の改善として残す。

### なぜ `src/main/java` のみを対象とし `src/test/java` を含めないか

drools-lsp と同一の方針。テストクラスへのジャンプはユースケースとして稀であり、
`target/classes` と `target/test-classes` の区別も必要になる。
必要に応じて将来拡張可能だが、初期スコープでは Maven メインソースのみ。

---

## 8. スコープ外

- `src/test/java` へのソースマッピング
- Gradle 規約（`build/classes/java/main`）のサポート
- JAR 内ソースの展開（`-sources.jar` の利用）
- `DrlxWorkspaceFileIndex` との連携（#18 ではファイル走査は不要）
- TypeHierarchy での `JavaSourceLocator.Result.kind` の利用（将来の item）

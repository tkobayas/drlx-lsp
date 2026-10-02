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

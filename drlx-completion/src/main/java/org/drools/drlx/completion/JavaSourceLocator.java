package org.drools.drlx.completion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FQCN をプロジェクトの Maven ソースファイルにマッピングする。
 * classpath に {@code <module>/target/classes/pkg/Type.class} がある場合、
 * ソースは {@code <module>/src/main/java/pkg/Type.java}（存在時）。
 * JAR 由来のクラスは {@code null} を返す。
 */
final class JavaSourceLocator {

    private static final Logger logger = LoggerFactory.getLogger(JavaSourceLocator.class);

    private static final String TYPE_DECL_TEMPLATE = "\\b(class|interface|enum|record)\\s+(%s)\\b";

    private JavaSourceLocator() {
    }

    static final class Result {
        final Location location;
        final SymbolKind kind;

        Result(Location location, SymbolKind kind) {
            this.location = location;
            this.kind = kind;
        }
    }

    /**
     * @param fqcn            完全修飾クラス名
     * @param buildOutputDirs ビルド出力ディレクトリ（例: target/classes）
     * @return ソースの Location + SymbolKind、見つからなければ null
     */
    static Result locate(String fqcn, Set<Path> buildOutputDirs) {
        if (fqcn == null || fqcn.isEmpty() || buildOutputDirs == null || buildOutputDirs.isEmpty()) {
            return null;
        }
        String relClass = fqcn.replace('.', '/') + ".class";
        String relJava = fqcn.replace('.', '/') + ".java";
        String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);

        for (Path outputDir : buildOutputDirs) {
            if (!Files.isRegularFile(outputDir.resolve(relClass))) {
                continue;
            }
            Path target = outputDir.getParent();
            Path module = target == null ? null : target.getParent();
            if (module == null) {
                continue;
            }
            Path javaFile = module.resolve("src/main/java").resolve(relJava);
            if (!Files.isRegularFile(javaFile)) {
                continue;
            }
            return readDeclaration(javaFile, simpleName);
        }
        return null;
    }

    private static Result readDeclaration(Path javaFile, String simpleName) {
        Pattern decl = Pattern.compile(String.format(TYPE_DECL_TEMPLATE, Pattern.quote(simpleName)));
        String uri = javaFile.toUri().toString();
        try {
            List<String> lines = Files.readAllLines(javaFile);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = decl.matcher(lines.get(i));
                if (m.find()) {
                    Range range = new Range(new Position(i, m.start(2)),
                                            new Position(i, m.start(2) + simpleName.length()));
                    return new Result(new Location(uri, range), kindOf(m.group(1)));
                }
            }
        } catch (Exception e) {
            logger.debug("Could not locate declaration in {}: {}", javaFile, e.getMessage());
        }
        return new Result(new Location(uri, new Range(new Position(0, 0), new Position(0, 0))),
                          SymbolKind.Class);
    }

    private static SymbolKind kindOf(String keyword) {
        switch (keyword) {
            case "interface":
                return SymbolKind.Interface;
            case "enum":
                return SymbolKind.Enum;
            default:
                return SymbolKind.Class;
        }
    }
}

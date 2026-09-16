package org.drools.drlx.completion;

import java.util.List;

import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class DrlxFoldingRangeHelperTest {

    @Test
    void emptyOrNullYieldsNoRanges() {
        assertThat(DrlxFoldingRangeHelper.foldingRanges(null)).isEmpty();
        assertThat(DrlxFoldingRangeHelper.foldingRanges("")).isEmpty();
        assertThat(DrlxFoldingRangeHelper.foldingRanges("   \n  \n")).isEmpty();
    }

    @Test
    void foldsRuleBlocks() {
        String drlx =
                "unit MyUnit;\n"                    // 0
                + "rule R1 {\n"                     // 1
                + "    var p : /persons,\n"         // 2
                + "    do {\n"                      // 3
                + "        System.out.println(p);\n"// 4
                + "    }\n"                         // 5
                + "}\n";                            // 6

        List<FoldingRange> ranges = DrlxFoldingRangeHelper.foldingRanges(drlx);

        assertThat(ranges)
                .extracting(FoldingRange::getStartLine, FoldingRange::getEndLine, FoldingRange::getKind)
                .contains(tuple(1, 6, FoldingRangeKind.Region));
    }

    @Test
    void foldsMultiLineImports() {
        String drlx =
                "import org.example.Person;\n"      // 0
                + "import org.example.Address;\n"   // 1
                + "import org.example.Order;\n"     // 2
                + "unit MyUnit;\n"                  // 3
                + "rule R1 {\n"                     // 4
                + "    var p : /persons,\n"         // 5
                + "    do {}\n"                     // 6
                + "}\n";                            // 7

        List<FoldingRange> ranges = DrlxFoldingRangeHelper.foldingRanges(drlx);

        assertThat(ranges)
                .extracting(FoldingRange::getStartLine, FoldingRange::getEndLine, FoldingRange::getKind)
                .contains(
                        tuple(0, 2, FoldingRangeKind.Imports),
                        tuple(4, 7, FoldingRangeKind.Region));
    }

    @Test
    void singleImportProducesNoImportsFold() {
        String drlx =
                "import org.example.Person;\n"      // 0
                + "unit MyUnit;\n"                  // 1
                + "rule R1 {\n"                     // 2
                + "    var p : /persons,\n"         // 3
                + "    do {}\n"                     // 4
                + "}\n";                            // 5

        List<FoldingRange> ranges = DrlxFoldingRangeHelper.foldingRanges(drlx);

        assertThat(ranges)
                .extracting(FoldingRange::getStartLine, FoldingRange::getEndLine, FoldingRange::getKind)
                .containsExactly(tuple(2, 5, FoldingRangeKind.Region));
    }

    @Test
    void foldsBlockComments() {
        String drlx =
                "/*\n"                              // 0
                + " * Multi-line header comment\n"  // 1
                + " */\n"                           // 2
                + "import org.example.Person;\n"    // 3
                + "unit MyUnit;\n"                  // 4
                + "rule R1 {\n"                     // 5
                + "    /* inline block\n"           // 6
                + "       comment */\n"             // 7
                + "    var p : /persons,\n"         // 8
                + "    do {}\n"                     // 9
                + "}\n";                            // 10

        List<FoldingRange> ranges = DrlxFoldingRangeHelper.foldingRanges(drlx);

        assertThat(ranges)
                .extracting(FoldingRange::getStartLine, FoldingRange::getEndLine, FoldingRange::getKind)
                .contains(
                        tuple(0, 2, FoldingRangeKind.Comment),
                        tuple(6, 7, FoldingRangeKind.Comment),
                        tuple(5, 10, FoldingRangeKind.Region));
    }

    @Test
    void foldsContiguousLineComments() {
        String drlx =
                "// Line comment 1\n"               // 0
                + "// Line comment 2\n"             // 1
                + "// Line comment 3\n"             // 2
                + "import org.example.Person;\n"    // 3
                + "unit MyUnit;\n"                  // 4
                + "rule R1 {\n"                     // 5
                + "    // isolated single line\n"   // 6
                + "    var p : /persons,\n"         // 7
                + "    do {}\n"                     // 8
                + "}\n";                            // 9

        List<FoldingRange> ranges = DrlxFoldingRangeHelper.foldingRanges(drlx);

        assertThat(ranges)
                .extracting(FoldingRange::getStartLine, FoldingRange::getEndLine, FoldingRange::getKind)
                .contains(
                        tuple(0, 2, FoldingRangeKind.Comment),
                        tuple(5, 9, FoldingRangeKind.Region))
                .doesNotContain(tuple(6, 6, FoldingRangeKind.Comment));
    }

    @Test
    void javaCompilationUnitProducesNoRanges() {
        String javaCode =
                "package org.example;\n"
                + "public class Person {\n"
                + "    private String name;\n"
                + "}\n";

        assertThat(DrlxFoldingRangeHelper.foldingRanges(javaCode)).isEmpty();
    }
}

package org.drools.drlx.completion;

import java.util.List;
import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class DrlxInlayHintHelperTest {

    private final WorkspaceSemanticModel model =
            new WorkspaceSemanticModel(new CurrentClassloaderProvider());

    @Test
    void testOopathVarAndConstraintHints() {
        String drlx = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons[ a : age, n : name ],
                    do {}
                }
                """;
        List<InlayHint> hints = DrlxInlayHintHelper.inlayHints(drlx, null, model);

        assertThat(hints)
                .extracting(
                        h -> h.getPosition().getLine(),
                        h -> h.getPosition().getCharacter(),
                        h -> h.getLabel().getLeft(),
                        InlayHint::getKind)
                .containsExactlyInAnyOrder(
                        tuple(6, 9, ": Person", InlayHintKind.Type),
                        tuple(6, 23, ": int", InlayHintKind.Type),
                        tuple(6, 32, ": String", InlayHintKind.Type)
                );
    }

    @Test
    void testAccumulateVarHint() {
        String drlx = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var cnt = count(/persons),
                    do {}
                }
                """;
        List<InlayHint> hints = DrlxInlayHintHelper.inlayHints(drlx, null, model);

        assertThat(hints)
                .extracting(
                        h -> h.getPosition().getLine(),
                        h -> h.getPosition().getCharacter(),
                        h -> h.getLabel().getLeft())
                .containsExactly(tuple(6, 11, ": Long"));
    }

    @Test
    void testConsequenceVarHint() {
        String drlx = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    do {
                        var msg = "hello";
                    }
                }
                """;
        List<InlayHint> hints = DrlxInlayHintHelper.inlayHints(drlx, null, model);

        assertThat(hints)
                .extracting(
                        h -> h.getPosition().getLine(),
                        h -> h.getPosition().getCharacter(),
                        h -> h.getLabel().getLeft())
                .containsExactly(tuple(7, 15, ": String"));
    }

    @Test
    void testExplicitTypeHasNoHint() {
        String drlx = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    Person p : /persons,
                    do {
                        String s = "world";
                    }
                }
                """;
        List<InlayHint> hints = DrlxInlayHintHelper.inlayHints(drlx, null, model);
        assertThat(hints).isEmpty();
    }

    @Test
    void testRangeFiltering() {
        String drlx = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do {}
                }

                rule R2 {
                    var q : /persons,
                    do {}
                }
                """;
        // Request range only covering R1 (lines 5 to 9)
        Range range = new Range(new Position(5, 0), new Position(9, 0));
        List<InlayHint> hints = DrlxInlayHintHelper.inlayHints(drlx, range, model);

        assertThat(hints)
                .extracting(h -> h.getPosition().getLine())
                .containsExactly(6);
    }
}

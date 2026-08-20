package org.drools.drlx.completion;

import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxHoverHelperTest {

    private final WorkspaceSemanticModel model =
            new WorkspaceSemanticModel(new CurrentClassloaderProvider());

    private static String content(Hover hover) {
        assertThat(hover).isNotNull();
        return hover.getContents().getRight().getValue();
    }

    @Test
    void hoverOnOopathBinding() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p }
                }
                """;
        // "p" in "do { p }" — line 7 after stripping, char 9
        Hover hover = DrlxHoverHelper.hover(text, new Position(7, 9), model);

        String md = content(hover);
        assertThat(md).contains("Person");
        assertThat(md).contains("name");
        assertThat(md).contains("age");
        assertThat(md).contains("address");
    }

    @Test
    void hoverOnRuleParameter() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1(Person p) {
                    do { System.out.println(p); }
                }
                """;
        // "p" in "println(p)" — line 6, char 28
        Hover hover = DrlxHoverHelper.hover(text, new Position(6, 28), model);

        String md = content(hover);
        assertThat(md).contains("Person");
        assertThat(md).contains("name");
    }

    @Test
    void hoverOnUnknownSymbolReturnsNull() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { unknown }
                }
                """;
        // "unknown" — line 7, char 9
        Hover hover = DrlxHoverHelper.hover(text, new Position(7, 9), model);
        assertThat(hover).isNull();
    }

    @Test
    void hoverOnKeywordReturnsNull() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;
                rule R1 {
                    var p : /persons,
                    do { }
                }
                """;
        // "rule" keyword — line 2, char 0
        Hover hover = DrlxHoverHelper.hover(text, new Position(2, 0), model);
        assertThat(hover).isNull();
    }

    @Test
    void hoverOnDotAccessMember() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p.address }
                }
                """;
        // "address" in "p.address" — line 8, char 11
        Hover hover = DrlxHoverHelper.hover(text, new Position(8, 11), model);

        String md = content(hover);
        assertThat(md).contains("address");
        assertThat(md).contains("Address");
        assertThat(md).contains("Field of");
        assertThat(md).contains("Person");
    }

    @Test
    void hoverOnChainedDotAccess() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p.address.city }
                }
                """;
        // "city" in "p.address.city" — line 8, char 19
        Hover hover = DrlxHoverHelper.hover(text, new Position(8, 19), model);

        String md = content(hover);
        assertThat(md).contains("city");
        assertThat(md).contains("String");
        assertThat(md).contains("Field of");
        assertThat(md).contains("Address");
    }

    @Test
    void hoverOnConstraintBinding() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons[addr : address],
                    do { addr }
                }
                """;
        // "addr" in "do { addr }" — line 8, char 9
        Hover hover = DrlxHoverHelper.hover(text, new Position(8, 9), model);

        String md = content(hover);
        assertThat(md).contains("Address");
    }

    @Test
    void hoverOnRhsLocal() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do {
                        Address x = p.getAddress();
                        x
                    }
                }
                """;
        // "x" on its own line — line 10, char 8
        Hover hover = DrlxHoverHelper.hover(text, new Position(10, 8), model);

        String md = content(hover);
        assertThat(md).contains("Address");
    }

    @Test
    void hoverOnImportTypeName() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { }
                }
                """;
        // "Person" in import — line 0, char 30
        Hover hover = DrlxHoverHelper.hover(text, new Position(0, 30), model);

        String md = content(hover);
        assertThat(md).contains("org.drools.drlx.domain.Person");
        assertThat(md).contains("name");
        assertThat(md).contains("age");
    }

    @Test
    void nullTextReturnsNull() {
        assertThat(DrlxHoverHelper.hover(null, new Position(0, 0), model)).isNull();
    }

    @Test
    void nullPositionReturnsNull() {
        assertThat(DrlxHoverHelper.hover("rule R1 {}", null, model)).isNull();
    }
}

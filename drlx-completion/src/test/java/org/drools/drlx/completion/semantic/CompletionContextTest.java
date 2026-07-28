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

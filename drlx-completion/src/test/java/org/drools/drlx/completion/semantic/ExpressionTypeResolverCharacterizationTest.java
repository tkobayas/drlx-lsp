package org.drools.drlx.completion.semantic;

import java.util.Optional;

import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mvel3.parser.MvelParser;
import org.mvel3.transpiler.MVELTranspiler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests for ExpressionTypeResolver.
 * Each test resolves the type of the scope expression at a DOT_ACCESS caret position.
 *
 * Tests marked @Disabled require VisibleSymbols (#7), Maven classpath (#6),
 * or are improvement targets (#8).
 */
class ExpressionTypeResolverCharacterizationTest {

    private final WorkspaceSemanticModel model =
            new WorkspaceSemanticModel(new CurrentClassloaderProvider());
    private final ExpressionTypeResolver resolver = new SentinelExpressionTypeResolver();

    @BeforeAll
    static void setup() {
        MvelParser.Factory.USE_ANTLR = true;
        MVELTranspiler.ENABLE_REWRITE = false;
    }

    // --- Cases that pass with SentinelExpressionTypeResolver ---

    @Test
    void systemDot() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.
                """;
        assertResolvesTo(text, 4, 16, "java.lang.System");
    }

    @Test
    void systemOutDot() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.out.
                """;
        assertResolvesTo(text, 4, 20, "java.io.PrintStream");
    }

    @Test
    void bigDecimalLiteral() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { 10.5B.
                """;
        assertResolvesTo(text, 4, 15, "java.math.BigDecimal");
    }

    @Test
    @Disabled("Requires VisibleSymbols to declare 'p' as Person — see #7")
    void rhsLocalPropertyChain() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        Person p = new Person("John", new Address("Tokyo"));
                        p.address.
                """;
        assertResolvesTo(text, 9, 18, "org.drools.drlx.domain.Address");
    }

    @Test
    @Disabled("Requires VisibleSymbols to declare 'list' — see #7")
    void inlineCastSimple() {
        String text = """
                import java.util.ArrayList;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { list#ArrayList#.
                """;
        assertResolvesTo(text, 6, 25, "java.util.ArrayList");
    }

    @Test
    void brokenCodeAfterCaret() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.
                    invalid broken {{ code
                """;
        assertResolvesTo(text, 4, 16, "java.lang.System");
    }

    @Test
    void resolverWithVisibleSymbolsResolvesVariable() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { p.address.
                """;

        ResolvedReferenceTypeDeclaration personDecl =
                model.typeSolver().solveType("org.drools.drlx.domain.Person");
        SemanticType personType = SemanticType.value(new ReferenceTypeImpl(personDecl));

        VisibleSymbols symbols = new VisibleSymbols.Builder()
                .add("p", personType)
                .build();

        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        ParseTree tree = parser.drlxStart();
        int caretTokenIndex = computeTokenIndex(parser, 7 + 1, 19);
        CompletionExpression expr = CompletionExpression.fromCaretPosition(parser, tree, caretTokenIndex);

        Optional<SemanticType> result = resolver.resolve(expr, symbols, model);
        assertThat(result).isPresent();
        assertThat(result.get().resolvedType().describe()).isEqualTo("org.drools.drlx.domain.Address");
    }

    // --- Improvement targets — require VisibleSymbols (#7) ---

    @Test
    @Disabled("Requires VisibleSymbols to declare 'list' as List — see #7")
    void methodReturnType() {
        String text = """
                import java.util.List;
                import java.util.ArrayList;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        List list = new ArrayList();
                        list.get(0).
                """;
        assertResolvesTo(text, 9, 20, "java.lang.Object");
    }

    @Test
    @Disabled("Requires VisibleSymbols to declare 'p' as Person + null-safe normalization — see #7")
    void nullSafeAccess() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        Person p = new Person("John", new Address("Tokyo"));
                        p!.address!.
                """;
        assertResolvesTo(text, 9, 20, "org.drools.drlx.domain.Address");
    }

    @Test
    @Disabled("Requires VisibleSymbols to declare 'arr' as String[] — see #7")
    void arrayIndexedAccess() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        String[] arr = new String[]{"a","b"};
                        arr[0].
                """;
        assertResolvesTo(text, 6, 15, "java.lang.String");
    }

    @Test
    void caretInMiddleOfDocument() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.out.
                }

                rule R2 {
                    var b : /bs,
                    do { System.out.println("hello"); }
                }
                """;
        assertResolvesTo(text, 4, 20, "java.io.PrintStream");
    }

    @Test
    void inlineCastQualifiedType() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { list#java.util.ArrayList#.
                """;
        assertResolvesTo(text, 4, 35, "java.util.ArrayList");
    }

    // --- @Disabled: requires VisibleSymbols (#7) or Maven classpath (#6) ---

    @Test
    @Disabled("Requires unit class resolution — see #7 ENTRY_POINT")
    void entryPointTypeInference() {
        // var p : /persons with DataSource<Person> persons in unit class
    }

    @Test
    @Disabled("Requires VisibleSymbols — see #7")
    void bindingsFromEarlierPatterns() {
        // var p : /persons, p. should offer Person members
    }

    @Test
    @Disabled("Requires VisibleSymbols — see #7")
    void noLeakageFromLaterPatterns() {
        // binding from rule R2 should not appear in rule R1
    }

    @Test
    @Disabled("Requires VisibleSymbols — see #7")
    void shadowedLocalVariables() {
        // inner scope shadows outer
    }

    @Test
    @Disabled("Requires OOPath traversal — see #7 OOPATH_CHUNK/CONSTRAINT_EXPRESSION")
    void nestedOopathChunkConstraint() {
        // /persons/address[city.] — city belongs to Address, not Person
    }

    @Test
    @Disabled("Requires Maven classpath — see #6")
    void realMavenWorkspaceClasses() {
        // resolve a class from a Maven dependency not on the LSP classpath
    }

    // --- Helper methods ---

    private void assertResolvesTo(String text, int line, int col, String expectedFqcn) {
        Optional<SemanticType> result = resolveAt(text, line, col);
        assertThat(result)
                .as("Expected type %s at line %d col %d", expectedFqcn, line, col)
                .isPresent();
        assertThat(result.get().resolvedType().describe()).isEqualTo(expectedFqcn);
    }

    private Optional<SemanticType> resolveAt(String text, int line, int col) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        ParseTree tree = parser.drlxStart();

        int caretTokenIndex = computeTokenIndex(parser, line + 1, col);
        CompletionExpression expr = CompletionExpression.fromCaretPosition(parser, tree, caretTokenIndex);

        return resolver.resolve(expr, VisibleSymbols.empty(), model);
    }

    private int computeTokenIndex(DrlxParser parser, int row, int col) {
        CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();
        int tokenIndex = 0;
        for (Token token : tokens.getTokens()) {
            if (token.getLine() > row || (token.getLine() == row && token.getCharPositionInLine() >= col)) {
                break;
            }
            tokenIndex++;
        }
        return tokenIndex;
    }
}

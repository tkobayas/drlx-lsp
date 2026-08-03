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
    void rhsLocalPropertyChain() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        Person p = new Person("John", 0, new Address("Tokyo"));
                        p.address.
                """;
        assertResolvesToWithSymbols(text, 9, 18, "org.drools.drlx.domain.Address");
    }

    @Test
    void inlineCastSimple() {
        String text = """
                import java.util.ArrayList;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        Object list = new Object();
                        list#ArrayList#.
                """;
        assertResolvesToWithSymbols(text, 8, 25, "java.util.ArrayList");
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
        assertResolvesToWithSymbols(text, 9, 20, "java.lang.Object");
    }

    @Test
    void nullSafeAccess() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        Person p = new Person("John", 0, new Address("Tokyo"));
                        p!.address!.
                """;
        assertResolvesToWithSymbols(text, 9, 20, "org.drools.drlx.domain.Address");
    }

    @Test
    void arrayIndexedAccess() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        String[] arr = new String[]{"a","b"};
                        arr[0].
                """;
        assertResolvesToWithSymbols(text, 6, 15, "java.lang.String");
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
    void entryPointTypeInference() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p.
                """;
        assertResolvesToWithSymbols(text, 7, 11, "org.drools.drlx.domain.Person");
    }

    @Test
    void bindingsFromEarlierPatterns() {
        String text = """
                import org.drools.drlx.domain.Person;

                unit MyUnit;

                rule R1 {
                    Person p : /persons,
                    do { p.
                """;
        assertResolvesToWithSymbols(text, 6, 11, "org.drools.drlx.domain.Person");
    }

    @Test
    void noLeakageFromLaterPatterns() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;

                unit MyUnit;

                rule R1 {
                    Person p : /persons,
                    do { p.
                }

                rule R2 {
                    Address a : /addresses,
                    do { a.getCity(); }
                }
                """;
        Optional<SemanticType> result = resolveAtWithVisibleSymbols(text, 7, 11);
        assertThat(result).isPresent();
        assertThat(result.get().resolvedType().describe()).isEqualTo("org.drools.drlx.domain.Person");
    }

    @Test
    void shadowedLocalVariables() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;

                unit MyUnit;

                rule R1 {
                    Person p : /persons,
                    do {
                        Address p = new Address();
                        p.
                """;
        assertResolvesToWithSymbols(text, 9, 10, "org.drools.drlx.domain.Address");
    }

    @Test
    void nestedOopathChunkConstraint() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons/address[city.
                """;
        assertResolvesToWithSymbols(text, 7, 37, "java.lang.String");
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

    private void assertResolvesToWithSymbols(String text, int line, int col, String expectedFqcn) {
        Optional<SemanticType> result = resolveAtWithVisibleSymbols(text, line, col);
        assertThat(result)
                .as("Expected type %s at line %d col %d", expectedFqcn, line, col)
                .isPresent();
        assertThat(result.get().resolvedType().describe()).isEqualTo(expectedFqcn);
    }

    private Optional<SemanticType> resolveAtWithVisibleSymbols(String text, int line, int col) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);
        ParseTree tree = parser.drlxStart();

        int caretTokenIndex = computeTokenIndex(parser, line + 1, col);
        CompletionContext ctx = model.createContext(parser, tree, caretTokenIndex);
        VisibleSymbols symbols = ctx.buildVisibleSymbols();
        CompletionExpression expr = CompletionExpression.fromCaretPosition(parser, tree, caretTokenIndex);

        return resolver.resolve(expr, symbols, model);
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

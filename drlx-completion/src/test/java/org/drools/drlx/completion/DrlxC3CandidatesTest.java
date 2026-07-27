package org.drools.drlx.completion;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.vmware.antlr4c3.CodeCompletionCore;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.drools.drlx.completion.CompletionSite.*;

/**
 * Characterization tests for antlr4-c3 behavior.
 *
 * Records candidates.tokens, candidates.rules, and rule call stacks at many
 * caret positions. These tests document what C3 produces today — changes are
 * expected when the grammar evolves or PREFERRED_RULES changes.
 */
class DrlxC3CandidatesTest {

    private static final Set<Integer> PREFERRED_RULES = Set.of(DrlxParser.RULE_identifier);

    static final String TEXT = """
            unit MyUnit;

            rule R1 {
                Person p1 : /persons[age > 18],
                var a : /persons/address[city == "Tokyo"],
                not /orders,
                exists /orders,
                test p1.age > 18,
                do {
                    System.out.println(p1);
                }
            }

            rule R2(String name) {
                var p : /persons[name == name],
                do { System.out.println(p); }
            }
            """;

    // --- Helpers ---

    record CandidateResult(CodeCompletionCore.CandidatesCollection candidates, DrlxParser parser, int tokenIndex) {}

    private static CandidateResult collectAt(String text, int line, int col) {
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokenStream);
        parser.drlxStart();

        int row = line + 1; // ANTLR uses 1-based lines
        int tokenIndex = 0;
        for (Token token : tokenStream.getTokens()) {
            if (token.getLine() > row || (token.getLine() == row && token.getCharPositionInLine() >= col)) {
                break;
            }
            tokenIndex++;
        }

        CodeCompletionCore core = new CodeCompletionCore(parser, PREFERRED_RULES, Tokens.IGNORED);
        CodeCompletionCore.CandidatesCollection candidates = core.collectCandidates(tokenIndex, null);
        return new CandidateResult(candidates, parser, tokenIndex);
    }

    private static Set<String> tokenNames(CandidateResult r) {
        return r.candidates.tokens.keySet().stream()
                .filter(Objects::nonNull)
                .map(i -> r.parser.getVocabulary().getDisplayName(i).replace("'", "").toLowerCase())
                .collect(Collectors.toSet());
    }

    private static boolean hasRule(CandidateResult r, int ruleIndex) {
        return r.candidates.rules.containsKey(ruleIndex);
    }

    private static CompletionSite siteAt(CandidateResult r) {
        return CompletionContextAnalyzer.analyze(r.candidates(), r.parser(), r.tokenIndex());
    }

    private static List<String> ruleCallStack(CandidateResult r, int ruleIndex) {
        List<Integer> stack = r.candidates.rules.get(ruleIndex);
        if (stack == null) {
            return List.of();
        }
        return stack.stream()
                .map(i -> DrlxParser.ruleNames[i])
                .toList();
    }

    // --- Compilation unit level ---

    @Test
    void compilationUnitStart() {
        CandidateResult r = collectAt(TEXT, 0, 0);
        assertThat(tokenNames(r)).contains("package", "import", "unit", "class");
        assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
        assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
                .contains("compilationUnit");
        assertThat(siteAt(r)).isEqualTo(COMPILATION_UNIT);
    }

    @Test
    void beforeRule() {
        CandidateResult r = collectAt(TEXT, 2, 0);
        assertThat(tokenNames(r)).contains("rule", "window");
        assertThat(tokenNames(r)).doesNotContain("not", "exists", "do");
        assertThat(siteAt(r)).isEqualTo(RULE_DECLARATION);
    }

    @Test
    void afterRuleKeyword() {
        CandidateResult r = collectAt(TEXT, 2, 5);
        assertThat(tokenNames(r)).isEmpty();
        assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
        assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
                .contains("ruleDeclaration");
        assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
                .doesNotContain("ruleBody");
        assertThat(siteAt(r)).isEqualTo(RULE_DECLARATION);
    }

    // --- Rule item positions ---

    @Test
    void boundType() {
        // At 'Person' in 'Person p1 : /persons[...],'
        CandidateResult r = collectAt(TEXT, 3, 4);
        assertThat(tokenNames(r)).contains("not", "exists", "and", "or", "test", "if", "match", "do", "var");
        assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
        assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
                .contains("ruleItem", "boundOopath");
        assertThat(siteAt(r)).isEqualTo(RULE_ITEM);
    }

    @Test
    void bindName() {
        // At 'p1' in 'Person p1 : /persons[...],'
        CandidateResult r = collectAt(TEXT, 3, 11);
        assertThat(tokenNames(r)).isEmpty();
        assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
        assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
                .contains("boundOopath");
        // C3 call stack includes ruleItem because boundOopath is reached through it;
        // the analyzer classifies this as RULE_ITEM rather than BIND_NAME
        assertThat(siteAt(r)).isEqualTo(RULE_ITEM);
    }

    @Test
    void afterSlash() {
        // At 'persons' after '/' in '/persons[...]'
        CandidateResult r = collectAt(TEXT, 3, 17);
        assertThat(tokenNames(r)).isEmpty();
        assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
        assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
                .contains("oopathRoot");
        assertThat(siteAt(r)).isEqualTo(ENTRY_POINT);
    }

    @Test
    void constraintExpression() {
        // At 'age' inside '/persons[age > 18]' — expression inside constraint bracket
        CandidateResult r = collectAt(TEXT, 3, 25);
        assertThat(tokenNames(r)).contains("this", "super", "new", "var");
        assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
        assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
                .contains("drlxExpression");
        assertThat(siteAt(r)).isEqualTo(CONSTRAINT_EXPRESSION);
    }

    @Test
    void oopathChunkIdentifier() {
        // At 'address' in '/persons/address[...]'
        CandidateResult r = collectAt(TEXT, 4, 21);
        assertThat(tokenNames(r)).isEmpty();
        assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
        assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
                .contains("oopathChunk");
        assertThat(siteAt(r)).isEqualTo(OOPATH_CHUNK);
    }

    @Test
    void constraintChunkExpression() {
        // At 'city' in '/address[city == "Tokyo"]'
        CandidateResult r = collectAt(TEXT, 4, 29);
        assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
        assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
                .contains("drlxExpression");
        assertThat(siteAt(r)).isEqualTo(CONSTRAINT_EXPRESSION);
    }

    @Test
    void ruleItemStart_beforeNot() {
        // At 'not' — rule item start where CE keywords should appear
        CandidateResult r = collectAt(TEXT, 5, 4);
        assertThat(tokenNames(r)).contains("not", "exists", "and", "or", "test", "if", "match", "do", "var");
        assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
        assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
                .contains("ruleItem", "boundOopath");
        assertThat(siteAt(r)).isEqualTo(RULE_ITEM);
    }

    @Test
    void ruleItemStart_beforeExists() {
        // At 'exists' — same position type as before 'not'
        CandidateResult r = collectAt(TEXT, 6, 4);
        assertThat(tokenNames(r)).contains("not", "exists", "do");
        assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
        assertThat(siteAt(r)).isEqualTo(RULE_ITEM);
    }

    // --- Test element ---

    @Test
    void testElementExpression() {
        // At 'p1' after 'test ' — expression context
        CandidateResult r = collectAt(TEXT, 7, 9);
        assertThat(tokenNames(r)).doesNotContain("rule", "window");
        assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
        assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
                .contains("testElement", "expression", "primary");
        assertThat(siteAt(r)).isEqualTo(TEST_EXPRESSION);
    }

    // --- Consequence block ---

    @Test
    void consequenceBlock() {
        // At 'System' inside 'do { ... }'
        CandidateResult r = collectAt(TEXT, 9, 8);
        assertThat(tokenNames(r)).contains("if", "var", "for", "while", "return");
        assertThat(tokenNames(r)).doesNotContain("rule", "window");
        assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
        assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
                .contains("ruleConsequence", "block");
        assertThat(siteAt(r)).isEqualTo(CONSEQUENCE_EXPRESSION);
    }

    @Test
    void afterDot() {
        // At 'println' after 'System.out.' — member access position
        CandidateResult r = collectAt(TEXT, 9, 19);
        assertThat(tokenNames(r)).contains("this", "super", "new", "class");
        assertThat(tokenNames(r)).doesNotContain("rule", "window", "if", "for");
        assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
        assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
                .contains("expression");
        assertThat(siteAt(r)).isEqualTo(DOT_ACCESS);
    }

    // --- Rule with parameters ---

    @Test
    void ruleParameterType() {
        // At 'String' in 'R2(String name)' — parameter type position
        CandidateResult r = collectAt(TEXT, 13, 8);
        assertThat(tokenNames(r)).contains("boolean", "int", "long", "short", "char", "float", "double", "byte");
        assertThat(hasRule(r, DrlxParser.RULE_identifier)).isTrue();
        assertThat(ruleCallStack(r, DrlxParser.RULE_identifier))
                .contains("ruleParameter", "typeType");
        assertThat(siteAt(r)).isEqualTo(RULE_PARAMETER);
    }
}

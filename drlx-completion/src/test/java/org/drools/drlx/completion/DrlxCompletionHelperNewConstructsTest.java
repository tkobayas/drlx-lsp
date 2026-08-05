package org.drools.drlx.completion;

import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.MemberCompletionProvider;
import org.drools.drlx.completion.semantic.SentinelExpressionTypeResolver;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.drools.drlx.completion.DrlxCompletionHelper.completionItemStrings;

/**
 * Tests for code completion with new drlx-parser constructs.
 *
 * Scope: keyword token auto-discovery by antlr4-c3.
 * Semantic completions (ExpressionTypeResolver) are out of scope.
 *
 * Completion is additive: keyword tokens and semantic completions (IDENTIFIER)
 * are both surfaced when the grammar offers both at a given position.
 */
class DrlxCompletionHelperNewConstructsTest {

    private final DrlxCompletionHelper helper = new DrlxCompletionHelper(
            new WorkspaceSemanticModel(new CurrentClassloaderProvider()),
            new SentinelExpressionTypeResolver(),
            new MemberCompletionProvider());

    // --- drlxCompilationUnit level: window and rule keywords ---

    @Test
    void windowAndRuleOfferedAtUnitLevel() {
        String text = """
                unit org.example;
                window RecentOrders {
                    /orders |time[5s]
                }
                rule R1 {
                    var o : /orders,
                    do { System.out.println(o); }
                }
                """;

        // caret: |window RecentOrders {
        Position caret = new Position(1, 0);
        List<String> items = completionItemStrings(helper.getCompletionItems(text, caret));
        assertThat(items).contains("window", "rule");
    }

    @Test
    void ruleOfferedAfterWindow() {
        String text = """
                unit org.example;
                window RecentOrders {
                    /orders |time[5s]
                }

                """;

        // caret: after '}' of window declaration, at empty line
        Position caret = new Position(4, 0);
        List<String> items = completionItemStrings(helper.getCompletionItems(text, caret));
        assertThat(items).contains("rule", "window");
    }

    // --- Rule with annotations ---

    @Test
    void ruleWithAnnotation() {
        String text = """
                unit MyUnit;

                @Timer(5s)
                rule R1 {
                    var a : /persons,
                    do { System.out.println(a); }
                }
                """;

        // caret: |rule R1 {  (after @Timer annotation)
        Position caret = new Position(3, 0);
        List<String> items = completionItemStrings(helper.getCompletionItems(text, caret));
        assertThat(items).contains("rule");
    }

    // --- Rule with parameters (query form) ---

    @Test
    void ruleWithParameters_identifierExpectedForType() {
        String text = """
                unit MyUnit;

                rule findPerson(String name) {
                    var p : /persons[name == name],
                    do { System.out.println(p); }
                }
                """;

        // caret: var |p : /persons[name == name],
        Position caret = new Position(3, 8);
        List<String> items = completionItemStrings(helper.getCompletionItems(text, caret));
        assertThat(items).containsOnly("IDENTIFIER");
    }

    // --- Additive completion: keywords + IDENTIFIER at rule-item-start ---

    @Test
    void ruleItemStart_keywordsAndIdentifierBothOffered() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /persons,

                }
                """;

        // caret: empty line after first pattern — new rule item position
        Position caret = new Position(4, 4);
        List<String> items = completionItemStrings(helper.getCompletionItems(text, caret));
        assertThat(items).contains("not", "exists", "and", "or", "test", "if", "match", "do", "var");
        assertThat(items).contains("IDENTIFIER");
    }

    @Test
    void ruleItemStart_emptyRuleBody() {
        String text = """
                unit MyUnit;

                rule R1 {

                }
                """;

        // caret: empty rule body — first rule item position
        Position caret = new Position(3, 4);
        List<String> items = completionItemStrings(helper.getCompletionItems(text, caret));
        assertThat(items).contains("not", "exists", "do", "var");
        assertThat(items).contains("IDENTIFIER");
    }

    @Test
    void ruleItemStart_beforeNotElement() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /persons,
                    not /orders,
                    do { System.out.println(a); }
                }
                """;

        // caret: |not /orders, — rule item start where CE keywords should appear
        Position caret = new Position(4, 4);
        List<String> items = completionItemStrings(helper.getCompletionItems(text, caret));
        assertThat(items).contains("not", "exists", "if", "match", "do");
        assertThat(items).contains("IDENTIFIER");
    }

    // --- test element ---

    @Test
    void testElement_expressionContext() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    test a.age > 18,
                    do { System.out.println(a); }
                }
                """;

        // caret: test |a.age > 18,
        Position caret = new Position(4, 9);
        List<String> items = completionItemStrings(helper.getCompletionItems(text, caret));
        assertThat(items).doesNotContain("rule", "window");
    }

    // --- match branch ---

    @Test
    void matchBranch_doesNotOfferTopLevelKeywords() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    match (p.status)
                    case "active" do { System.out.println("active"); }
                }
                """;

        // caret: match |(p.status)
        Position caret = new Position(4, 10);
        List<String> items = completionItemStrings(helper.getCompletionItems(text, caret));
        assertThat(items).doesNotContain("rule");
    }

    // --- OOPath variations ---

    @Test
    void oopathWithConstraint_noTopLevelKeywords() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var p : /persons[age > 18],
                    do { System.out.println(p); }
                }
                """;

        // caret: /persons[age > 18|],
        Position caret = new Position(3, 20);
        List<String> items = completionItemStrings(helper.getCompletionItems(text, caret));
        assertThat(items).doesNotContain("rule", "window");
    }

    @Test
    void oopathChained_identifierExpected() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /persons/address[city == "Tokyo"],
                    do { System.out.println(a); }
                }
                """;

        // caret: /persons/|address[city == "Tokyo"],
        // No import for MyUnit, so entry-point type can't be resolved — empty chunk completions
        Position caret = new Position(3, 21);
        List<String> items = completionItemStrings(helper.getCompletionItems(text, caret));
        assertThat(items).doesNotContain("IDENTIFIER");
    }

    // --- Window filter on pattern ---

    @Test
    void windowFilter_identifierExpectedForBind() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var o : /orders |time[5s],
                    do { System.out.println(o); }
                }
                """;

        // caret: var |o : /orders |time[5s],
        Position caret = new Position(3, 8);
        List<String> items = completionItemStrings(helper.getCompletionItems(text, caret));
        assertThat(items).containsOnly("IDENTIFIER");
    }

    // --- Multiple rules after window ---

    @Test
    void multipleRulesAfterWindow() {
        String text = """
                unit org.example;
                window RecentOrders {
                    /orders |time[5s]
                }
                rule R1 {
                    var o : /orders,
                    do { System.out.println(o); }
                }

                """;

        // caret: empty line after '}' of rule R1
        Position caret = new Position(8, 0);
        List<String> items = completionItemStrings(helper.getCompletionItems(text, caret));
        assertThat(items).contains("rule");
    }

    // --- Consequence (do block) inside various constructs ---

    @Test
    void consequence_javaExpressionsOffered() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /persons,
                    do { System.out.println(a); }
                }
                """;

        // caret: do { |System.out.println(a); }
        Position caret = new Position(4, 9);
        List<String> items = completionItemStrings(helper.getCompletionItems(text, caret));
        assertThat(items).contains("int", "var", "if");
    }

    // --- No crash tests: verify new constructs don't break completion ---

    @Test
    void noCrash_notElement() {
        String text = """
                unit MyUnit;

                rule R1 {
                    not /persons,
                    do { System.out.println("no persons"); }
                }
                """;

        // caret: not |/persons,
        Position caret = new Position(3, 8);
        List<CompletionItem> result = helper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_existsElement() {
        String text = """
                unit MyUnit;

                rule R1 {
                    exists /persons,
                    do { System.out.println("persons exist"); }
                }
                """;

        // caret: exists |/persons,
        Position caret = new Position(3, 11);
        List<CompletionItem> result = helper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_notParenForm() {
        String text = """
                unit MyUnit;

                rule R1 {
                    not(/persons, /orders),
                    do { System.out.println("none"); }
                }
                """;

        // caret: not(|/persons, /orders),
        Position caret = new Position(3, 8);
        List<CompletionItem> result = helper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_conditionalBranch() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    if (p.age > 18) {
                        var s : /seniors,
                    } else {
                        var j : /juniors,
                    }
                }
                """;

        // caret: } else {\n        |var j : /juniors,
        Position caret = new Position(7, 8);
        List<CompletionItem> result = helper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_matchBranch() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    match (p.status)
                    case "active" do { System.out.println("active"); }
                    case "inactive" do { System.out.println("inactive"); }
                }
                """;

        // caret: |case "inactive" do { ... }
        Position caret = new Position(6, 4);
        List<CompletionItem> result = helper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_accumulateItem() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var total : int = sum(/orders.amount),
                    do { System.out.println(total); }
                }
                """;

        // caret: |var total : int = sum(/orders.amount),
        Position caret = new Position(3, 4);
        List<CompletionItem> result = helper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_passivePattern() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : ?/persons,
                    do { System.out.println(a); }
                }
                """;

        // caret: var a : ?/|persons,
        Position caret = new Position(3, 13);
        List<CompletionItem> result = helper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_groupByKeyword() {
        String text = """
                unit MyUnit;

                rule R1 {
                    groupBy(Person p : /persons, var key = p.department, int count = count(p)),
                    do { System.out.println(count); }
                }
                """;

        // caret: groupBy(|Person p : /persons, ...),
        Position caret = new Position(3, 12);
        List<CompletionItem> result = helper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_andOrElements() {
        String text = """
                unit MyUnit;

                rule R1 {
                    and(/persons, /orders),
                    do { System.out.println("both"); }
                }
                """;

        // caret: and(|/persons, /orders),
        Position caret = new Position(3, 8);
        List<CompletionItem> result = helper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_customConstraint() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var e : /events[this after[0s, 1h] $other],
                    do { System.out.println(e); }
                }
                """;

        // caret: /events[this after[0s,| 1h] $other],
        Position caret = new Position(3, 20);
        List<CompletionItem> result = helper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }
}

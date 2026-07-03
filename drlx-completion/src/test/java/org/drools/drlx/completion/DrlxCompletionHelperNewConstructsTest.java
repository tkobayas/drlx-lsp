package org.drools.drlx.completion;

import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.drools.drlx.completion.DrlxCompletionHelper.completionItemStrings;

/**
 * Tests for code completion with new drlx-parser constructs.
 *
 * Scope: keyword token auto-discovery by antlr4-c3.
 * Semantic completions (TolerantDrlxToJavaParserVisitor) are out of scope — see issue #5.
 *
 * Note: inside rule body, the grammar offers both keyword tokens (not, exists, if, match, etc.)
 * AND identifier rules (for boundOopath). The current code routes to semantic completions
 * when RULE_identifier is a candidate, so keyword tokens are not surfaced at those positions.
 * This is a known limitation to be addressed by issue #5.
 */
class DrlxCompletionHelperNewConstructsTest {

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
        List<String> items = completionItemStrings(DrlxCompletionHelper.getCompletionItems(text, caret));
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
        List<String> items = completionItemStrings(DrlxCompletionHelper.getCompletionItems(text, caret));
        assertThat(items).contains("rule", "window");
    }

    // --- Rule with annotations ---

    @Test
    void ruleWithAnnotation() {
        String text = """
                class Foo {
                    @Timer(5s)
                    rule R1 {
                        var a : /persons,
                        do { System.out.println(a); }
                    }
                }
                """;

        // caret: |rule R1 {  (after @Timer annotation)
        Position caret = new Position(2, 4);
        List<String> items = completionItemStrings(DrlxCompletionHelper.getCompletionItems(text, caret));
        assertThat(items).contains("rule");
    }

    // --- Rule with parameters (query form) ---

    @Test
    void ruleWithParameters_identifierExpectedForType() {
        String text = """
                class Foo {
                    rule findPerson(String name) {
                        var p : /persons[name == name],
                        do { System.out.println(p); }
                    }
                }
                """;

        // caret: var |p : /persons[name == name],
        Position caret = new Position(2, 12);
        List<String> items = completionItemStrings(DrlxCompletionHelper.getCompletionItems(text, caret));
        assertThat(items).containsOnly("IDENTIFIER");
    }

    // --- test element ---

    @Test
    void testElement_expressionContext() {
        String text = """
                class Foo {
                    rule R1 {
                        var a : /as,
                        test a.age > 18,
                        do { System.out.println(a); }
                    }
                }
                """;

        // caret: test |a.age > 18,
        Position caret = new Position(3, 13);
        List<String> items = completionItemStrings(DrlxCompletionHelper.getCompletionItems(text, caret));
        assertThat(items).doesNotContain("rule", "window");
    }

    // --- match branch ---

    @Test
    void matchBranch_doesNotOfferTopLevelKeywords() {
        String text = """
                class Foo {
                    rule R1 {
                        var p : /persons,
                        match (p.status)
                        case "active" do { System.out.println("active"); }
                    }
                }
                """;

        // caret: match |(p.status)
        Position caret = new Position(3, 14);
        List<String> items = completionItemStrings(DrlxCompletionHelper.getCompletionItems(text, caret));
        assertThat(items).doesNotContain("rule");
    }

    // --- OOPath variations ---

    @Test
    void oopathWithConstraint_noTopLevelKeywords() {
        String text = """
                class Foo {
                    rule R1 {
                        var p : /persons[age > 18],
                        do { System.out.println(p); }
                    }
                }
                """;

        // caret: /persons[age > 18|],
        Position caret = new Position(2, 24);
        List<String> items = completionItemStrings(DrlxCompletionHelper.getCompletionItems(text, caret));
        assertThat(items).doesNotContain("rule", "window");
    }

    @Test
    void oopathChained_identifierExpected() {
        String text = """
                class Foo {
                    rule R1 {
                        var a : /persons/address[city == "Tokyo"],
                        do { System.out.println(a); }
                    }
                }
                """;

        // caret: /persons/|address[city == "Tokyo"],
        Position caret = new Position(2, 25);
        List<String> items = completionItemStrings(DrlxCompletionHelper.getCompletionItems(text, caret));
        assertThat(items).containsOnly("IDENTIFIER");
    }

    // --- Window filter on pattern ---

    @Test
    void windowFilter_identifierExpectedForBind() {
        String text = """
                class Foo {
                    rule R1 {
                        var o : /orders |time[5s],
                        do { System.out.println(o); }
                    }
                }
                """;

        // caret: var |o : /orders |time[5s],
        Position caret = new Position(2, 12);
        List<String> items = completionItemStrings(DrlxCompletionHelper.getCompletionItems(text, caret));
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
        List<String> items = completionItemStrings(DrlxCompletionHelper.getCompletionItems(text, caret));
        assertThat(items).contains("rule");
    }

    // --- Consequence (do block) inside various constructs ---

    @Test
    void consequence_javaExpressionsOffered() {
        String text = """
                class Foo {
                    rule R1 {
                        var a : /persons,
                        do { System.out.println(a); }
                    }
                }
                """;

        // caret: do { |System.out.println(a); }
        Position caret = new Position(3, 13);
        List<String> items = completionItemStrings(DrlxCompletionHelper.getCompletionItems(text, caret));
        assertThat(items).contains("int", "var", "if");
    }

    // --- No crash tests: verify new constructs don't break completion ---

    @Test
    void noCrash_notElement() {
        String text = """
                class Foo {
                    rule R1 {
                        not /persons,
                        do { System.out.println("no persons"); }
                    }
                }
                """;

        // caret: not |/persons,
        Position caret = new Position(2, 12);
        List<CompletionItem> result = DrlxCompletionHelper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_existsElement() {
        String text = """
                class Foo {
                    rule R1 {
                        exists /persons,
                        do { System.out.println("persons exist"); }
                    }
                }
                """;

        // caret: exists |/persons,
        Position caret = new Position(2, 15);
        List<CompletionItem> result = DrlxCompletionHelper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_notParenForm() {
        String text = """
                class Foo {
                    rule R1 {
                        not(/persons, /orders),
                        do { System.out.println("none"); }
                    }
                }
                """;

        // caret: not(|/persons, /orders),
        Position caret = new Position(2, 12);
        List<CompletionItem> result = DrlxCompletionHelper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_conditionalBranch() {
        String text = """
                class Foo {
                    rule R1 {
                        var p : /persons,
                        if (p.age > 18) {
                            var s : /seniors,
                        } else {
                            var j : /juniors,
                        }
                    }
                }
                """;

        // caret: } else {\n            |var j : /juniors,
        Position caret = new Position(6, 12);
        List<CompletionItem> result = DrlxCompletionHelper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_matchBranch() {
        String text = """
                class Foo {
                    rule R1 {
                        var p : /persons,
                        match (p.status)
                        case "active" do { System.out.println("active"); }
                        case "inactive" do { System.out.println("inactive"); }
                    }
                }
                """;

        // caret: |case "inactive" do { ... }
        Position caret = new Position(5, 8);
        List<CompletionItem> result = DrlxCompletionHelper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_accumulateItem() {
        String text = """
                class Foo {
                    rule R1 {
                        var total : int = sum(/orders.amount),
                        do { System.out.println(total); }
                    }
                }
                """;

        // caret: |var total : int = sum(/orders.amount),
        Position caret = new Position(2, 8);
        List<CompletionItem> result = DrlxCompletionHelper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_passivePattern() {
        String text = """
                class Foo {
                    rule R1 {
                        var a : ?/persons,
                        do { System.out.println(a); }
                    }
                }
                """;

        // caret: var a : ?/|persons,
        Position caret = new Position(2, 17);
        List<CompletionItem> result = DrlxCompletionHelper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_groupByKeyword() {
        String text = """
                class Foo {
                    rule R1 {
                        groupBy(Person p : /persons, var key = p.department, int count = count(p)),
                        do { System.out.println(count); }
                    }
                }
                """;

        // caret: groupBy(|Person p : /persons, ...),
        Position caret = new Position(2, 16);
        List<CompletionItem> result = DrlxCompletionHelper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_andOrElements() {
        String text = """
                class Foo {
                    rule R1 {
                        and(/persons, /orders),
                        do { System.out.println("both"); }
                    }
                }
                """;

        // caret: and(|/persons, /orders),
        Position caret = new Position(2, 12);
        List<CompletionItem> result = DrlxCompletionHelper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }

    @Test
    void noCrash_customConstraint() {
        String text = """
                class Foo {
                    rule R1 {
                        var e : /events[this after[0s, 1h] $other],
                        do { System.out.println(e); }
                    }
                }
                """;

        // caret: /events[this after[0s,| 1h] $other],
        Position caret = new Position(2, 24);
        List<CompletionItem> result = DrlxCompletionHelper.getCompletionItems(text, caret);
        assertThat(result).isNotNull();
    }
}

package org.drools.drlx.completion;

import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.MemberCompletionProvider;
import org.drools.drlx.completion.semantic.SentinelExpressionTypeResolver;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.drools.drlx.completion.DrlxCompletionHelper.completionItemStrings;

class DrlxCompletionHelperIncompleteCodeTest {

    private final DrlxCompletionHelper helper = new DrlxCompletionHelper(
            new WorkspaceSemanticModel(new CurrentClassloaderProvider()),
            new SentinelExpressionTypeResolver(),
            new MemberCompletionProvider());

    @Test
    void emptyInput() {
        String text = "";
        Position caretPosition = new Position(0, 0);

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("package", "import", "class");
    }

    @Test
    void incompleteRule_pattern() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var a : /
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(13); // After the '/'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("persons", "addresses");
    }

    @Test
    void incompleteRule_consequence_System() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(16); // After the 'System.'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("out", "in", "gc"); // System fields, methods
    }

    @Test
    void incompleteRule_consequence_SystemOut() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { System.out.
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(20); // After the 'System.out.'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("println"); // System.out fields, methods
    }

    @Test
    void incompleteClass_consequence() {
        String text = """
                public class Foo {
                    public void bar() {
                        System.
                """;

        Position caretPosition = new Position();
        List<CompletionItem> result;

        // Test completion after 'System.'
        caretPosition.setLine(2);
        caretPosition.setCharacter(15);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("out", "in", "gc"); // System fields, methods
    }

    @Test
    void incompleteRule_inlineCast() {
        String text = """
                import java.util.ArrayList;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        Object list = new Object();
                        list#ArrayList#.
                """;

        Position caretPosition = new Position();
        List<CompletionItem> result;

        // Test completion after 'list#ArrayList#.'
        caretPosition.setLine(8);
        caretPosition.setCharacter(24);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("trimToSize");
        assertThat(completionItemStrings(result)).doesNotContain("removeRange");
    }

    @Test
    void incompleteRule_BigDecimalLiteral() {
        String text = """
                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do { 10.5B.
                """;

        Position caretPosition = new Position();
        List<CompletionItem> result;

        // Test completion after '10.5B.'
        caretPosition.setLine(4);
        caretPosition.setCharacter(15);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("precision");
    }

    @Test
    void incompleteRule_entryPointBinding() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { p.
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(7);
        caretPosition.setCharacter(11); // After 'p.'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("age", "name", "address", "getAge", "getName", "getAddress");
    }

    @Test
    void incompleteRule_varTypeInference_rhs() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do {
                        var addr = p.getAddress();
                        addr.
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(10);
        caretPosition.setCharacter(13); // After 'addr.'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("city", "country", "getCity");
    }

    @Test
    void incompleteRule_accumulateResultBinding_var() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var total = sum(/persons.age),
                    do { total.
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(5);
        caretPosition.setCharacter(15); // after 'total.'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("intValue", "doubleValue");
    }

    @Test
    void incompleteRule_accumulateResultBinding_explicitType() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    Long total = count(/persons),
                    do { total.
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(5);
        caretPosition.setCharacter(15); // after 'total.'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("intValue", "doubleValue");
    }

    @Test
    void incompleteRule_accumulateResultBinding_count() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var cnt = count(/persons),
                    do { cnt.
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(5);
        caretPosition.setCharacter(13); // after 'cnt.'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("intValue", "doubleValue");
    }

    @Test
    void incompleteRule_accKeyword_sourceBinding_dotAccess() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    acc(var p : /persons,
                        int s = 0;,
                        s = s + p.
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(8);
        caretPosition.setCharacter(18); // after 's = s + p.'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("age", "name", "address");
    }

    @Test
    void accKeyword_initVar_dotAccess() {
        String text = """
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    acc(var p : /persons,
                        Address a = null;,
                        a.city,
                        int sum = 0)
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(8);
        caretPosition.setCharacter(10); // after 'a.' in the action block

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("city", "country");
    }

    @Test
    void incompleteRule_constraintBinding_rootChunk() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons[$addr : address],
                    do { $addr.
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(8);
        caretPosition.setCharacter(15); // after '$addr.'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("city", "country", "getCity", "getCountry");
    }

    @Test
    void incompleteRule_constraintBinding_nestedChunk() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons/address[$c : city],
                    do { $c.
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(8);
        caretPosition.setCharacter(12); // after '$c.'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("length", "charAt");
    }

    @Test
    void incompleteRule_constraintBinding_name() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons[$n : name],
                    do { $n.
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(7);
        caretPosition.setCharacter(12); // after '$n.'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("length", "charAt");
    }

    @Test
    void incompleteRule_inlineCastTypeName() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;

                unit MyUnit;

                rule R1 {
                    var a : /as,
                    do {
                        Object obj = new Object();
                        obj#
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(9);
        caretPosition.setCharacter(12); // after 'obj#'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("Person", "Address");
    }

    @Test
    void incompleteRule_afterNew() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.Address;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule R1 {
                    var p : /persons,
                    do { Person p2 = new\s
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(8);
        caretPosition.setCharacter(25); // after 'new '

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("Person", "Address", "MyUnit");
    }

    @Test
    void incompleteRule_accumulateFunctionName() {
        String text = """
                import org.drools.drlx.domain.MyUnit;
                unit MyUnit;

                rule R1 {
                    var total =\s
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(4);
        caretPosition.setCharacter(16); // after 'var total = '

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result))
                .contains("avg", "sum", "min", "max", "count", "collectList", "collectSet");
    }

    @Test
    void incompleteRule_PropertyAccessor() {
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

        Position caretPosition = new Position();
        List<CompletionItem> result;

        // Test completion after 'p.address.'
        caretPosition.setLine(9);
        caretPosition.setCharacter(18);
        result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("city", "getCity", "setCity"); // `city` can be directly accessed in mvel
    }

    @Test
    void incompleteRule_queryParameterNames() {
        String text = """
                import org.drools.drlx.domain.Person;
                import org.drools.drlx.domain.MyUnit;

                unit MyUnit;

                rule personsByAge(int minAge, Person result) {
                    Person p : /persons[age >= minAge],
                    do { result = p; }
                }

                rule R1 {
                    /personsByAge[
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(11);
        caretPosition.setCharacter(18); // after '['

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains("minAge", "result");
    }

    @Test
    void incompleteRule_annotationNames() {
        String text = """
                unit MyUnit;

                @
                rule R1 {
                    var p : /persons,
                    do { System.out.println(p); }
                }
                """;

        Position caretPosition = new Position();
        caretPosition.setLine(2);
        caretPosition.setCharacter(1); // after '@'

        List<CompletionItem> result = helper.getCompletionItems(text, caretPosition);
        assertThat(completionItemStrings(result)).contains(
                "ActivationGroup", "DataSource", "DateEffective", "DateExpires",
                "Description", "Disabled", "Duration", "LockOnActive",
                "NoLoop", "Salience", "Timer");
    }
}

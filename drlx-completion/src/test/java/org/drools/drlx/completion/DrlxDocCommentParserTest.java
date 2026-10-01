package org.drools.drlx.completion;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxDocCommentParserTest {

    @Test
    void emptyOrNullReturnsEmpty() {
        assertThat(DrlxDocCommentParser.parseDocs(null)).isEmpty();
        assertThat(DrlxDocCommentParser.parseDocs("")).isEmpty();
    }

    @Test
    void docOnRuleIsCaptured() {
        String drlx =
            "/** Matches high-risk patients. */\n"
            + "rule HighRisk {\n"
            + "    var p : /persons[age > 60],\n"
            + "    do { System.out.println(p); }\n"
            + "}\n";

        Map<String, String> docs = DrlxDocCommentParser.parseDocs(drlx);

        assertThat(docs).containsOnlyKeys("HighRisk");
        assertThat(docs.get("HighRisk"))
            .isEqualTo("Matches high-risk patients.");
    }

    @Test
    void docOnUnitIsCaptured() {
        String drlx =
            "/** The main patient-processing unit. */\n"
            + "unit org.example.PatientUnit;\n";

        Map<String, String> docs = DrlxDocCommentParser.parseDocs(drlx);

        assertThat(docs).containsEntry("org.example.PatientUnit",
            "The main patient-processing unit.");
    }

    @Test
    void docOnWindowIsCaptured() {
        String drlx =
            "/** Last 10 sensor events. */\n"
            + "window LastEvents {\n"
            + "    /events |time 10s|\n"
            + "}\n";

        Map<String, String> docs = DrlxDocCommentParser.parseDocs(drlx);

        assertThat(docs).containsEntry("LastEvents",
            "Last 10 sensor events.");
    }

    @Test
    void docOnAnnotatedRuleIsCaptured() {
        String drlx =
            "/**\n"
            + " * High-priority rule.\n"
            + " */\n"
            + "@Salience(10)\n"
            + "rule HighPriority {\n"
            + "    var p : /persons,\n"
            + "    do { System.out.println(p); }\n"
            + "}\n";

        Map<String, String> docs = DrlxDocCommentParser.parseDocs(drlx);

        assertThat(docs).containsOnlyKeys("HighPriority");
        assertThat(docs.get("HighPriority"))
            .isEqualTo("High-priority rule.");
    }

    @Test
    void docNotFollowedByDeclIsIgnored() {
        String drlx =
            "/** This is just floating text. */\n"
            + "\n"
            + "// Some comment.\n"
            + "import org.example.Foo;\n";

        assertThat(DrlxDocCommentParser.parseDocs(drlx)).isEmpty();
    }

    @Test
    void multipleDocsOnDifferentDeclsAreAllCaptured() {
        String drlx =
            "/** Doc for R1. */\n"
            + "rule R1 {\n"
            + "    var p : /persons,\n"
            + "    do { System.out.println(p); }\n"
            + "}\n"
            + "\n"
            + "/** Doc for R2. */\n"
            + "rule R2 {\n"
            + "    var p : /persons,\n"
            + "    do { System.out.println(p); }\n"
            + "}\n";

        Map<String, String> docs = DrlxDocCommentParser.parseDocs(drlx);

        assertThat(docs)
            .containsEntry("R1", "Doc for R1.")
            .containsEntry("R2", "Doc for R2.");
    }

    @Test
    void bannerCommentIsNotADoc() {
        String drlx =
            "/**************************\n"
            + "Banner above rule\n"
            + "**************************/\n"
            + "rule R1 {\n"
            + "    var p : /persons,\n"
            + "    do { System.out.println(p); }\n"
            + "}\n";

        assertThat(DrlxDocCommentParser.parseDocs(drlx)).isEmpty();
    }

    @Test
    void decorativeBannerDoesNotPolluteRealDoc() {
        String drlx =
            "package org.example;\n"
            + "\n"
            + "/**************************\n"
            + "Imports\n"
            + "**************************/\n"
            + "import org.example.Foo;\n"
            + "\n"
            + "/** Real doc for R1. */\n"
            + "rule R1 {\n"
            + "    var p : /persons,\n"
            + "    do { System.out.println(p); }\n"
            + "}\n";

        Map<String, String> docs = DrlxDocCommentParser.parseDocs(drlx);

        assertThat(docs).containsOnlyKeys("R1");
        assertThat(docs.get("R1")).isEqualTo("Real doc for R1.");
    }

    @Test
    void multiLineDocBodyPreservesInternalStructure() {
        String drlx =
            "/**\n"
            + " * First line of description.\n"
            + " * Second line of description.\n"
            + " */\n"
            + "rule MultiLine {\n"
            + "    var p : /persons,\n"
            + "    do { System.out.println(p); }\n"
            + "}\n";

        Map<String, String> docs = DrlxDocCommentParser.parseDocs(drlx);

        assertThat(docs.get("MultiLine"))
            .isEqualTo("First line of description.\nSecond line of description.");
    }

    @Test
    void emptyDocBodyIsSkipped() {
        String drlx = "/** */\nrule R1 {\n    var p : /persons,\n    do { System.out.println(p); }\n}\n";
        assertThat(DrlxDocCommentParser.parseDocs(drlx)).isEmpty();
    }

    @Test
    void docConvenienceLookup() {
        String drlx = "/** Hello. */\nrule Foo {\n    var p : /persons,\n    do { System.out.println(p); }\n}\n";
        assertThat(DrlxDocCommentParser.docFor(drlx, "Foo")).isEqualTo("Hello.");
        assertThat(DrlxDocCommentParser.docFor(drlx, "Bar")).isNull();
        assertThat(DrlxDocCommentParser.docFor(null, "Foo")).isNull();
        assertThat(DrlxDocCommentParser.docFor(drlx, null)).isNull();
    }
}

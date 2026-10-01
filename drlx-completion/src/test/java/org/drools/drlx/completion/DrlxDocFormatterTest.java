package org.drools.drlx.completion;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxDocFormatterTest {

    @Test
    void inlineCodeBecomesBackticks() {
        assertThat(DrlxDocFormatter.format("uses {@code getX()} here", null))
                .isEqualTo("uses `getX()` here");
    }

    @Test
    void literalContentIsMarkdownEscaped() {
        assertThat(DrlxDocFormatter.format("{@literal *not bold*}", null))
                .isEqualTo("\\*not bold\\*");
    }

    @Test
    void linkWithTargetRendersMarkdownLink() {
        Map<String, String> targets = Map.of("Person", "file:///types.drlx");
        assertThat(DrlxDocFormatter.format("see {@link Person}", targets))
                .isEqualTo("see [Person](file:///types.drlx)");
    }

    @Test
    void linkLabelIsUsedWhenPresent() {
        Map<String, String> targets = Map.of("Person", "file:///types.drlx");
        assertThat(DrlxDocFormatter.format("see {@link Person the patient}", targets))
                .isEqualTo("see [the patient](file:///types.drlx)");
    }

    @Test
    void memberReferenceLooksUpTheTypePart() {
        Map<String, String> targets = Map.of("Person", "file:///types.drlx");
        assertThat(DrlxDocFormatter.format("{@link Person#name}", targets))
                .isEqualTo("[Person#name](file:///types.drlx)");
    }

    @Test
    void unresolvedLinkFallsBackToCode() {
        assertThat(DrlxDocFormatter.format("see {@link Person}", null))
                .isEqualTo("see `Person`");
    }

    @Test
    void unresolvedLinkplainFallsBackToPlainText() {
        assertThat(DrlxDocFormatter.format("see {@linkplain Person}", null))
                .isEqualTo("see Person");
    }

    @Test
    void unsupportedTagsAreLeftUntouched() {
        assertThat(DrlxDocFormatter.format("{@value Config#MAX}", null))
                .isEqualTo("{@value Config#MAX}");
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertThat(DrlxDocFormatter.format(null, null)).isNull();
        assertThat(DrlxDocFormatter.format("", null)).isEmpty();
    }
}

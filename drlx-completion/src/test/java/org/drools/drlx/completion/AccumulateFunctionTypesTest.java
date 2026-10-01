package org.drools.drlx.completion;

import java.util.Optional;
import java.util.Set;

import org.drools.drlx.completion.semantic.AccumulateFunctionTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccumulateFunctionTypesTest {

    @Test
    void resultType_sum() {
        assertThat(AccumulateFunctionTypes.resultType("sum")).isEqualTo(Optional.of("Double"));
    }

    @Test
    void resultType_avg() {
        assertThat(AccumulateFunctionTypes.resultType("avg")).isEqualTo(Optional.of("Double"));
    }

    @Test
    void resultType_min() {
        assertThat(AccumulateFunctionTypes.resultType("min")).isEqualTo(Optional.of("Comparable"));
    }

    @Test
    void resultType_max() {
        assertThat(AccumulateFunctionTypes.resultType("max")).isEqualTo(Optional.of("Comparable"));
    }

    @Test
    void resultType_count() {
        assertThat(AccumulateFunctionTypes.resultType("count")).isEqualTo(Optional.of("Long"));
    }

    @Test
    void resultType_collectList() {
        assertThat(AccumulateFunctionTypes.resultType("collectList")).isEqualTo(Optional.of("java.util.List"));
    }

    @Test
    void resultType_collectSet() {
        assertThat(AccumulateFunctionTypes.resultType("collectSet")).isEqualTo(Optional.of("java.util.Set"));
    }

    @Test
    void resultType_unknown() {
        assertThat(AccumulateFunctionTypes.resultType("unknown")).isEmpty();
    }

    @Test
    void resultType_null() {
        assertThat(AccumulateFunctionTypes.resultType(null)).isEmpty();
    }

    @Test
    void functionNames_returnsAll7() {
        Set<String> names = AccumulateFunctionTypes.functionNames();
        assertThat(names).containsExactlyInAnyOrder(
                "sum", "avg", "min", "max", "count", "collectList", "collectSet");
    }
}

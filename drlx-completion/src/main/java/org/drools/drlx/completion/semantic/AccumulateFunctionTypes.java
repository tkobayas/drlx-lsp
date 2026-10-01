package org.drools.drlx.completion.semantic;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class AccumulateFunctionTypes {

    private static final Map<String, String> FUNCTION_TYPES = Map.of(
            "sum",         "Double",
            "avg",         "Double",
            "min",         "Comparable",
            "max",         "Comparable",
            "count",       "Long",
            "collectList", "java.util.List",
            "collectSet",  "java.util.Set"
    );

    private AccumulateFunctionTypes() {}

    public static Optional<String> resultType(String functionName) {
        if (functionName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(FUNCTION_TYPES.get(functionName));
    }

    public static Set<String> functionNames() {
        return FUNCTION_TYPES.keySet();
    }
}

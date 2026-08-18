package org.drools.drlx.completion;

public enum CompletionSite {
    COMPILATION_UNIT,
    RULE_DECLARATION,
    RULE_ITEM,
    BIND_NAME,
    ENTRY_POINT,
    OOPATH_CHUNK,
    CONSTRAINT_EXPRESSION,
    CONSEQUENCE_EXPRESSION,
    DOT_ACCESS,
    INLINE_CAST_TYPE,
    ACCUMULATE_FUNCTION,
    QUERY_PARAMETER,
    RULE_ANNOTATION,
    TEST_EXPRESSION,
    RULE_PARAMETER,
    AFTER_NEW,
    OOPATH_WATCH_LIST,
    UNKNOWN;

    public boolean needsSemanticCompletions() {
        return this != UNKNOWN;
    }

    public boolean semanticOnly() {
        return this == ENTRY_POINT || this == OOPATH_CHUNK || this == OOPATH_WATCH_LIST;
    }
}

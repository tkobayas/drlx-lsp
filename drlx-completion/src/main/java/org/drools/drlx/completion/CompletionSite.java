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
    TEST_EXPRESSION,
    RULE_PARAMETER,
    UNKNOWN;

    public boolean isIdentifierExpected() {
        return this != UNKNOWN;
    }

    public boolean needsSemanticCompletions() {
        return this != UNKNOWN;
    }
}

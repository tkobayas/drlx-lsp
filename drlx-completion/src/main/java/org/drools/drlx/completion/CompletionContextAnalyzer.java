package org.drools.drlx.completion;

import java.util.List;

import com.vmware.antlr4c3.CodeCompletionCore;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;

public class CompletionContextAnalyzer {

    private CompletionContextAnalyzer() {
    }

    public static CompletionSite analyze(
            CodeCompletionCore.CandidatesCollection candidates,
            DrlxParser parser,
            int caretTokenIndex) {

        if (isDotAccess(parser, caretTokenIndex)) {
            return CompletionSite.DOT_ACCESS;
        }

        List<Integer> identifierStack = candidates.rules.get(DrlxParser.RULE_identifier);
        if (identifierStack == null) {
            return CompletionSite.UNKNOWN;
        }

        if (identifierStack.contains(DrlxParser.RULE_ruleConsequence)
                && identifierStack.contains(DrlxParser.RULE_block)) {
            return CompletionSite.CONSEQUENCE_EXPRESSION;
        }

        if (identifierStack.contains(DrlxParser.RULE_testElement)) {
            return CompletionSite.TEST_EXPRESSION;
        }

        if (identifierStack.contains(DrlxParser.RULE_drlxExpression)) {
            return CompletionSite.CONSTRAINT_EXPRESSION;
        }

        if (identifierStack.contains(DrlxParser.RULE_oopathRoot)) {
            return CompletionSite.ENTRY_POINT;
        }

        if (identifierStack.contains(DrlxParser.RULE_oopathChunk)) {
            return CompletionSite.OOPATH_CHUNK;
        }

        if (identifierStack.contains(DrlxParser.RULE_ruleItem)
                && identifierStack.contains(DrlxParser.RULE_boundOopath)) {
            return CompletionSite.RULE_ITEM;
        }

        if (identifierStack.contains(DrlxParser.RULE_boundOopath)) {
            return CompletionSite.BIND_NAME;
        }

        if (identifierStack.contains(DrlxParser.RULE_ruleParameter)
                && identifierStack.contains(DrlxParser.RULE_typeType)) {
            return CompletionSite.RULE_PARAMETER;
        }

        if (identifierStack.contains(DrlxParser.RULE_ruleParameter)) {
            return CompletionSite.BIND_NAME;
        }

        if (identifierStack.contains(DrlxParser.RULE_ruleDeclaration)
                && !identifierStack.contains(DrlxParser.RULE_ruleBody)) {
            return CompletionSite.RULE_DECLARATION;
        }

        if (identifierStack.contains(DrlxParser.RULE_compilationUnit)) {
            return CompletionSite.COMPILATION_UNIT;
        }

        return CompletionSite.UNKNOWN;
    }

    private static boolean isDotAccess(DrlxParser parser, int caretTokenIndex) {
        if (caretTokenIndex < 1) {
            return false;
        }
        return parser.getTokenStream().get(caretTokenIndex - 1).getType() == DrlxLexer.DOT;
    }
}

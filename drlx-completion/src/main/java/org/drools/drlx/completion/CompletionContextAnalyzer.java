package org.drools.drlx.completion;

import java.util.List;

import com.vmware.antlr4c3.CodeCompletionCore;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.drools.drlx.parser.DrlxParser.DrlxCompilationUnitContext;
import org.drools.drlx.parser.DrlxParser.OopathRootContext;
import org.drools.drlx.parser.DrlxParser.RuleDeclarationContext;

public class CompletionContextAnalyzer {

    private CompletionContextAnalyzer() {
    }

    public static CompletionSite analyze(
            CodeCompletionCore.CandidatesCollection candidates,
            DrlxParser parser,
            int caretTokenIndex,
            ParseTree parseTree) {

        if (isDotAccess(parser, caretTokenIndex)) {
            return CompletionSite.DOT_ACCESS;
        }

        if (isHashAccess(parser, caretTokenIndex)) {
            return CompletionSite.INLINE_CAST_TYPE;
        }

        if (isAfterNew(parser, caretTokenIndex)) {
            return CompletionSite.AFTER_NEW;
        }

        List<Integer> identifierStack = candidates.rules.get(DrlxParser.RULE_identifier);
        if (identifierStack == null) {
            return CompletionSite.UNKNOWN;
        }

        if (identifierStack.contains(DrlxParser.RULE_accumulateCall)) {
            return CompletionSite.ACCUMULATE_FUNCTION;
        }

        if (identifierStack.contains(DrlxParser.RULE_annotation)
                && !identifierStack.contains(DrlxParser.RULE_ruleBody)
                && isAtAccess(parser, caretTokenIndex)) {
            return CompletionSite.RULE_ANNOTATION;
        }

        if (identifierStack.contains(DrlxParser.RULE_ruleConsequence)
                && identifierStack.contains(DrlxParser.RULE_block)) {
            return CompletionSite.CONSEQUENCE_EXPRESSION;
        }

        if (identifierStack.contains(DrlxParser.RULE_testElement)) {
            return CompletionSite.TEST_EXPRESSION;
        }

        if (identifierStack.contains(DrlxParser.RULE_drlxExpression)) {
            if (isQueryInvocation(parseTree, caretTokenIndex)) {
                return CompletionSite.QUERY_PARAMETER;
            }
            return CompletionSite.CONSTRAINT_EXPRESSION;
        }

        if (identifierStack.contains(DrlxParser.RULE_watchItem)) {
            return CompletionSite.OOPATH_WATCH_LIST;
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
                && !identifierStack.contains(DrlxParser.RULE_ruleBody)
                && !identifierStack.contains(DrlxParser.RULE_altAnnotationQualifiedName)) {
            return CompletionSite.RULE_DECLARATION;
        }

        if (identifierStack.contains(DrlxParser.RULE_compilationUnit)
                || identifierStack.contains(DrlxParser.RULE_drlxCompilationUnit)) {
            return CompletionSite.COMPILATION_UNIT;
        }

        return CompletionSite.UNKNOWN;
    }

    private static boolean isQueryInvocation(ParseTree parseTree, int caretTokenIndex) {
        String rootName = findEnclosingOopathRootName(parseTree, caretTokenIndex);
        if (rootName == null) return false;
        return findQueryByName(parseTree, rootName) != null;
    }

    private static String findEnclosingOopathRootName(ParseTree node, int caretTokenIndex) {
        if (node instanceof OopathRootContext root) {
            if (root.getStart() != null && root.identifier(0) != null) {
                int rootStart = root.getStart().getTokenIndex();
                int rootStop = root.getStop() != null ? root.getStop().getTokenIndex() + 1 : Integer.MAX_VALUE;
                if (rootStart <= caretTokenIndex && rootStop >= caretTokenIndex) {
                    return root.identifier(0).getText();
                }
            }
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String found = findEnclosingOopathRootName(node.getChild(i), caretTokenIndex);
            if (found != null) return found;
        }
        return null;
    }

    static RuleDeclarationContext findQueryByName(ParseTree node, String name) {
        if (node instanceof DrlxCompilationUnitContext cu) {
            for (RuleDeclarationContext rule : cu.ruleDeclaration()) {
                if (rule.ruleParameterList() != null
                        && rule.identifier() != null
                        && name.equals(rule.identifier().getText())) {
                    return rule;
                }
            }
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            RuleDeclarationContext found = findQueryByName(node.getChild(i), name);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isDotAccess(DrlxParser parser, int caretTokenIndex) {
        if (caretTokenIndex < 1) {
            return false;
        }
        return parser.getTokenStream().get(caretTokenIndex - 1).getType() == DrlxLexer.DOT;
    }

    private static boolean isHashAccess(DrlxParser parser, int caretTokenIndex) {
        if (caretTokenIndex < 1) {
            return false;
        }
        return parser.getTokenStream().get(caretTokenIndex - 1).getType() == DrlxLexer.HASH;
    }

    private static boolean isAfterNew(DrlxParser parser, int caretTokenIndex) {
        for (int i = caretTokenIndex - 1; i >= 0; i--) {
            Token token = parser.getTokenStream().get(i);
            if (token.getChannel() == Token.DEFAULT_CHANNEL) {
                return token.getType() == DrlxLexer.NEW;
            }
        }
        return false;
    }

    private static boolean isAtAccess(DrlxParser parser, int caretTokenIndex) {
        if (caretTokenIndex < 1) {
            return false;
        }
        return parser.getTokenStream().get(caretTokenIndex - 1).getType() == DrlxLexer.AT;
    }
}

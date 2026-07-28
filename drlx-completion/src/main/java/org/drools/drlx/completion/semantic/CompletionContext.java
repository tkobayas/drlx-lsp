package org.drools.drlx.completion.semantic;

import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxParser;

public class CompletionContext {

    private final WorkspaceSemanticModel model;
    private final DrlxParser parser;
    private final ParseTree tree;
    private final int caretTokenIndex;

    CompletionContext(WorkspaceSemanticModel model, DrlxParser parser, ParseTree tree, int caretTokenIndex) {
        this.model = model;
        this.parser = parser;
        this.tree = tree;
        this.caretTokenIndex = caretTokenIndex;
    }
}

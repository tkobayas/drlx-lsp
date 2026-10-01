package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonPrimitive;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

public final class DrlxCodeActionHelper {

    private DrlxCodeActionHelper() {
    }

    public static List<CodeAction> codeActions(String uri, Range range, CodeActionContext context) {
        if (uri == null || range == null || context == null || context.getDiagnostics() == null) {
            return Collections.emptyList();
        }

        List<CodeAction> actions = new ArrayList<>();
        for (Diagnostic diagnostic : context.getDiagnostics()) {
            if ("drlx-type".equals(diagnostic.getSource())) {
                if (diagnostic.getRange() != null && rangesOverlap(range, diagnostic.getRange())) {
                    String suggestion = extractSuggestion(diagnostic.getData());
                    if (suggestion != null && !suggestion.isEmpty()) {
                        CodeAction action = new CodeAction();
                        action.setTitle("Replace with '" + suggestion + "'");
                        action.setKind(CodeActionKind.QuickFix);
                        action.setDiagnostics(List.of(diagnostic));

                        TextEdit textEdit = new TextEdit(diagnostic.getRange(), suggestion);
                        WorkspaceEdit edit = new WorkspaceEdit();
                        edit.setChanges(Map.of(uri, List.of(textEdit)));
                        action.setEdit(edit);

                        actions.add(action);
                    }
                }
            }
        }
        return actions;
    }

    private static String extractSuggestion(Object data) {
        if (data instanceof String s) {
            return s;
        } else if (data instanceof JsonPrimitive jp && jp.isString()) {
            return jp.getAsString();
        }
        return null;
    }

    private static boolean rangesOverlap(Range r1, Range r2) {
        if (isBefore(r1.getEnd(), r2.getStart())) {
            return false;
        }
        if (isBefore(r2.getEnd(), r1.getStart())) {
            return false;
        }
        return true;
    }

    private static boolean isBefore(Position p1, Position p2) {
        if (p1.getLine() < p2.getLine()) {
            return true;
        }
        if (p1.getLine() == p2.getLine()) {
            return p1.getCharacter() < p2.getCharacter();
        }
        return false;
    }
}

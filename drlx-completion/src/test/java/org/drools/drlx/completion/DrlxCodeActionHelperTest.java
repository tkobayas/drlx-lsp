package org.drools.drlx.completion;

import java.util.Collections;
import java.util.List;

import com.google.gson.JsonPrimitive;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlxCodeActionHelperTest {

    private static final String URI = "file:///test.drlx";

    @Test
    void matchingDiagnosticProducesQuickFix() {
        Diagnostic d = new Diagnostic();
        Range diagRange = new Range(new Position(6, 4), new Position(6, 10));
        d.setRange(diagRange);
        d.setSeverity(DiagnosticSeverity.Warning);
        d.setSource("drlx-type");
        d.setMessage("Unknown type 'Preson'. Did you mean 'Person'?");
        d.setData("Person");

        CodeActionContext context = new CodeActionContext(List.of(d));
        Range cursorRange = new Range(new Position(6, 5), new Position(6, 5));

        List<CodeAction> actions = DrlxCodeActionHelper.codeActions(URI, cursorRange, context);

        assertThat(actions).hasSize(1);
        CodeAction action = actions.get(0);
        assertThat(action.getTitle()).isEqualTo("Replace with 'Person'");
        assertThat(action.getKind()).isEqualTo(CodeActionKind.QuickFix);
        assertThat(action.getDiagnostics()).containsExactly(d);
        assertThat(action.getEdit()).isNotNull();
        assertThat(action.getEdit().getChanges()).containsKey(URI);

        List<TextEdit> edits = action.getEdit().getChanges().get(URI);
        assertThat(edits).hasSize(1);
        assertThat(edits.get(0).getRange()).isEqualTo(diagRange);
        assertThat(edits.get(0).getNewText()).isEqualTo("Person");
    }

    @Test
    void matchingDiagnosticWithJsonPrimitiveData() {
        Diagnostic d = new Diagnostic();
        Range diagRange = new Range(new Position(6, 4), new Position(6, 10));
        d.setRange(diagRange);
        d.setSeverity(DiagnosticSeverity.Warning);
        d.setSource("drlx-type");
        d.setMessage("Unknown type 'Preson'. Did you mean 'Person'?");
        d.setData(new JsonPrimitive("Person"));

        CodeActionContext context = new CodeActionContext(List.of(d));
        Range cursorRange = new Range(new Position(6, 4), new Position(6, 10));

        List<CodeAction> actions = DrlxCodeActionHelper.codeActions(URI, cursorRange, context);

        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).getTitle()).isEqualTo("Replace with 'Person'");
    }

    @Test
    void diagnosticOutsideRangeProducesNoActions() {
        Diagnostic d = new Diagnostic();
        Range diagRange = new Range(new Position(6, 4), new Position(6, 10));
        d.setRange(diagRange);
        d.setSeverity(DiagnosticSeverity.Warning);
        d.setSource("drlx-type");
        d.setMessage("Unknown type 'Preson'. Did you mean 'Person'?");
        d.setData("Person");

        CodeActionContext context = new CodeActionContext(List.of(d));
        // Cursor on line 10
        Range cursorRange = new Range(new Position(10, 0), new Position(10, 5));

        List<CodeAction> actions = DrlxCodeActionHelper.codeActions(URI, cursorRange, context);

        assertThat(actions).isEmpty();
    }

    @Test
    void diagnosticFromOtherSourceIgnored() {
        Diagnostic d = new Diagnostic();
        Range diagRange = new Range(new Position(6, 4), new Position(6, 10));
        d.setRange(diagRange);
        d.setSeverity(DiagnosticSeverity.Warning);
        d.setSource("drlx-lint");
        d.setMessage("Some other lint");
        d.setData("Person");

        CodeActionContext context = new CodeActionContext(List.of(d));
        Range cursorRange = new Range(new Position(6, 5), new Position(6, 5));

        List<CodeAction> actions = DrlxCodeActionHelper.codeActions(URI, cursorRange, context);

        assertThat(actions).isEmpty();
    }

    @Test
    void diagnosticWithoutSuggestionDataProducesNoActions() {
        Diagnostic d = new Diagnostic();
        Range diagRange = new Range(new Position(6, 4), new Position(6, 10));
        d.setRange(diagRange);
        d.setSeverity(DiagnosticSeverity.Warning);
        d.setSource("drlx-type");
        d.setMessage("Unknown type 'Preson'");
        d.setData(null);

        CodeActionContext context = new CodeActionContext(List.of(d));
        Range cursorRange = new Range(new Position(6, 5), new Position(6, 5));

        List<CodeAction> actions = DrlxCodeActionHelper.codeActions(URI, cursorRange, context);

        assertThat(actions).isEmpty();
    }

    @Test
    void nullAndEmptyHandling() {
        assertThat(DrlxCodeActionHelper.codeActions(null, new Range(), new CodeActionContext())).isEmpty();
        assertThat(DrlxCodeActionHelper.codeActions(URI, null, new CodeActionContext())).isEmpty();
        assertThat(DrlxCodeActionHelper.codeActions(URI, new Range(), null)).isEmpty();
        assertThat(DrlxCodeActionHelper.codeActions(URI, new Range(), new CodeActionContext(Collections.emptyList()))).isEmpty();
    }
}

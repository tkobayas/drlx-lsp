package org.drools.drlx.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.drools.drlx.parser.DrlxLexer;
import org.drools.drlx.parser.DrlxParser;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public final class DrlxDiagnosticHelper {

    private DrlxDiagnosticHelper() {
    }

    public static List<Diagnostic> validate(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        ANTLRInputStream input = new ANTLRInputStream(text);
        DrlxLexer lexer = new DrlxLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DrlxParser parser = new DrlxParser(tokens);

        List<Diagnostic> diagnostics = new ArrayList<>();
        CollectingErrorListener listener = new CollectingErrorListener(diagnostics);

        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        parser.removeErrorListeners();
        parser.addErrorListener(listener);

        parser.drlxStart();
        return diagnostics;
    }

    private static class CollectingErrorListener extends BaseErrorListener {

        private final List<Diagnostic> diagnostics;

        CollectingErrorListener(List<Diagnostic> diagnostics) {
            this.diagnostics = diagnostics;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg,
                                RecognitionException e) {
            int startCol = charPositionInLine;
            int endCol = charPositionInLine + 1;
            if (offendingSymbol instanceof Token token) {
                if (token.getType() == Token.EOF) {
                    startCol = Math.max(0, charPositionInLine - 1);
                    endCol = charPositionInLine;
                } else {
                    String tokenText = token.getText();
                    if (tokenText != null && !tokenText.isEmpty()
                            && tokenText.indexOf('\n') < 0 && tokenText.indexOf('\r') < 0) {
                        endCol = charPositionInLine + tokenText.length();
                    }
                }
            }
            Diagnostic d = new Diagnostic();
            d.setRange(new Range(new Position(line - 1, startCol),
                                 new Position(line - 1, endCol)));
            d.setSeverity(DiagnosticSeverity.Error);
            d.setSource("drlx-parser");
            d.setMessage(msg);
            diagnostics.add(d);
        }
    }
}

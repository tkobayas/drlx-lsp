package org.drools.drlx.lsp.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.services.LanguageClient;

public class TestHelperMethods {

    private TestHelperMethods() {
    }

    public static DrlxLspDocumentService getDrlxLspDocumentService(String drlx) {
        DrlxLspServer ls = getDrlxLspServerForDocument(drlx);
        return ls.getTextDocumentService();
    }

    /**
     * Returns a server pre-loaded with the given document. The last
     * {@code publishDiagnostics} call is captured in {@code capturedDiagnostics}.
     */
    public static DrlxLspServer getDrlxLspServerForDocument(String drlx) {
        return getDrlxLspServerForDocument(drlx, new ArrayList<>());
    }

    public static DrlxLspServer getDrlxLspServerForDocument(String drlx,
                                                              List<Diagnostic> capturedDiagnostics) {
        DrlxLspServer ls = new DrlxLspServer();
        ls.connect(new LanguageClient() {
            @Override
            public void telemetryEvent(Object object) {
            }

            @Override
            public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
                return null;
            }

            @Override
            public void showMessage(MessageParams messageParams) {
            }

            @Override
            public void publishDiagnostics(PublishDiagnosticsParams d) {
                capturedDiagnostics.clear();
                capturedDiagnostics.addAll(d.getDiagnostics());
            }

            @Override
            public void logMessage(MessageParams message) {
            }
        });

        TextDocumentItem doc = new TextDocumentItem();
        doc.setUri("myDocument");
        doc.setText(drlx);
        ls.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(doc));
        return ls;
    }
}

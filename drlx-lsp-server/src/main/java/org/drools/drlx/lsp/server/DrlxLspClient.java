package org.drools.drlx.lsp.server;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;

public class DrlxLspClient implements LanguageClient {
    
    @Override
    public void telemetryEvent(Object object) {
        // No-op for now
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        // No-op for now
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        // No-op for now
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
        // No-op for now
    }
}
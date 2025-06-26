package org.drools.drlx.lsp.server;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.drools.drlx.completion.DRLXCompletionHelper;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;

public class DrlxLspDocumentService implements TextDocumentService {

    private final Map<String, String> sourcesMap = new ConcurrentHashMap<>();

    private final DrlxLspServer server;

    public DrlxLspDocumentService(DrlxLspServer server) {
        this.server = server;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        sourcesMap.put(params.getTextDocument().getUri(), params.getTextDocument().getText());
        CompletableFuture.runAsync(() ->
                server.getClient().publishDiagnostics(
                        new PublishDiagnosticsParams(params.getTextDocument().getUri(), validate())
                )
        );
    }

    private List<Diagnostic> validate() {
        // TODO: Implement DRLX validation
        return Collections.emptyList();
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        sourcesMap.put(params.getTextDocument().getUri(), params.getContentChanges().get(0).getText());
        CompletableFuture.runAsync(() ->
                server.getClient().publishDiagnostics(
                        new PublishDiagnosticsParams(params.getTextDocument().getUri(), validate())
                )
        );
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams completionParams) {
        return CompletableFuture.supplyAsync(() -> Either.forLeft(attempt(() -> getCompletionItems(completionParams))));
    }

    private <T> T attempt(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            server.getClient().showMessage(new MessageParams(MessageType.Error, e.toString()));
        }
        return null;
    }

    public List<CompletionItem> getCompletionItems(CompletionParams completionParams) {
        String text = sourcesMap.get(completionParams.getTextDocument().getUri());

        Position caretPosition = completionParams.getPosition();
        List<CompletionItem> completionItems = DRLXCompletionHelper.getCompletionItems(text, caretPosition);

        server.getClient().showMessage(new MessageParams(MessageType.Info, "Position=" + caretPosition));
        server.getClient().showMessage(new MessageParams(MessageType.Info, "completionItems = " + completionItems));

        return completionItems;
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        sourcesMap.remove(params.getTextDocument().getUri());
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // No-op for now
    }
}
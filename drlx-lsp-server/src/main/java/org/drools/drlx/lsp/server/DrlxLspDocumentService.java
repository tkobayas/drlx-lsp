package org.drools.drlx.lsp.server;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.drools.drlx.completion.DRLXCompletionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import static org.drools.drlx.completion.DRLXCompletionHelper.completionItemStrings;

public class DrlxLspDocumentService implements TextDocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DrlxLspDocumentService.class);

    private final Map<String, String> sourcesMap = new ConcurrentHashMap<>();

    private final DrlxLspServer server;

    public DrlxLspDocumentService(DrlxLspServer server) {
        this.server = server;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String text = params.getTextDocument().getText();
        logger.info("Document opened: {}", uri);
        logger.debug("Document content length: {}", text.length());

        sourcesMap.put(uri, text);
        CompletableFuture.runAsync(() ->
                                           server.getClient().publishDiagnostics(
                                                   new PublishDiagnosticsParams(uri, validate())
                                           )
        );
    }

    private List<Diagnostic> validate() {
        // TODO: Implement DRLX validation
        return Collections.emptyList();
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String newText = params.getContentChanges().get(0).getText();
        logger.debug("Document changed: {}", uri);
        logger.trace("New content length: {}", newText.length());

        sourcesMap.put(uri, newText);
        CompletableFuture.runAsync(() ->
                                           server.getClient().publishDiagnostics(
                                                   new PublishDiagnosticsParams(uri, validate())
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
            logger.error("Error during operation", e);
            server.getClient().showMessage(new MessageParams(MessageType.Error, e.toString()));
        }
        return null;
    }

    public List<CompletionItem> getCompletionItems(CompletionParams completionParams) {
        String uri = completionParams.getTextDocument().getUri();
        String text = sourcesMap.get(uri);
        Position caretPosition = completionParams.getPosition();

        logger.info("Completion requested for {} at position {}:{}", uri, caretPosition.getLine(), caretPosition.getCharacter());
        logger.debug("Document text length: {}", text != null ? text.length() : 0);

        List<CompletionItem> completionItems = DRLXCompletionHelper.getCompletionItems(text, caretPosition);

        server.getClient().showMessage(new MessageParams(MessageType.Info, "Position=[" + caretPosition.getLine() + "," + caretPosition.getCharacter() + "]"));
        server.getClient().showMessage(new MessageParams(MessageType.Info, "completionItems = " + completionItemStrings(completionItems)));

        logger.info("Found {} completion items", completionItems.size());
        if (logger.isDebugEnabled()) {
            logger.debug("Completion items: {}", completionItemStrings(completionItems));
        }

        return completionItems;
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        logger.info("Document closed: {}", uri);
        sourcesMap.remove(uri);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        logger.info("Document saved: {}", uri);
        // No-op for now
    }
}
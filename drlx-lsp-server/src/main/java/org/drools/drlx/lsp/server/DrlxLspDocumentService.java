package org.drools.drlx.lsp.server;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.drools.drlx.completion.DrlxCompletionHelper;
import org.drools.drlx.completion.DrlxDefinitionHelper;
import org.drools.drlx.completion.DrlxDiagnosticHelper;
import org.drools.drlx.completion.DrlxHoverHelper;
import org.drools.drlx.completion.semantic.MemberCompletionProvider;
import org.drools.drlx.completion.semantic.SentinelExpressionTypeResolver;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;

import static org.drools.drlx.completion.DrlxCompletionHelper.completionItemStrings;

public class DrlxLspDocumentService implements TextDocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DrlxLspDocumentService.class);

    private final Map<String, String> sourcesMap = new ConcurrentHashMap<>();

    private final DrlxLspServer server;
    private final WorkspaceSemanticModel model;
    private final DrlxCompletionHelper completionHelper;

    public DrlxLspDocumentService(DrlxLspServer server, WorkspaceSemanticModel model) {
        this.server = server;
        this.model = model;
        this.completionHelper = new DrlxCompletionHelper(
                model,
                new SentinelExpressionTypeResolver(),
                new MemberCompletionProvider());
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
                                                   new PublishDiagnosticsParams(uri, validate(uri))
                                           )
        );
    }

    private List<Diagnostic> validate(String uri) {
        String text = sourcesMap.get(uri);
        if (text == null) {
            return Collections.emptyList();
        }
        return DrlxDiagnosticHelper.validate(text);
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
                                                   new PublishDiagnosticsParams(uri, validate(uri))
                                           )
        );
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            String text = sourcesMap.get(uri);
            if (text == null) return null;
            return DrlxHoverHelper.hover(text, params.getPosition(), model);
        });
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            String text = sourcesMap.get(uri);
            if (text == null) return Either.forLeft(Collections.emptyList());
            List<Location> locations = DrlxDefinitionHelper.definition(uri, text, params.getPosition(), model);
            return Either.forLeft(locations);
        });
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

        List<CompletionItem> completionItems = completionHelper.getCompletionItems(text, caretPosition);

        for (String diag : completionHelper.lastDiagnostics()) {
            server.getClient().showMessage(new MessageParams(MessageType.Warning, diag));
        }

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
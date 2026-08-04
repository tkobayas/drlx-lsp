package org.drools.drlx.lsp.server;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

import org.drools.drlx.completion.semantic.CurrentClassloaderProvider;
import org.drools.drlx.completion.semantic.WorkspaceSemanticModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.WorkspaceService;

public class DrlxLspServer implements LanguageServer, LanguageClientAware {

    private static final Logger logger = LoggerFactory.getLogger(DrlxLspServer.class);

    private final DrlxLspDocumentService textService;
    private final WorkspaceService workspaceService;
    private final WorkspaceSemanticModel model;

    private LanguageClient client;

    public DrlxLspServer() {
        model = new WorkspaceSemanticModel(new CurrentClassloaderProvider());
        textService = new DrlxLspDocumentService(this, model);
        workspaceService = new DrlxLspWorkspaceService();
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    public LanguageClient getClient() {
        return client;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        String rootUri = params.getRootUri();
        logger.info("initialize: rootUri={}", rootUri);
        if (rootUri != null) {
            try {
                Path workspaceRoot = Paths.get(URI.create(rootUri));
                logger.info("initialize: workspaceRoot={}", workspaceRoot);
                CurrentClassloaderProvider provider = new CurrentClassloaderProvider(workspaceRoot);
                logger.info("initialize: classpathEntries={}", provider.classpathEntries());
                model.rebuild(provider);
            } catch (Exception e) {
                logger.error("initialize: failed to rebuild with workspace root", e);
            }
        }

        final InitializeResult initializeResult = new InitializeResult(new ServerCapabilities());
        initializeResult.getCapabilities().setTextDocumentSync(TextDocumentSyncKind.Full);
        CompletionOptions completionOptions = new CompletionOptions();
        initializeResult.getCapabilities().setCompletionProvider(completionOptions);
        return CompletableFuture.supplyAsync(() -> initializeResult);
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public DrlxLspDocumentService getTextDocumentService() {
        return textService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }
}
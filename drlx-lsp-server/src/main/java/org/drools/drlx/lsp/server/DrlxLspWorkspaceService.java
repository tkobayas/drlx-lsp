package org.drools.drlx.lsp.server;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

public class DrlxLspWorkspaceService implements WorkspaceService {

    static final long DEBOUNCE_DELAY_MS = 1000;

    private final DrlxLspServer server;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "drlx-lsp-rebuild");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pendingRebuild;

    DrlxLspWorkspaceService(DrlxLspServer server) {
        this.server = server;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        ScheduledFuture<?> existing = pendingRebuild;
        if (existing != null) {
            existing.cancel(false);
        }
        pendingRebuild = scheduler.schedule(
                () -> server.rebuildClassIndex(),
                DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    void shutdown() {
        scheduler.shutdownNow();
    }
}

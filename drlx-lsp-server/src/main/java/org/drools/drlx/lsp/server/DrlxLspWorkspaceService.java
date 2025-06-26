package org.drools.drlx.lsp.server;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

public class DrlxLspWorkspaceService implements WorkspaceService {
    
    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // No-op for now
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        // No-op for now
    }
}
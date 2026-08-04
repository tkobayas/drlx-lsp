package org.drools.drlx.lsp.server;

import java.nio.file.Path;
import java.util.Set;

import org.drools.drlx.completion.semantic.ClasspathProvider;

public class MavenClasspathProvider implements ClasspathProvider {

    private final Set<Path> entries;

    public MavenClasspathProvider(Set<Path> entries) {
        this.entries = entries;
    }

    @Override
    public Set<Path> classpathEntries() {
        return entries;
    }
}

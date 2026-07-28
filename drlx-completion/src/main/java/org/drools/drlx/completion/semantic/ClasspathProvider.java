package org.drools.drlx.completion.semantic;

import java.nio.file.Path;
import java.util.Set;

public interface ClasspathProvider {
    Set<Path> classpathEntries();
}

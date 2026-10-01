package org.drools.drlx.completion.semantic;

import java.nio.file.Path;
import java.util.List;

public interface WorkspaceSiblingResolver {

    List<Path> resolveSiblings(Path currentFile);
}

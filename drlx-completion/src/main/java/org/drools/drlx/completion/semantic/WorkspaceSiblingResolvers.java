package org.drools.drlx.completion.semantic;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorkspaceSiblingResolvers {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceSiblingResolvers.class);

    private static final WorkspaceSiblingResolver SAME_DIRECTORY =
            WorkspaceSiblingResolvers::sameDirectorySiblings;

    private static volatile WorkspaceSiblingResolver active = SAME_DIRECTORY;

    private WorkspaceSiblingResolvers() {
    }

    public static WorkspaceSiblingResolver active() {
        return active;
    }

    public static void setActive(WorkspaceSiblingResolver resolver) {
        active = (resolver == null) ? SAME_DIRECTORY : resolver;
    }

    private static List<Path> sameDirectorySiblings(Path currentFile) {
        if (currentFile == null) {
            return Collections.emptyList();
        }
        Path dir = currentFile.toAbsolutePath().getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        Path normalizedCurrent = currentFile.toAbsolutePath().normalize();
        List<Path> siblings = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.drlx")) {
            for (Path candidate : stream) {
                if (!candidate.toAbsolutePath().normalize().equals(normalizedCurrent)) {
                    siblings.add(candidate);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve sibling DRLX files for {}", currentFile, e);
            return Collections.emptyList();
        }
        siblings.sort(Path::compareTo);
        return siblings;
    }
}

package org.drools.drlx.completion.semantic;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CurrentClassloaderProvider implements ClasspathProvider {

    private static final Logger logger = LoggerFactory.getLogger(CurrentClassloaderProvider.class);

    private Path workspaceRoot;

    public CurrentClassloaderProvider() {
    }

    public CurrentClassloaderProvider(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    public Set<Path> classpathEntries() {
        Set<Path> entries = new LinkedHashSet<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ClassLoader.getSystemClassLoader();
        }
        while (cl != null) {
            if (cl instanceof URLClassLoader urlCl) {
                for (URL url : urlCl.getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        try {
                            entries.add(Paths.get(url.toURI()));
                        } catch (Exception e) {
                            // skip malformed URIs
                        }
                    }
                }
            }
            cl = cl.getParent();
        }
        if (entries.isEmpty()) {
            String cp = System.getProperty("java.class.path", "");
            if (!cp.isEmpty()) {
                for (String entry : cp.split(System.getProperty("path.separator"))) {
                    entries.add(Paths.get(entry));
                }
            }
        }
        if (workspaceRoot != null) {
            Path targetClasses = workspaceRoot.resolve("target/classes");
            logger.info("workspaceRoot={}, targetClasses={}, exists={}", workspaceRoot, targetClasses, Files.isDirectory(targetClasses));
            if (Files.isDirectory(targetClasses)) {
                entries.add(targetClasses);
            }
            Path targetDependency = workspaceRoot.resolve("target/dependency");
            if (Files.isDirectory(targetDependency)) {
                try (DirectoryStream<Path> jars = Files.newDirectoryStream(targetDependency, "*.jar")) {
                    for (Path jar : jars) {
                        entries.add(jar);
                    }
                } catch (IOException e) {
                    logger.debug("Cannot scan target/dependency: {}", e.getMessage());
                }
            }
        }
        logger.info("classpathEntries result: {}", entries);
        return entries;
    }
}

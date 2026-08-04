package org.drools.drlx.lsp.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MavenClasspathResolver {

    private static final Logger logger = LoggerFactory.getLogger(MavenClasspathResolver.class);

    private MavenClasspathResolver() {
    }

    public static Set<Path> resolve(Path workspaceRoot) {
        Set<Path> entries = new LinkedHashSet<>();

        Path targetClasses = workspaceRoot.resolve("target/classes");
        if (Files.isDirectory(targetClasses)) {
            entries.add(targetClasses);
        }

        resolveDependencyClasspath(workspaceRoot, entries);

        return entries;
    }

    public static Set<Path> resolveBuildOutputDirs(Path workspaceRoot) {
        Set<Path> dirs = new LinkedHashSet<>();
        Path targetClasses = workspaceRoot.resolve("target/classes");
        if (Files.isDirectory(targetClasses)) {
            dirs.add(targetClasses);
        }
        return dirs;
    }

    private static void resolveDependencyClasspath(Path workspaceRoot, Set<Path> entries) {
        Path pomFile = workspaceRoot.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            logger.warn("No pom.xml found at {}", workspaceRoot);
            return;
        }

        Path cpFile;
        try {
            cpFile = Files.createTempFile("drlx-lsp-cp-", ".txt");
        } catch (IOException e) {
            logger.warn("Failed to create temp file for classpath resolution", e);
            return;
        }
        try {
            String mvnCommand = System.getProperty("os.name").toLowerCase().contains("win")
                    ? "mvn.cmd" : "mvn";
            ProcessBuilder pb = new ProcessBuilder(
                    mvnCommand, "-f", pomFile.toString(),
                    "dependency:build-classpath",
                    "-Dmdep.outputFile=" + cpFile.toAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // drain stdout to prevent blocking
                }
            }

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.warn("mvn dependency:build-classpath timed out for {}", workspaceRoot);
                return;
            }
            if (process.exitValue() != 0) {
                logger.warn("mvn dependency:build-classpath failed for {} (exit code {})",
                        workspaceRoot, process.exitValue());
                return;
            }

            String cpContent = Files.readString(cpFile).trim();
            if (!cpContent.isEmpty()) {
                Arrays.stream(cpContent.split(File.pathSeparator))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Path::of)
                        .forEach(entries::add);
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve dependency classpath for {}: {}",
                    workspaceRoot, e.getMessage());
        } finally {
            try { Files.deleteIfExists(cpFile); } catch (IOException ignored) {}
        }
    }
}

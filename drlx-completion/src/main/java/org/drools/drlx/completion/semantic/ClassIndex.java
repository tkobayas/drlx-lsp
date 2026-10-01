package org.drools.drlx.completion.semantic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClassIndex {

    private static final Logger logger = LoggerFactory.getLogger(ClassIndex.class);

    private final Map<String, List<String>> index;

    private ClassIndex(Map<String, List<String>> index) {
        this.index = index;
    }

    public static ClassIndex empty() {
        return new ClassIndex(new HashMap<>());
    }

    public static ClassIndex build(Set<Path> classpathEntries) {
        Map<String, List<String>> index = new HashMap<>();
        for (Path entry : classpathEntries) {
            if (Files.isDirectory(entry)) {
                scanDirectory(entry, index);
            } else if (entry.toString().endsWith(".jar") && Files.isRegularFile(entry)) {
                scanJar(entry, index);
            }
        }
        return new ClassIndex(index);
    }

    public static ClassIndex merge(ClassIndex base, ClassIndex overlay) {
        Map<String, List<String>> merged = new HashMap<>(base.index);
        for (Map.Entry<String, List<String>> entry : overlay.index.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), (a, b) -> {
                List<String> combined = new ArrayList<>(a);
                combined.addAll(b);
                return combined;
            });
        }
        return new ClassIndex(merged);
    }

    public Set<String> simpleNames() {
        return Collections.unmodifiableSet(index.keySet());
    }

    public List<String> getBySimpleName(String simpleName) {
        List<String> fqcns = index.get(simpleName);
        return fqcns != null ? Collections.unmodifiableList(fqcns) : Collections.emptyList();
    }

    public boolean containsSimpleName(String simpleName) {
        return index.containsKey(simpleName);
    }

    public boolean containsFqcn(String fqcn) {
        if (fqcn == null || fqcn.isEmpty()) {
            return false;
        }
        String simpleName = fqcn.contains(".") ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
        List<String> fqcns = index.get(simpleName);
        return fqcns != null && fqcns.contains(fqcn);
    }

    public List<String> getMatching(String prefix) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : index.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.addAll(entry.getValue());
            }
        }
        result.sort(String::compareTo);
        return result;
    }

    public List<String> getAll() {
        return getMatching("");
    }

    public int size() {
        return index.values().stream().mapToInt(List::size).sum();
    }

    private static void scanDirectory(Path dir, Map<String, List<String>> index) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                .forEach(p -> {
                    String relative = dir.relativize(p).toString();
                    addClassEntry(relative, index);
                });
        } catch (IOException e) {
            logger.warn("Failed to scan directory: {}", dir, e);
        }
    }

    private static void scanJar(Path jarPath, Map<String, List<String>> index) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            jar.stream()
                .map(JarEntry::getName)
                .filter(name -> name.endsWith(".class"))
                .forEach(name -> addClassEntry(name, index));
        } catch (IOException e) {
            logger.warn("Failed to scan JAR: {}", jarPath, e);
        }
    }

    private static void addClassEntry(String path, Map<String, List<String>> index) {
        String fqcn = path.replace('/', '.').replace('\\', '.');
        if (fqcn.endsWith(".class")) {
            fqcn = fqcn.substring(0, fqcn.length() - ".class".length());
        }

        String simpleName = fqcn.contains(".") ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
        if (simpleName.contains("$") || simpleName.equals("module-info") || simpleName.equals("package-info")) {
            return;
        }

        index.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(fqcn);
    }
}

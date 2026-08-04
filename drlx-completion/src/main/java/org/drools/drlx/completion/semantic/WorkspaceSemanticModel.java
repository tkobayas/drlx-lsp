package org.drools.drlx.completion.semantic;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.antlr.v4.runtime.tree.ParseTree;
import org.drools.drlx.parser.DrlxParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkspaceSemanticModel implements WorkspaceTypes {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceSemanticModel.class);

    private CombinedTypeSolver typeSolver;
    private ClassLoader projectClassLoader;

    public WorkspaceSemanticModel(ClasspathProvider classpathProvider) {
        rebuild(classpathProvider);
    }

    public TypeSolver typeSolver() {
        return typeSolver;
    }

    public ClassLoader projectClassLoader() {
        return projectClassLoader;
    }

    public CompletionContext createContext(DrlxParser parser, ParseTree tree, int caretTokenIndex) {
        return new CompletionContext(this, parser, tree, caretTokenIndex);
    }

    public void rebuild(ClasspathProvider classpathProvider) {
        this.projectClassLoader = buildClassLoader(classpathProvider.classpathEntries());
        this.typeSolver = buildTypeSolver(projectClassLoader);
    }

    private static CombinedTypeSolver buildTypeSolver(ClassLoader classLoader) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver(false));
        if (classLoader != null && classLoader != ClassLoader.getSystemClassLoader()) {
            solver.add(new ClassLoaderTypeSolver(classLoader));
        }
        return solver;
    }

    private static ClassLoader buildClassLoader(Set<Path> entries) {
        List<URL> urls = new ArrayList<>();
        for (Path entry : entries) {
            try {
                urls.add(entry.toUri().toURL());
            } catch (MalformedURLException e) {
                logger.debug("Skipping classpath entry '{}': {}", entry, e.getMessage());
            }
        }
        logger.info("buildClassLoader: {} URLs from {} entries: {}", urls.size(), entries.size(), urls);
        if (urls.isEmpty()) {
            logger.info("buildClassLoader: no URLs, falling back to system classloader");
            return ClassLoader.getSystemClassLoader();
        }
        URLClassLoader cl = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getSystemClassLoader());
        logger.info("buildClassLoader: created URLClassLoader with {} URLs", urls.size());
        return cl;
    }
}

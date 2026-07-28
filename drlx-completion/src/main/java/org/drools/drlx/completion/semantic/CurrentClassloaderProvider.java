package org.drools.drlx.completion.semantic;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

public class CurrentClassloaderProvider implements ClasspathProvider {

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
        return entries;
    }
}

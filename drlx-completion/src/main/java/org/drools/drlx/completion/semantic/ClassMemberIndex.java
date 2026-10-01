package org.drools.drlx.completion.semantic;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClassMemberIndex implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ClassMemberIndex.class);

    private static final ClassMemberIndex EMPTY = new ClassMemberIndex((ClassLoader) null);

    private final ClassLoader loader;
    private final boolean ownsLoader;
    private final Map<String, List<Field>> cache = new ConcurrentHashMap<>();

    public ClassMemberIndex(ClassLoader loader) {
        this(loader, false);
    }

    private ClassMemberIndex(ClassLoader loader, boolean ownsLoader) {
        this.loader = loader;
        this.ownsLoader = ownsLoader;
    }

    public static ClassMemberIndex empty() {
        return EMPTY;
    }

    public static ClassMemberIndex of(Set<Path> classpathEntries) {
        if (classpathEntries == null || classpathEntries.isEmpty()) {
            return EMPTY;
        }
        List<URL> urls = new ArrayList<>(classpathEntries.size());
        for (Path entry : classpathEntries) {
            try {
                urls.add(entry.toUri().toURL());
            } catch (Exception e) {
                logger.debug("Skipping classpath entry {}: {}", entry, e.getMessage());
            }
        }
        URLClassLoader loader = new URLClassLoader(
                urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        return new ClassMemberIndex(loader, true);
    }

    public List<Field> membersOf(String fqcn) {
        if (loader == null || fqcn == null || fqcn.isEmpty()) {
            return Collections.emptyList();
        }
        return cache.computeIfAbsent(fqcn, this::reflectMembers);
    }

    public Set<String> memberNames(String fqcn) {
        if (loader == null || fqcn == null || fqcn.isEmpty()) {
            return null;
        }
        Class<?> clazz;
        try {
            clazz = Class.forName(fqcn, false, loader);
        } catch (Throwable t) {
            return null;
        }
        try {
            Set<String> names = new LinkedHashSet<>();
            for (java.lang.reflect.Field f : clazz.getFields()) {
                names.add(f.getName());
            }
            for (Class<?> nested : clazz.getClasses()) {
                names.add(nested.getSimpleName());
            }
            return names;
        } catch (Throwable t) {
            logger.debug("Failed to reflect member names of {}", fqcn, t);
            return null;
        }
    }

    @Override
    public void close() {
        cache.clear();
        if (ownsLoader && loader instanceof java.io.Closeable closeable) {
            try {
                closeable.close();
            } catch (java.io.IOException e) {
                logger.debug("Failed to close class member index loader", e);
            }
        }
    }

    private List<Field> reflectMembers(String fqcn) {
        Class<?> clazz;
        try {
            clazz = Class.forName(fqcn, false, loader);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        try {
            Map<String, Field> members = new LinkedHashMap<>();
            if (clazz.isEnum()) {
                for (java.lang.reflect.Field f : clazz.getFields()) {
                    if (f.isEnumConstant()) {
                        members.put(f.getName(),
                                new Field(f.getName(), clazz.getName()));
                    }
                }
            }
            for (Method m : clazz.getMethods()) {
                String property = propertyNameOf(m);
                if (property != null) {
                    members.putIfAbsent(property,
                            new Field(property, m.getReturnType().getName()));
                }
            }
            for (java.lang.reflect.Field f : clazz.getFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    members.putIfAbsent(f.getName(),
                            new Field(f.getName(), f.getType().getName()));
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(members.values()));
        } catch (Throwable t) {
            logger.debug("Failed to reflect members of {}", fqcn, t);
            return Collections.emptyList();
        }
    }

    private static String propertyNameOf(Method m) {
        if (m.getParameterCount() != 0 || m.getReturnType() == void.class
                || Modifier.isStatic(m.getModifiers())) {
            return null;
        }
        String name = m.getName();
        String raw;
        if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) {
            if ("getClass".equals(name)) {
                return null;
            }
            raw = name.substring(3);
        } else if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) {
            Class<?> returnType = m.getReturnType();
            if (returnType != boolean.class && returnType != Boolean.class) {
                return null;
            }
            raw = name.substring(2);
        } else {
            return null;
        }
        if (raw.length() > 1 && Character.isUpperCase(raw.charAt(0)) && Character.isUpperCase(raw.charAt(1))) {
            return raw;
        }
        char[] chars = raw.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }
}

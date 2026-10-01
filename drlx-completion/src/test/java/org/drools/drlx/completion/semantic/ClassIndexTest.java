package org.drools.drlx.completion.semantic;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ClassIndexTest {

    @Test
    void emptyIndex() {
        ClassIndex index = ClassIndex.empty();
        assertThat(index.size()).isEqualTo(0);
        assertThat(index.getAll()).isEmpty();
        assertThat(index.simpleNames()).isEmpty();
        assertThat(index.containsSimpleName("Person")).isFalse();
        assertThat(index.containsFqcn("com.example.Person")).isFalse();
        assertThat(index.getBySimpleName("Person")).isEmpty();
    }

    @Test
    void scanDirectoryAndExactLookup(@TempDir Path tempDir) throws IOException {
        Path pkgDir = tempDir.resolve("com/example");
        Files.createDirectories(pkgDir);
        Files.createFile(pkgDir.resolve("Person.class"));
        Files.createFile(pkgDir.resolve("Address.class"));
        Files.createFile(pkgDir.resolve("Person$Inner.class")); // should be ignored
        Files.createFile(tempDir.resolve("module-info.class")); // should be ignored

        ClassIndex index = ClassIndex.build(Set.of(tempDir));

        assertThat(index.size()).isEqualTo(2);
        assertThat(index.simpleNames()).containsExactlyInAnyOrder("Person", "Address");
        assertThat(index.containsSimpleName("Person")).isTrue();
        assertThat(index.containsSimpleName("Address")).isTrue();
        assertThat(index.containsSimpleName("Perso")).isFalse(); // exact match only
        assertThat(index.containsSimpleName("Person$Inner")).isFalse();

        assertThat(index.containsFqcn("com.example.Person")).isTrue();
        assertThat(index.containsFqcn("com.example.Perso")).isFalse();
        assertThat(index.containsFqcn("org.example.Person")).isFalse();

        assertThat(index.getBySimpleName("Person")).containsExactly("com.example.Person");
        assertThat(index.getBySimpleName("Perso")).isEmpty();

        // prefix matching check
        assertThat(index.getMatching("Per")).containsExactly("com.example.Person");
    }

    @Test
    void scanJarFile(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("test.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new ZipEntry("org/sample/Order.class"));
            jos.closeEntry();
            jos.putNextEntry(new ZipEntry("org/sample/Customer.class"));
            jos.closeEntry();
            jos.putNextEntry(new ZipEntry("package-info.class"));
            jos.closeEntry();
        }

        ClassIndex index = ClassIndex.build(Set.of(jarPath));

        assertThat(index.size()).isEqualTo(2);
        assertThat(index.simpleNames()).containsExactlyInAnyOrder("Order", "Customer");
        assertThat(index.containsFqcn("org.sample.Order")).isTrue();
        assertThat(index.containsFqcn("org.sample.Customer")).isTrue();
    }

    @Test
    void mergeIndexes(@TempDir Path tempDir) throws IOException {
        Path dir1 = tempDir.resolve("dir1/com/example");
        Path dir2 = tempDir.resolve("dir2/org/example");
        Files.createDirectories(dir1);
        Files.createDirectories(dir2);
        Files.createFile(dir1.resolve("Person.class"));
        Files.createFile(dir2.resolve("Person.class")); // same simple name, different package

        ClassIndex index1 = ClassIndex.build(Set.of(tempDir.resolve("dir1")));
        ClassIndex index2 = ClassIndex.build(Set.of(tempDir.resolve("dir2")));
        ClassIndex merged = ClassIndex.merge(index1, index2);

        assertThat(merged.size()).isEqualTo(2);
        assertThat(merged.getBySimpleName("Person"))
                .containsExactlyInAnyOrder("com.example.Person", "org.example.Person");
    }
}

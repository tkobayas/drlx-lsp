package org.drools.drlx.completion.semantic;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class CurrentClassloaderProviderTest {

    @Test
    void classpathEntriesReturnsNonEmptySet() {
        CurrentClassloaderProvider provider = new CurrentClassloaderProvider();
        Set<Path> entries = provider.classpathEntries();
        assertThat(entries).isNotEmpty();
    }

    @Test
    void classpathEntriesContainsExistingPaths() {
        CurrentClassloaderProvider provider = new CurrentClassloaderProvider();
        Set<Path> entries = provider.classpathEntries();
        assertThat(entries).anyMatch(p -> p.toFile().exists());
    }
}

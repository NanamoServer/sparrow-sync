package net.momirealms.sparrow.sync.command.feature.debug;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SnapshotUtilsTest {

    @Test
    void resolveInsideAcceptsRelativePathsAndRejectsEscapes() {
        Path directory = Path.of("plugins", "SparrowSync", "debug");

        Path nested = SnapshotUtils.resolveInside(directory, "sub/Steve-20260829.snapshot");
        assertEquals(directory.resolve("sub").resolve("Steve-20260829.snapshot"), nested);
        assertNull(SnapshotUtils.resolveInside(directory, "../config.yml"));
        assertNull(SnapshotUtils.resolveInside(directory, "sub/../../secrets.txt"));
    }
}

package net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerJsonStorageTest {

    @Test
    void materializeCreatesMissingFile(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("advancements").resolve("player.json");
        byte[] json = "{\"DataVersion\":1}".getBytes(StandardCharsets.UTF_8);

        assertTrue(PlayerJsonStorage.materialize(target, json));

        assertEquals(new String(json, StandardCharsets.UTF_8), Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void materializeAtomicallyReplacesCompleteFile(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("stats").resolve("player.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        byte[] replacement = "{\"stats\":{},\"DataVersion\":1}".getBytes(StandardCharsets.UTF_8);

        assertTrue(PlayerJsonStorage.materialize(target, replacement));

        assertEquals(new String(replacement, StandardCharsets.UTF_8), Files.readString(target, StandardCharsets.UTF_8));
        try (var files = Files.list(target.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void failedAtomicMovePreservesTargetAndCleansTemporaryFile(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("stats").resolve("player.json");
        Files.createDirectories(target);
        Files.writeString(target.resolve("sentinel"), "old", StandardCharsets.UTF_8);

        assertFalse(PlayerJsonStorage.materialize(target, "new".getBytes(StandardCharsets.UTF_8)));

        assertEquals("old", Files.readString(target.resolve("sentinel"), StandardCharsets.UTF_8));
        try (var files = Files.list(target.getParent())) {
            assertEquals(1, files.count());
        }
    }
}

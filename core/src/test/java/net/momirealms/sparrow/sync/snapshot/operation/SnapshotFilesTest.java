package net.momirealms.sparrow.sync.snapshot.operation;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.snapshot.*;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotFilesTest {
    @TempDir Path directory;

    @BeforeAll
    static void initialize() {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), List.of("paper"));
    }

    @ParameterizedTest
    @EnumSource(SnapshotFiles.Format.class)
    void exportsBothFormatsToSenderDirectoriesAndOverwritesSameId(SnapshotFiles.Format format) throws Exception {
        SnapshotFiles files = new SnapshotFiles(this.directory, new BinarySnapshotCodec(CompressorRegistry.NONE));
        Snapshot original = snapshot(UUID.randomUUID());
        String console = files.export(original, format, false);
        assertTrue(console.startsWith("snapshot/" + original.meta().player() + "/"));
        String player = files.export(original, format, true);
        assertTrue(player.startsWith("snapshot/output/" + original.meta().player() + "/" + original.meta().id() + "."));
        assertEquals(original, assertInstanceOf(DecodedSnapshot.Valid.class, files.read(player.substring("snapshot/".length()))).snapshot());
        Snapshot updated = new Snapshot(original.meta().withPinned(false), original.data());
        assertEquals(player, files.export(updated, format, true));
        assertEquals(updated, assertInstanceOf(DecodedSnapshot.Valid.class, files.read(player.substring("snapshot/".length()))).snapshot());
        assertEquals(original, assertInstanceOf(DecodedSnapshot.Valid.class, files.read(console.substring("snapshot/".length()))).snapshot());
    }

    @Test
    void invalidPathsAndUnsupportedSuffixesAreRefused() throws Exception {
        SnapshotFiles files = new SnapshotFiles(this.directory, new BinarySnapshotCodec(CompressorRegistry.NONE));
        Path nested = this.directory.resolve("snapshot/nested/folder with spaces");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("one.json"), "invalid");
        Files.writeString(nested.resolve("two.snapshot"), "invalid");
        Files.writeString(nested.resolve("ignore.yml"), "config");
        assertThrows(IOException.class, () -> files.read("../config.yml"));
        assertThrows(IOException.class, () -> files.read("nested/folder with spaces/ignore.yml"));
        assertThrows(IOException.class, () -> files.read("missing.json"));
    }

    @Test
    void exceptionDeletionDoesNotNeedToDecodeTheBody() throws Exception {
        SnapshotFiles files = new SnapshotFiles(this.directory, new BinarySnapshotCodec(CompressorRegistry.NONE));
        Path file = this.directory.resolve("exception/corrupted/broken.snapshot");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "broken body");
        assertTrue(files.deleteException("corrupted/broken.snapshot"));
        assertFalse(files.deleteException("corrupted/broken.snapshot"));
        assertThrows(IOException.class, () -> files.deleteException("../config.yml"));
    }

    static Snapshot snapshot(UUID player) {
        CompoundTag data = NBT.createCompound();
        data.putString("value", "kept");
        return new Snapshot(new SnapshotMeta(UUID.randomUUID(), player, 1234, SaveCause.COMMAND, true, "source", 0),
                Map.of(DataKey.of("unknown", "payload"), data));
    }
}

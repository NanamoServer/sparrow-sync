package net.momirealms.sparrow.sync.snapshot.local;

import net.momirealms.sparrow.sync.test.SnapshotFileTestLogger;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.jetbrains.annotations.ApiStatus;
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

@ApiStatus.Internal
public class SnapshotFilesTest {
    @TempDir Path directory;

    @BeforeAll
    static void initialize() {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), List.of("paper"));
    }

    @ParameterizedTest
    @EnumSource(SnapshotFiles.Format.class)
    void exportsBothFormatsToSharedOutputAndOverwritesSameId(SnapshotFiles.Format format) throws Exception {
        SnapshotFiles files = new SnapshotFiles(this.directory, new BinarySnapshotCodec(CompressorRegistry.NONE), new SnapshotFileTestLogger());
        Snapshot original = snapshot(UUID.randomUUID());
        String output = files.export(original, format);
        assertTrue(output.startsWith("snapshot/output/" + original.meta().player() + "/" + original.meta().id() + "."));
        assertEquals(original, assertInstanceOf(DecodedSnapshot.Valid.class, files.read(output.substring("snapshot/output/".length()))).snapshot());
        Snapshot updated = new Snapshot(original.meta().withPinned(false), original.allData());
        assertEquals(output, files.export(updated, format));
        assertEquals(updated, assertInstanceOf(DecodedSnapshot.Valid.class, files.read(output.substring("snapshot/output/".length()))).snapshot());
    }

    @Test
    void invalidPathsAndUnsupportedSuffixesAreRefused() throws Exception {
        SnapshotFiles files = new SnapshotFiles(this.directory, new BinarySnapshotCodec(CompressorRegistry.NONE), new SnapshotFileTestLogger());
        Path nested = this.directory.resolve("snapshot/output/nested/folder with spaces");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("one.json"), "invalid");
        Files.writeString(nested.resolve("two.snapshot"), "invalid");
        Files.writeString(nested.resolve("ignore.yml"), "config");
        assertThrows(IOException.class, () -> files.read("../config.yml"));
        assertThrows(IOException.class, () -> files.read("../pending/one.snapshot"));
        assertThrows(IOException.class, () -> files.read("../exception/one.snapshot"));
        assertThrows(IOException.class, () -> files.read("backup.zip"));
        assertThrows(IOException.class, () -> files.read("nested/folder with spaces/ignore.yml"));
        assertThrows(IOException.class, () -> files.read("missing.json"));
    }

    @Test
    void exceptionDeletionDoesNotNeedToDecodeTheBody() throws Exception {
        SnapshotFiles files = new SnapshotFiles(this.directory, new BinarySnapshotCodec(CompressorRegistry.NONE), new SnapshotFileTestLogger());
        Path file = this.directory.resolve("snapshot/exception/corrupted/broken.snapshot");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "broken body");
        assertTrue(files.deleteException("corrupted/broken.snapshot"));
        assertFalse(files.deleteException("corrupted/broken.snapshot"));
        assertThrows(IOException.class, () -> files.deleteException("../config.yml"));
    }

    @ApiStatus.Internal
    public static Snapshot snapshot(UUID player) {
        CompoundTag data = NBT.createCompound();
        data.putString("value", "kept");
        return new Snapshot(new SnapshotMeta(UUID.randomUUID(), player, 1234, SaveCause.COMMAND, true, "source", 0),
                Map.of(DataKey.of("unknown", "payload"), data));
    }
}

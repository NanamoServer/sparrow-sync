package net.momirealms.sparrow.sync.plugin.command.feature;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.session.SnapshotService;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.incendo.cloud.suggestion.Suggestion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotFileSuggestionsTest {
    @TempDir Path directory;

    @BeforeAll
    static void initialize() {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), List.of("paper"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eachCommandScansItsOwnDirectoryAndFiltersPaths(boolean exceptions) throws Exception {
        SparrowSync plugin = NmsPlayerFixture.allocate(SparrowSync.class);
        SnapshotService service = new SnapshotService(plugin);
        SnapshotFiles files = new SnapshotFiles(this.directory, new BinarySnapshotCodec(CompressorRegistry.NONE));
        NmsPlayerFixture.set(SnapshotService.class, service, "files", files);
        NmsPlayerFixture.set(SparrowSync.class, plugin, "snapshotService", service);
        Function<String, List<Suggestion>> suggest = exceptions
                ? new ExceptionDeleteCommand(null, plugin)::suggestions
                : new SnapshotImportCommand(null, plugin)::suggestions;
        assertTrue(suggest.apply("").isEmpty());
        Path selected = exceptions ? files.exceptions() : files.directory();
        Path other = exceptions ? files.directory() : files.exceptions();
        Files.createDirectories(other);
        Files.writeString(other.resolve("outside.json"), "other directory");
        Path nested = selected.resolve("nested/folder with spaces");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("one.JSON"), "snapshot");
        Files.writeString(nested.resolve("two.snapshot"), "snapshot");
        Files.writeString(nested.resolve("ignore.yml"), "config");
        assertEquals(List.of(Suggestion.suggestion("nested/folder with spaces/one.JSON"), Suggestion.suggestion("nested/folder with spaces/two.snapshot")), suggest.apply("NESTED/"));
        assertEquals(2, suggest.apply("").size());
        assertTrue(suggest.apply("missing/").isEmpty());
        if (exceptions) {
            Files.writeString(nested.resolve("two.snapshot.head"), "duplicate header");
            Files.writeString(nested.resolve("orphan.snapshot.head"), "header without body");
            assertEquals(3, suggest.apply("").size());
            assertTrue(suggest.apply("").contains(Suggestion.suggestion("nested/folder with spaces/orphan.snapshot")));
        }
    }
}

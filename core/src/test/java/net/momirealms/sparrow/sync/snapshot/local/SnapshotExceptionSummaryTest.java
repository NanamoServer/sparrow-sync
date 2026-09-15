package net.momirealms.sparrow.sync.snapshot.local;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.snapshot.SnapshotDetails;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.JsonSnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.Compressor;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.data.type.ExperienceDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType;
import net.momirealms.sparrow.sync.snapshot.exception.ExceptionHeader;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDetailResult;
import net.momirealms.sparrow.sync.test.SnapshotFileTestLogger;
import net.momirealms.sparrow.sync.test.PluginConfigExtension;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(PluginConfigExtension.class)
class SnapshotExceptionSummaryTest {
    private static final byte COUNTED_DEFLATE = 77;
    private static final AtomicInteger DECOMPRESSIONS = new AtomicInteger();

    @TempDir Path directory;
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE, 0);
    private final SnapshotFileTestLogger logger = new SnapshotFileTestLogger();

    @BeforeAll
    static void initialize() {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), List.of("paper"));
        CompressorRegistry.register(COUNTED_DEFLATE, new Compressor() {
            @Override
            @NotNull
            public byte[] compress(byte @NotNull [] data) throws IOException {
                return CompressorRegistry.DEFLATE.compress(data);
            }

            @Override
            @NotNull
            public byte[] decompress(byte @NotNull [] data, int offset, int length, int sizeLimit) throws IOException {
                DECOMPRESSIONS.incrementAndGet();
                return CompressorRegistry.DEFLATE.decompress(data, offset, length, sizeLimit);
            }
        });
    }

    @BeforeEach
    void resetDecompressionCount() {
        DECOMPRESSIONS.set(0);
    }
    @Test
    void overviewReadsOnlyHeadersAndDoesNotDecodeBlocks() throws Exception {
        SnapshotFiles files = this.files();
        Snapshot snapshot = SnapshotFixtures.snapshot();
        Path body = this.writeCounted(files, snapshot, "corrupted");
        byte[] encoded = Files.readAllBytes(body);
        Snapshot lazy = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(encoded)).snapshot();
        SnapshotDetails details = new SnapshotDetails(null, files, new DataRegistry(), Runnable::run);
        try (FileChannel channel = FileChannel.open(body, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            var page = files.listExceptions(snapshot.meta().player(), "corrupted", 0, 27);
            assertEquals(1, page.total());
            var entry = page.content().getFirst();
            assertEquals(snapshot.keys(), entry.summary().keySet());
            for (DataKey key : snapshot.keys()) {
                assertEquals(lazy.content().rawLength(key), entry.summary().get(key));
            }
            var overview = details.loadException(entry.path()).join();
            assertInstanceOf(SnapshotDetailResult.Overview.class, overview.result());
            assertEquals(entry.summary(), overview.entry().summary());
            assertEquals(0, DECOMPRESSIONS.get());
        }
        assertEquals(0, SnapshotFixtures.decodedBlockCount(lazy));
        Files.delete(body);
        var entry = files.listExceptions(null, null, 0, 27).content().getFirst();
        assertFalse(entry.bodyPresent());
        assertEquals(snapshot.keys(), entry.summary().keySet());
        assertEquals(entry.summary(), details.loadException(entry.path()).join().entry().summary());
        assertEquals(0, DECOMPRESSIONS.get());
    }

    @Test
    void eachPreviewDecodesOnlyItsSelectedType() throws Exception {
        SnapshotFiles files = this.files();
        DataRegistry registry = new DataRegistry();
        registry.register(new ExperienceDataType());
        registry.register(new HealthDataType());
        registry.freeze();
        var experience = new ExperienceDataType.Experience(12, 3, 0.5f);
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of(
                ExperienceDataType.EXPERIENCE, new ExperienceDataType().encode(experience),
                HealthDataType.HEALTH, NBT.createString("bad health")));
        Path body = this.writeCounted(files, snapshot, "malformed");
        SnapshotDetails details = new SnapshotDetails(null, files, registry, Runnable::run);
        var archive = details.loadExceptionBody("malformed/" + body.getFileName()).join();
        var initial = assertInstanceOf(SnapshotDetailResult.Ready.class, archive.result());
        assertEquals(0, SnapshotFixtures.decodedBlockCount(initial.snapshot()));
        assertEquals(0, DECOMPRESSIONS.get());
        assertInstanceOf(SnapshotDetailResult.Preview.Unloaded.class, initial.previews().get(HealthDataType.HEALTH));
        var selected = details.preview(initial, ExperienceDataType.EXPERIENCE).join();
        assertEquals(1, SnapshotFixtures.decodedBlockCount(initial.snapshot()));
        assertEquals(1, DECOMPRESSIONS.get());
        assertEquals(experience, assertInstanceOf(SnapshotDetailResult.Preview.Ready.class, selected.previews().get(ExperienceDataType.EXPERIENCE)).value());
        var next = details.preview(selected, HealthDataType.HEALTH).join();
        assertEquals(2, SnapshotFixtures.decodedBlockCount(initial.snapshot()));
        assertEquals(2, DECOMPRESSIONS.get());
        assertInstanceOf(SnapshotDetailResult.Preview.Failed.class, next.previews().get(HealthDataType.HEALTH));
        assertSame(selected.previews().get(ExperienceDataType.EXPERIENCE), next.previews().get(ExperienceDataType.EXPERIENCE));
        assertSame(next, details.preview(next, ExperienceDataType.EXPERIENCE).join());
    }

    @Test
    void bodyRepairsSummaryWithoutDecodingPayloads() throws Exception {
        SnapshotFiles files = this.files();
        Snapshot source = SnapshotFixtures.snapshot();
        Path body = files.write(source, "Steve", "corrupted");
        var expected = this.codec.summarize(Files.readAllBytes(body));
        new ExceptionHeader(source.meta().withPinned(true), "Steve", Map.of(DataKey.of("test", "wrong"), 99)).write(body);
        String path = "corrupted/" + body.getFileName();
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, files.readException(path)).snapshot();
        ExceptionHeader header = ExceptionHeader.read(body);
        assertEquals(source.meta(), header.meta());
        assertEquals("Steve", header.playerName());
        assertEquals(expected, header.summary());
        assertEquals(0, SnapshotFixtures.decodedBlockCount(restored));
        assertEquals(1, this.logger.infos.size());
        assertTrue(this.logger.infos.getFirst().contains(body.toString()));
        files.readException(path);
        assertEquals(1, this.logger.infos.size());
    }

    @Test
    void headerWriteFailureKeepsTheSavedBodyReadable() throws IOException {
        SnapshotFiles files = this.files();
        Snapshot source = SnapshotFixtures.snapshot();
        Path body = files.write(source, "Steve", "corrupted");
        Path header = ExceptionHeader.path(body);
        Files.delete(header);
        Files.createDirectory(header);
        Files.writeString(header.resolve("occupied"), "keep");
        Snapshot changed = new Snapshot(source.meta(), source.content().with(DataKey.of("test", "new"), NBT.createInt(3)));
        assertThrows(IOException.class, () -> files.write(changed, "Steve", "corrupted"));
        Snapshot restored = assertInstanceOf(DecodedSnapshot.Valid.class, files.readException("corrupted/" + body.getFileName())).snapshot();
        assertEquals(changed, restored);
        assertEquals("keep", Files.readString(header.resolve("occupied")));
        assertEquals(1, this.logger.warnings.size());
    }

    @Test
    void damagedBodyDoesNotHideArchiveIdentity() throws IOException {
        SnapshotFiles files = this.files();
        files.archiveImport(new byte[]{1}, SnapshotFixtures.meta(), "corrupted", "truncated", null);
        var entry = files.listExceptions(null, null, 0, 27).content().getFirst();
        assertTrue(entry.bodyPresent());
        assertEquals(SnapshotFixtures.meta(), entry.header().meta());
        assertNull(entry.summary());
        new ExceptionHeader(entry.header().meta(), "Steve").write(files.exceptionFile(entry.path()));
        entry = files.exceptionEntry(entry.path());
        assertTrue(entry.informationAvailable());
        assertEquals(SnapshotFiles.HeadStatus.AVAILABLE, entry.headStatus());
        assertNull(entry.summary());
    }

    @Test
    void importAndMigrationWriteMachineReadableSummariesAndOriginalStacks() throws IOException {
        SnapshotFiles files = this.files();
        IOException failure = new IOException("bad \"item\"\nsecond line");
        failure.addSuppressed(new IOException("suppressed"));
        files.archiveImport(new byte[]{1}, SnapshotFixtures.meta(), "malformed", "bad import", failure);
        Path imported = files.exceptionFile(files.listExceptions(null, "malformed", 0, 27).content().getFirst().path());
        Document importSummary = this.summary(imported, failure);
        assertEquals("malformed", importSummary.getString("category"));
        assertEquals(SnapshotFixtures.meta().player().toString(), importSummary.getString("player"));
        assertEquals("bad import", importSummary.getString("reason"));
        assertFalse(importSummary.containsKey("source"));
        assertFalse(importSummary.containsKey("playerName"));
        files.archiveMigration(SnapshotFixtures.meta(), "Steve", "husksync", "encode", failure, new byte[]{4, 5});
        var entry = files.listExceptions(null, "migration", 0, 27).content().getFirst();
        assertFalse(entry.bodyPresent());
        assertNull(entry.summary());
        Document migration = this.summary(files.exceptionFile(entry.path()), failure);
        assertEquals("migration", migration.getString("category"));
        assertEquals("Steve", migration.getString("playerName"));
        assertEquals("husksync", migration.getString("source"));
        assertEquals("encode", migration.getString("stage"));
        assertTrue(migration.getBoolean("rawAttached"));
        assertEquals("bad \"item\"", migration.getString("reason"));
    }

    @Test
    void summariesOmitUnavailableFieldsAndReportMissingSourceAttachment() throws IOException {
        SnapshotFiles files = this.files();
        files.archiveImport(new byte[]{1}, null, "corrupted", "bad frame", null);
        var imported = files.listExceptions(null, "corrupted", 0, 27).content().getFirst();
        Document summary = this.summary(files.exceptionFile(imported.path()), null);
        assertEquals(Map.of("category", "corrupted", "reason", "bad frame"), summary);
        IOException failure = new IOException("no source");
        files.archiveMigration(SnapshotFixtures.meta(), null, "invsync", "decode", failure, null);
        var entry = files.listExceptions(null, "migration", 0, 27).content().getFirst();
        Document migration = this.summary(files.exceptionFile(entry.path()), failure);
        assertFalse(migration.containsKey("playerName"));
        assertFalse(migration.getBoolean("rawAttached"));
        assertFalse(entry.bodyPresent());
        assertNull(entry.header().summary());
    }

    private Document summary(Path body, Throwable failure) throws IOException {
        String text = Files.readString(body.resolveSibling(body.getFileName() + ".error.txt"));
        String separator = System.lineSeparator() + System.lineSeparator();
        int split = text.indexOf(separator);
        if (failure == null) {
            assertEquals(-1, split);
            assertEquals(1, text.lines().count());
            return Document.parse(text.stripTrailing());
        }
        assertTrue(split >= 0);
        StringWriter stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        assertEquals(stack.toString(), text.substring(split + separator.length()));
        assertEquals(1, text.substring(0, split).lines().count());
        return Document.parse(text.substring(0, split));
    }

    @Test
    void exceptionEntriesKeepAllThreeSummaryStates() throws IOException {
        SnapshotFiles files = this.files();
        Path directory = files.exceptions().resolve("corrupted");
        Files.createDirectories(directory);
        new ExceptionHeader(null, null).write(directory.resolve("missing.snapshot"));
        new ExceptionHeader(null, null, Map.of()).write(directory.resolve("empty.snapshot"));
        new ExceptionHeader(null, null, Map.of(DataKey.of("test", "data"), -1)).write(directory.resolve("populated.snapshot"));
        assertNull(files.exceptionEntry("corrupted/missing.snapshot").summary());
        assertEquals(Map.of(), files.exceptionEntry("corrupted/empty.snapshot").summary());
        assertEquals(Map.of(DataKey.of("test", "data"), -1), files.exceptionEntry("corrupted/populated.snapshot").summary());
        assertEquals(3, files.listExceptions(null, null, 0, 27).total());
    }

    @Test
    void legacyHeaderIsUnreadableButBodyCanRebuildIt() throws IOException {
        SnapshotFiles files = this.files();
        Path body = files.write(SnapshotFixtures.snapshot(), "Steve", "corrupted");
        Files.write(ExceptionHeader.path(body), ByteBuffer.allocate(4).putInt(0x53534801).array());
        var entry = files.listExceptions(null, null, 0, 27).content().getFirst();
        assertEquals(SnapshotFiles.HeadStatus.UNREADABLE, entry.headStatus());
        assertNull(entry.summary());
        var details = new SnapshotDetails(null, files, new DataRegistry(), Runnable::run);
        assertInstanceOf(SnapshotDetailResult.Overview.class, details.loadException(entry.path()).join().result());
        var loaded = details.loadExceptionBody(entry.path()).join();
        assertInstanceOf(SnapshotDetailResult.Ready.class, loaded.result());
        assertEquals(SnapshotFiles.HeadStatus.AVAILABLE, loaded.entry().headStatus());
        assertEquals(SnapshotFixtures.snapshot().keys(), loaded.entry().summary().keySet());
        assertEquals(1, this.logger.infos.size());
    }

    @Test
    void jsonBodyIsReadExplicitlyAndNeverReencodedForSizes() throws IOException {
        SnapshotFiles files = this.files();
        DataKey key = DataKey.of("external", "json");
        Snapshot snapshot = new Snapshot(SnapshotFixtures.meta(), Map.of(key, NBT.createInt(7)));
        Path body = files.exceptions().resolve("malformed/entry.json");
        Files.createDirectories(body.getParent());
        String json = new JsonSnapshotCodec().encode(snapshot);
        Files.writeString(body, json);
        new ExceptionHeader(snapshot.meta(), "Steve", Map.of(DataKey.of("test", "wrong"), 99)).write(body);
        var details = new SnapshotDetails(null, files, new DataRegistry(), Runnable::run);
        assertInstanceOf(SnapshotDetailResult.Overview.class, details.loadException("malformed/entry.json").join().result());
        var loaded = details.loadExceptionBody("malformed/entry.json").join();
        var ready = assertInstanceOf(SnapshotDetailResult.Ready.class, loaded.result());
        assertEquals(snapshot.keys(), ready.snapshot().keys());
        assertEquals(-1, assertInstanceOf(SnapshotDetailResult.Preview.Unsupported.class, ready.previews().get(key)).rawLength());
        assertNull(loaded.entry().summary());
        assertEquals(json, Files.readString(body));
        assertEquals(0, DECOMPRESSIONS.get());
    }

    @Test
    void unknownTypeStateComesFromTheLocalRegistry() throws IOException {
        SnapshotFiles files = this.files();
        DataKey key = DataKey.of("external", "unknown");
        Path body = this.writeCounted(files, new Snapshot(SnapshotFixtures.meta(), Map.of(key, NBT.createInt(7))), "malformed");
        DataRegistry registry = new DataRegistry();
        var details = new SnapshotDetails(null, files, registry, Runnable::run);
        String path = "malformed/" + body.getFileName();
        var kept = assertInstanceOf(SnapshotDetailResult.Ready.class, details.loadExceptionBody(path).join().result());
        assertFalse(assertInstanceOf(SnapshotDetailResult.Preview.Unsupported.class, kept.previews().get(key)).discardUnknown());
        registry.registerUnknownDrop(key);
        var dropped = assertInstanceOf(SnapshotDetailResult.Ready.class, details.loadExceptionBody(path).join().result());
        assertTrue(assertInstanceOf(SnapshotDetailResult.Preview.Unsupported.class, dropped.previews().get(key)).discardUnknown());
        assertEquals(0, DECOMPRESSIONS.get());
    }

    private Path writeCounted(SnapshotFiles files, Snapshot snapshot, String category) throws IOException {
        Path body = files.write(snapshot, "Steve", category);
        byte[] bytes = Files.readAllBytes(body);
        Snapshot lazy = assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(bytes)).snapshot();
        for (DataKey key : lazy.keys()) {
            bytes[(int) lazy.content().raw(key).offset()] = COUNTED_DEFLATE;
        }
        Files.write(body, bytes);
        return body;
    }

    private SnapshotFiles files() {
        return new SnapshotFiles(this.directory, this.codec, this.logger);
    }
}

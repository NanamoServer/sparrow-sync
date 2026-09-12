package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.test.SnapshotFileTestLogger;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.JsonSnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.EnchantmentSeedDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.ExperienceDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.LocationDataType;
import net.momirealms.sparrow.sync.snapshot.exception.ExceptionHeader;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDetailResult.Preview;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDetailResult;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.test.PluginConfigExtension;
import net.momirealms.sparrow.sync.util.ItemCodec;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(PluginConfigExtension.class)
class SnapshotDetailsTest {
    @TempDir Path directory;
    private DataRegistry registry;
    private final BinarySnapshotCodec binary = new BinarySnapshotCodec(CompressorRegistry.NONE);
    private final List<UUID> reads = new ArrayList<>();
    private Function<UUID, CompletableFuture<Optional<Snapshot>>> reader = id -> CompletableFuture.completedFuture(Optional.empty());

    @BeforeEach
    void setUpRegistry() {
        this.registry = new DataRegistry();
    }

    @BeforeAll
    static void initialize() {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), List.of("paper"));
    }

    @Test
    void loadsOnlyTheSelectedIdAndPreparesOffTheCallingThread() {
        this.registry.register(new ExperienceDataType());
        var experience = new ExperienceDataType.Experience(100, 7, 0.5f);
        Snapshot snapshot = this.snapshot(Map.of(ExperienceDataType.EXPERIENCE, new ExperienceDataType().encode(experience)));
        this.reader = id -> CompletableFuture.completedFuture(Optional.of(snapshot));
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        SnapshotDetails details = this.details(jobs::addLast);
        assertTrue(this.reads.isEmpty());
        var pending = details.load(snapshot.meta().id());
        assertFalse(pending.isDone());
        assertEquals(List.of(snapshot.meta().id()), this.reads);
        jobs.removeFirst().run();
        var ready = assertInstanceOf(SnapshotDetailResult.Ready.class, pending.join());
        assertSame(snapshot, ready.snapshot());
        assertEquals(experience, assertInstanceOf(Preview.Ready.class, ready.previews().get(ExperienceDataType.EXPERIENCE)).value());
        assertThrows(UnsupportedOperationException.class, () -> ready.previews().clear());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -123456789, Integer.MAX_VALUE})
    void enchantmentSeedPreviewPreservesTheStoredInteger(int seed) {
        var type = new EnchantmentSeedDataType();
        this.registry.register(type);
        Snapshot snapshot = this.snapshot(Map.of(EnchantmentSeedDataType.ENCHANTMENT_SEED, type.encode(seed)));
        this.reader = id -> CompletableFuture.completedFuture(Optional.of(snapshot));
        var ready = assertInstanceOf(SnapshotDetailResult.Ready.class, this.details(Runnable::run).load(snapshot.meta().id()).join());
        assertEquals(seed, assertInstanceOf(Preview.Ready.class, ready.previews().get(EnchantmentSeedDataType.ENCHANTMENT_SEED)).value());
    }

    @Test
    void locationPreviewRetainsWorldCoordinatesAndRotation() {
        var type = new LocationDataType();
        this.registry.register(type);
        var location = new LocationDataType.PlayerLocation("remote_world", -120.25, 64.5, 305.75, 135.5f, -20.25f);
        Snapshot snapshot = this.snapshot(Map.of(LocationDataType.LOCATION, type.encode(location)));
        this.reader = id -> CompletableFuture.completedFuture(Optional.of(snapshot));
        var ready = assertInstanceOf(SnapshotDetailResult.Ready.class, this.details(Runnable::run).load(snapshot.meta().id()).join());
        assertEquals(location, assertInstanceOf(Preview.Ready.class, ready.previews().get(LocationDataType.LOCATION)).value());
    }

    @Test
    void corruptAndUnsupportedTypesDoNotPreventOtherPreviewsOrDiscardRawData() {
        this.registry.register(new HealthDataType());
        this.registry.register(new ExperienceDataType());
        DataKey custom = DataKey.of("test", "custom");
        this.registry.register(new UnsupportedType(custom));
        DataKey unknown = DataKey.of("unknown", "retained");
        this.registry.registerUnknownDrop(custom);
        this.registry.registerUnknownDrop(unknown);
        Tag raw = NBT.createString("raw");
        Snapshot snapshot = this.snapshot(Map.of(
                HealthDataType.HEALTH, raw,
                ExperienceDataType.EXPERIENCE, new ExperienceDataType().encode(new ExperienceDataType.Experience(3, 1, 0.1f)),
                custom, raw, unknown, raw));
        this.reader = id -> CompletableFuture.completedFuture(Optional.of(snapshot));
        var ready = assertInstanceOf(SnapshotDetailResult.Ready.class, this.details(Runnable::run).load(snapshot.meta().id()).join());
        assertInstanceOf(Preview.Failed.class, ready.previews().get(HealthDataType.HEALTH));
        assertInstanceOf(Preview.Ready.class, ready.previews().get(ExperienceDataType.EXPERIENCE));
        assertEquals(new Preview.Unsupported(true, -1, false), ready.previews().get(custom));
        assertEquals(new Preview.Unsupported(false, -1, true), ready.previews().get(unknown));
        assertSame(raw, ready.snapshot().data(unknown));
        assertEquals(4, ready.snapshot().allData().size());
    }

    @Test
    void inventoryPreviewsRetainRecordedContainerSizesAndHeldSlot() {
        this.registry.register(NmsPlayerFixture.allocate(InventoryDataType.class));
        this.registry.register(NmsPlayerFixture.allocate(EnderChestDataType.class));
        CompoundTag inventory = NBT.createCompound();
        inventory.putInt("size", 43);
        inventory.putInt("heldSlot", 8);
        inventory.put("items", NBT.createList());
        CompoundTag ender = NBT.createCompound();
        ender.putInt("size", 54);
        ender.put("items", NBT.createList());
        Snapshot snapshot = this.snapshot(Map.of(InventoryDataType.INVENTORY, inventory, EnderChestDataType.ENDER_CHEST, ender));
        this.reader = id -> CompletableFuture.completedFuture(Optional.of(snapshot));
        var result = assertInstanceOf(SnapshotDetailResult.Ready.class, this.details(Runnable::run).load(snapshot.meta().id()).join());
        var decodedInventory = assertInstanceOf(InventoryDataType.Inventory.class, assertInstanceOf(Preview.Ready.class, result.previews().get(InventoryDataType.INVENTORY)).value());
        var decodedEnder = assertInstanceOf(ItemCodec.LoadedItems.class, assertInstanceOf(Preview.Ready.class, result.previews().get(EnderChestDataType.ENDER_CHEST)).value());
        assertEquals(43, decodedInventory.contents().length);
        assertEquals(8, decodedInventory.heldSlot());
        assertEquals(54, decodedEnder.items().length);
    }

    @ParameterizedTest
    @EnumSource(FormatException.InvalidReason.class)
    void databaseDecodeReasonsRemainStructured(FormatException.InvalidReason reason) {
        this.reader = id -> CompletableFuture.failedFuture(new FormatException(reason, "invalid stored data"));
        var result = assertInstanceOf(SnapshotDetailResult.Invalid.class, this.details(Runnable::run).load(UUID.randomUUID()).join());
        assertEquals(reason, result.reason());
    }

    @Test
    void missingSnapshotAndStorageOutageAreDifferentResults() {
        SnapshotDetails details = this.details(Runnable::run);
        assertSame(SnapshotDetailResult.NOT_FOUND, details.load(UUID.randomUUID()).join());
        IOException failure = new IOException("database offline");
        this.reader = id -> CompletableFuture.failedFuture(failure);
        assertSame(failure, assertInstanceOf(SnapshotDetailResult.Failed.class, details.load(UUID.randomUUID()).join()).failure());
    }

    @ParameterizedTest
    @EnumSource(SnapshotFiles.Format.class)
    void archiveDetailsReadOnlySelectedBodyWithoutRequiringAHeader(SnapshotFiles.Format format) throws Exception {
        Snapshot snapshot = this.snapshot(Map.of(DataKey.of("unknown", "data"), NBT.createString("retained")));
        Path body = this.directory.resolve("snapshot/exception/malformed/selected" + (format == SnapshotFiles.Format.JSON ? ".json" : ".snapshot"));
        Files.createDirectories(body.getParent());
        if (format == SnapshotFiles.Format.JSON) {
            Files.writeString(body, new JsonSnapshotCodec().encode(snapshot));
        } else {
            Files.write(body, this.binary.encode(snapshot));
        }
        Files.writeString(body.resolveSibling("unselected.snapshot"), "corrupt");
        var result = this.details(Runnable::run).loadExceptionBody("malformed/" + body.getFileName()).join();
        assertEquals(SnapshotFiles.HeadStatus.AVAILABLE, result.entry().headStatus());
        assertEquals(snapshot, assertInstanceOf(SnapshotDetailResult.Ready.class, result.result()).snapshot());
        assertTrue(this.reads.isEmpty());
    }

    @Test
    void invalidAndMissingArchiveBodiesKeepTheirHeader() throws Exception {
        Snapshot snapshot = this.snapshot(Map.of());
        Path body = this.directory.resolve("snapshot/exception/corrupted/selected.snapshot");
        Files.createDirectories(body.getParent());
        Files.writeString(body, "broken");
        ExceptionHeader header = new ExceptionHeader(snapshot.meta(), "Steve");
        header.write(body);
        SnapshotDetails details = this.details(Runnable::run);
        var broken = details.loadExceptionBody("corrupted/selected.snapshot").join();
        assertEquals(header, broken.entry().header());
        assertInstanceOf(SnapshotDetailResult.Invalid.class, broken.result());
        byte[] future = this.binary.encode(snapshot);
        future[0] = 100;
        Files.write(body, future);
        var unsupported = details.loadExceptionBody("corrupted/selected.snapshot").join();
        assertEquals(FormatException.InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(SnapshotDetailResult.Invalid.class, unsupported.result()).reason());
        Files.delete(body);
        var missing = details.loadExceptionBody("corrupted/selected.snapshot").join();
        assertEquals(header, missing.entry().header());
        assertFalse(missing.entry().bodyPresent());
        assertSame(SnapshotDetailResult.NOT_FOUND, missing.result());
    }

    @Test
    void oversizedBinaryArchiveStillPreparesGuiPreviews() throws IOException {
        this.registry.register(new ExperienceDataType());
        var experience = new ExperienceDataType.Experience(100, 7, 0.5f);
        DataKey unknown = DataKey.of("test", "large");
        Snapshot snapshot = this.snapshot(Map.of(unknown, NBT.createByteArray(new byte[17 * 1024 * 1024]),
                ExperienceDataType.EXPERIENCE, new ExperienceDataType().encode(experience)));
        SnapshotFiles files = new SnapshotFiles(this.directory, this.binary, new SnapshotFileTestLogger());
        Path body = files.write(snapshot, "Steve", "oversized");
        assertTrue(Files.size(body) > 16 * 1024 * 1024);
        var archive = this.details(Runnable::run).loadExceptionBody("oversized/" + body.getFileName()).join();
        var ready = assertInstanceOf(SnapshotDetailResult.Ready.class, archive.result());
        assertEquals(snapshot, ready.snapshot());
        ready = this.details(Runnable::run).preview(ready, ExperienceDataType.EXPERIENCE).join();
        assertEquals(experience, assertInstanceOf(Preview.Ready.class, ready.previews().get(ExperienceDataType.EXPERIENCE)).value());
        assertEquals(new Preview.Unsupported(false, ready.snapshot().content().rawLength(unknown), false), ready.previews().get(unknown));
    }

    /**
     * 破坏不支持预览及未注册类型的数据块, 验证详情页仍能展示其他已支持的类型.
     * 被跳过的类型只从块头取得未压缩 NBT 的字节数, 损坏的 payload 不会被解码.
     *
     * @throws Exception 当测试快照编解码或读取已解析块数失败时
     */
    @Test
    void lazyPreviewSkipsUnsupportedPayloadsAndReportsHeaderSizes() throws Exception {
        this.registry.register(new EnchantmentSeedDataType());
        DataKey unsupported = DataKey.of("test", "unsupported");
        DataKey unknown = DataKey.of("external", "unknown");
        this.registry.register(new UnsupportedType(unsupported));
        Snapshot fixture = this.snapshot(Map.of(EnchantmentSeedDataType.ENCHANTMENT_SEED, NBT.createInt(12),
                unsupported, NBT.createString("unsupported"), unknown, NBT.createString("unknown")));
        byte[] bytes = this.binary.encode(fixture);
        Snapshot located = assertInstanceOf(DecodedSnapshot.Valid.class, this.binary.decode(bytes)).snapshot();
        bytes[(int) located.content().raw(unsupported).offset() + 13] ^= 1;
        bytes[(int) located.content().raw(unknown).offset() + 13] ^= 1;
        Snapshot source = assertInstanceOf(DecodedSnapshot.Valid.class, this.binary.decode(bytes)).snapshot();
        this.reader = id -> CompletableFuture.completedFuture(Optional.of(source));
        var ready = assertInstanceOf(SnapshotDetailResult.Ready.class, this.details(Runnable::run).load(source.meta().id()).join());
        assertEquals(12, assertInstanceOf(Preview.Ready.class, ready.previews().get(EnchantmentSeedDataType.ENCHANTMENT_SEED)).value());
        assertEquals(source.content().rawLength(unsupported), assertInstanceOf(Preview.Unsupported.class, ready.previews().get(unsupported)).rawLength());
        assertTrue(assertInstanceOf(Preview.Unsupported.class, ready.previews().get(unknown)).rawLength() > 0);
        assertEquals(1, SnapshotFixtures.decodedBlockCount(source));
    }
    /**
     * 未适配或未注册类型的块头损坏只产生本项 Failed, 其他类型仍可展示.
     *
     * @param registered 是否注册损坏类型, 两种状态都没有适配内容预览
     * @param damage 损坏位置, 覆盖 payload 长度, 原始长度与块头截断
     * @throws Exception 当测试快照编解码或读取缓存计数失败时
     */
    @ParameterizedTest
    @CsvSource({"false,payload", "true,payload", "false,raw", "true,raw", "false,truncated", "true,truncated"})
    void badHeaderOnlyFailsItsOwnPreview(boolean registered, String damage) throws Exception {
        DataKey intact = DataKey.of("external", "intact");
        DataKey broken = DataKey.of("external", "broken");
        this.registry.register(new EnchantmentSeedDataType());
        this.registry.registerUnknownDrop(broken);
        if (registered) {
            this.registry.register(new UnsupportedType(broken));
        }
        Map<DataKey, Tag> values = new LinkedHashMap<>();
        values.put(EnchantmentSeedDataType.ENCHANTMENT_SEED, NBT.createInt(12));
        values.put(intact, NBT.createString("intact"));
        values.put(broken, NBT.createString("broken"));
        byte[] bytes = this.binary.encode(this.snapshot(values));
        Snapshot located = assertInstanceOf(DecodedSnapshot.Valid.class, this.binary.decode(bytes)).snapshot();
        int offset = (int) located.content().raw(broken).offset();
        if (damage.equals("truncated")) {
            bytes = Arrays.copyOf(bytes, offset + 12);
        } else {
            ByteBuffer.wrap(bytes).putInt(offset + (damage.equals("payload") ? 1 : 5), -1);
        }
        Snapshot source = assertInstanceOf(DecodedSnapshot.Valid.class, this.binary.decode(bytes)).snapshot();
        this.reader = id -> CompletableFuture.completedFuture(Optional.of(source));
        var ready = assertInstanceOf(SnapshotDetailResult.Ready.class, this.details(Runnable::run).load(source.meta().id()).join());
        assertEquals(3, ready.previews().size());
        assertEquals(12, assertInstanceOf(Preview.Ready.class, ready.previews().get(EnchantmentSeedDataType.ENCHANTMENT_SEED)).value());
        assertInstanceOf(Preview.Unsupported.class, ready.previews().get(intact));
        assertInstanceOf(Preview.Failed.class, ready.previews().get(broken));
        assertTrue(ready.snapshot().keys().contains(broken));
        assertEquals(1, SnapshotFixtures.decodedBlockCount(source));
    }

    private SnapshotDetails details(Executor executor) {
        this.registry.freeze();
        StorageProvider storage = (StorageProvider) Proxy.newProxyInstance(StorageProvider.class.getClassLoader(), new Class<?>[]{StorageProvider.class}, (instance, method, args) -> {
            assertEquals("snapshot", method.getName());
            UUID id = (UUID) args[0];
            this.reads.add(id);
            return this.reader.apply(id);
        });
        SnapshotFiles files = new SnapshotFiles(this.directory, this.binary, new SnapshotFileTestLogger());
        return new SnapshotDetails(storage, files, this.registry, executor);
    }

    private Snapshot snapshot(Map<DataKey, Tag> data) {
        return new Snapshot(new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 1234, SaveCause.COMMAND, true, "lobby", 0), data);
    }

    private record UnsupportedType(DataKey key) implements PlayerDataType<Tag> {
        @Override
        public Tag capture(Player player, CaptureMode mode) {
            throw new AssertionError("preview must not capture a player");
        }

        @Override
        public Tag encode(Tag value) {
            throw new AssertionError("preview must not rewrite stored data");
        }

        @Override
        public Tag decode(Tag data, int version) {
            throw new AssertionError("unsupported preview must not decode data");
        }

        @Override
        public void apply(Player player, Tag value) {
            throw new AssertionError("preview must not apply to a player");
        }
    }
}

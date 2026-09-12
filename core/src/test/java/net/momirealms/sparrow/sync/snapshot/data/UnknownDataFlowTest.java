package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.FileLogWriter;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.type.LocationDataType;
import net.momirealms.sparrow.sync.snapshot.model.EagerSnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 验证本服丢弃名单经过正式加载和再次保存后的行为, 包括真实 location 类型的关闭与重开. */
class UnknownDataFlowTest {
    private static final DataKey BOOK = DataKey.of("external", "book"); // 未安装插件时仍需保留的类型
    private final BinarySnapshotCodec codec = new BinarySnapshotCodec(CompressorRegistry.DEFLATE, 0); // 强制压缩, 便于比较原块
    private final List<String> consoleLogs = new ArrayList<>(); // 记录控制台输出, 用于验证丢弃日志只写文件
    private final SyncLogger logger = new SyncLogger((PluginLogger) Proxy.newProxyInstance(PluginLogger.class.getClassLoader(), new Class<?>[]{PluginLogger.class}, (proxy, method, args) -> {
        this.consoleLogs.add(method.getName() + ": " + args[0]);
        return null;
    }));
    private Field configField;
    private Object previousConfig;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        // 注册表构造时读取启动配置, 每个用例从空名单开始, 再注册所需的丢弃类型.
        this.configField = PluginConfig.class.getDeclaredField("config");
        this.configField.setAccessible(true);
        this.previousConfig = this.configField.get(null);
        this.configField.set(null, new PluginConfig.ConfigDefinition());
    }

    @AfterEach
    void tearDown() throws IllegalAccessException {
        this.configField.set(null, this.previousConfig);
    }

    /**
     * location 关闭期间按接收服名单决定保留或丢弃, 重开同步只能恢复实际保留下来的旧坐标.
     *
     * @param discard 是否在接收服配置中列入 location
     * @throws Exception 当测试快照编解码或流水线装配失败时
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disablingAndReenablingLocationUsesTheReceiverDropList(boolean discard) throws Exception {
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> "world");
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class}, (proxy, method, args) -> world);
        AtomicReference<Location> position = new AtomicReference<>(new Location(world, 10, 64, 10));
        AtomicInteger teleports = new AtomicInteger();
        UUID id = UUID.randomUUID();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "PolicyPlayer";
            case "getLocation" -> position.get().clone();
            case "getServer" -> server;
            case "teleport", "teleportAsync" -> {
                teleports.incrementAndGet();
                position.set((Location) args[0]);
                yield method.getName().equals("teleport") ? true : CompletableFuture.completedFuture(true);
            }
            default -> throw new AssertionError(method.getName());
        });
        // 第一天由真实 location 类型采集坐标, 图鉴与坐标一起保存.
        PlayerDataPipeline dayOne = this.pipeline(new LocationDataType(), new TextType(BOOK));
        Snapshot original = this.capture(dayOne, player, EagerSnapshotData.EMPTY);
        byte[] book = bytes(original, BOOK);
        byte[] location = bytes(original, LocationDataType.LOCATION);
        // 第二天关闭 location 同步, 配置名单决定它是否进入保留集合, 两条分支都不解码此块.
        position.set(new Location(world, 200, 70, 300));
        PluginConfig.ConfigDefinition config = (PluginConfig.ConfigDefinition) this.configField.get(null);
        Field synchronization = PluginConfig.ConfigDefinition.class.getDeclaredField("synchronization");
        synchronization.setAccessible(true);
        NmsPlayerFixture.set(PluginConfig.SynchronizationOptions.class, synchronization.get(config), "discardUnknownData", discard ? Set.of(LocationDataType.LOCATION) : Set.of());
        PlayerDataPipeline dayTwo = this.pipeline();
        SnapshotApplyContext context = ready(dayTwo, original);
        assertNull(context.takePending(LocationDataType.LOCATION));
        assertEquals(discard ? Set.of(BOOK) : Set.of(BOOK, LocationDataType.LOCATION), context.passthrough().keys());
        assertEquals(0, SnapshotFixtures.decodedBlockCount(original));
        dayTwo.apply(player, context);
        Snapshot saved = this.capture(dayTwo, player, context.passthrough());
        assertEquals(!discard, saved.keys().contains(LocationDataType.LOCATION));
        if (!discard) {
            assertArrayEquals(location, bytes(saved, LocationDataType.LOCATION));
        }
        assertArrayEquals(book, bytes(saved, BOOK));
        assertEquals(0, SnapshotFixtures.decodedBlockCount(saved));
        // 第三天重新注册, 空名单下保留的旧坐标会正常应用, 曾丢弃的坐标不会出现.
        PlayerDataPipeline dayThree = this.pipeline(new LocationDataType());
        SnapshotApplyContext next = ready(dayThree, saved);
        assertEquals(!discard, next.pendingValues().containsKey(LocationDataType.LOCATION));
        assertInstanceOf(PlayerDataPipeline.ApplyResult.Success.class, dayThree.apply(player, next));
        assertEquals(discard ? 200 : 10, position.get().getX());
        assertEquals(discard ? 300 : 10, position.get().getZ());
        assertEquals(discard ? 0 : 1, teleports.get());
    }

    /**
     * 已注册类型无论是否列入丢弃名单都正常应用, 新采集值覆盖旧值.
     *
     * @param listed 是否将该类型加入本服丢弃名单
     * @throws Exception 当测试快照编解码失败时
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void registeredTypeAppliesAndRefreshesCapturedValue(boolean listed) throws Exception {
        TextType type = new TextType(BOOK);
        PlayerDataPipeline pipeline = this.pipeline(listed ? Set.of(BOOK) : Set.of(), type);
        Snapshot source = this.snapshot(UUID.randomUUID(), Map.of(BOOK, NBT.createString("old")));
        SnapshotApplyContext context = ready(pipeline, source);
        assertEquals("old", context.pendingValues().get(BOOK));
        assertTrue(context.passthrough().keys().isEmpty());
        pipeline.apply(this.player(source.meta().player()), context);
        assertEquals("old", type.applied);
        Snapshot saved = this.capture(pipeline, this.player(source.meta().player()), context.passthrough());
        assertEquals("fresh", saved.data(BOOK).getAsString());
    }

    /**
     * 列入丢弃名单的损坏块仍能从保留集排除, 预览可以看到原块且不会执行丢弃.
     *
     * @throws Exception 当测试快照编解码失败时
     */
    @Test
    void corruptedDropBlockIsDiscardedWithoutReadingPayload() throws Exception {
        Snapshot source = this.snapshot(UUID.randomUUID(), Map.of(BOOK, NBT.createString("broken")));
        var raw = source.content().raw(BOOK);
        raw.bytes()[(int) raw.offset() + 13] ^= 1;
        PlayerDataPipeline pipeline = this.pipeline(Set.of(BOOK));
        SnapshotApplyContext context = ready(pipeline, source);
        assertSame(EagerSnapshotData.EMPTY, context.passthrough());
        assertEquals(0, SnapshotFixtures.decodedBlockCount(source));
        DataRegistry registry = new DataRegistry();
        registry.freeze();
        new SnapshotDecoder(registry).decodeSelected(source, type -> true);
        assertTrue(source.keys().contains(BOOK));
        assertEquals(0, SnapshotFixtures.decodedBlockCount(source));
    }

    /**
     * 每次丢弃都写入日志文件, 同一玩家重复加载同一类型也保留记录, 控制台不输出.
     *
     * @param directory 测试日志目录
     * @throws Exception 当测试快照编解码或日志文件读取失败时
     */
    @Test
    void everyDropIsLoggedOnlyToFile(@TempDir Path directory) throws Exception {
        PlayerDataPipeline pipeline = this.pipeline(Set.of(BOOK, DataKey.of("external", "other")));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Snapshot source = this.snapshot(first, Map.of(BOOK, NBT.createInt(1)));
        try (FileLogWriter writer = new FileLogWriter(directory, "HH:mm:ss", "'drops'", this.logger.console)) {
            this.logger.attachFile(writer);
            ready(pipeline, source);
            ready(pipeline, source);
            ready(pipeline, this.snapshot(second, source.content().all()));
            ready(pipeline, this.snapshot(first, Map.of(DataKey.of("external", "other"), NBT.createInt(1))));
        }
        // close 会等待队列中的日志写完, 断言实际文件内容即可覆盖异步写入路径.
        List<String> lines = Files.readAllLines(directory.resolve("drops.log"));
        assertEquals(4, lines.size());
        assertEquals(3, lines.stream().filter(line -> line.contains(first.toString())).count());
        assertEquals(1, lines.stream().filter(line -> line.contains(second.toString())).count());
        // 测试没有装配翻译管理器, 文件中的消息正文为原始翻译键.
        assertTrue(lines.stream().allMatch(line -> line.contains(LogConstants.DATA_UNKNOWN_DROPPED)));
        assertTrue(this.consoleLogs.isEmpty(), this.consoleLogs.toString());
    }

    /**
     * 同一份快照在不同接收服按各自名单处理, 空名单保留未注册类型.
     *
     * @throws Exception 当测试快照编解码失败时
     */
    @Test
    void receiverRegistryDeterminesUnknownRetention() throws Exception {
        Snapshot source = this.snapshot(UUID.randomUUID(), Map.of(BOOK, NBT.createString("opaque")));
        SnapshotApplyContext kept = ready(this.pipeline(), source);
        SnapshotApplyContext dropped = ready(this.pipeline(Set.of(BOOK)), source);
        assertEquals(Set.of(BOOK), kept.passthrough().keys());
        assertTrue(dropped.passthrough().keys().isEmpty());
        assertEquals(0, SnapshotFixtures.decodedBlockCount(source));
        assertArrayEquals(bytes(source, BOOK), bytes(new Snapshot(source.meta(), kept.passthrough()), BOOK));
    }

    /**
     * 装配真实数据流水线, 编码器使用独立压缩配置.
     *
     * @param types 本服注册的类型
     * @return 可以执行采集和应用的测试流水线
     */
    private PlayerDataPipeline pipeline(PlayerDataType<?>... types) {
        return this.pipeline(Set.of(), types);
    }

    /**
     * 按接收服的丢弃名单装配流水线, 注册完成后冻结.
     *
     * @param dropped 本服未注册时丢弃的类型
     * @param types 本服已注册的类型
     * @return 可执行正式加载的流水线
     */
    private PlayerDataPipeline pipeline(Set<DataKey> dropped, PlayerDataType<?>... types) {
        DataRegistry registry = new DataRegistry();
        for (DataKey key : dropped) {
            registry.registerUnknownDrop(key);
        }
        for (PlayerDataType<?> type : types) registry.register(type);
        registry.freeze();
        PlayerDataPipeline pipeline = new PlayerDataPipeline(null);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "dataRegistry", registry);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "decoder", new SnapshotDecoder(registry));
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "dataCodec", new SnapshotDataCodec(CompressorRegistry.NONE));
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "logger", this.logger);
        return pipeline;
    }

    /**
     * 运行真实采集编码, 将本次 Tag 覆盖到保留数据后写出并读回二进制.
     *
     * @param pipeline 当前服务器的类型流水线
     * @param player 提供本服现值的玩家
     * @param retained 上次加载保留的数据体
     * @return 尚未解块的新快照
     * @throws IOException 当二进制编码失败时
     */
    private Snapshot capture(PlayerDataPipeline pipeline, Player player, SnapshotData retained) throws IOException {
        var captured = assertInstanceOf(PlayerDataPipeline.CaptureResult.Ready.class, pipeline.capture(player, CaptureMode.SYNC));
        var encoded = assertInstanceOf(PlayerDataPipeline.EncodeResult.Ready.class, pipeline.encode(captured));
        Snapshot snapshot = new Snapshot(SnapshotMeta.builder().player(player.getUniqueId()).timestamp(1).cause(SaveCause.COMMAND).build(), retained.with(encoded.data()));
        return assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(snapshot))).snapshot();
    }

    /**
     * 将类型 Tag 写入二进制帧, 供正式加载测试使用.
     *
     * @param player 数据所属玩家
     * @param data 按输入顺序写入的类型 Tag
     * @return 尚未解块的快照
     * @throws IOException 当二进制编码失败时
     */
    private Snapshot snapshot(UUID player, Map<DataKey, Tag> data) throws IOException {
        Snapshot snapshot = new Snapshot(SnapshotMeta.builder().player(player).timestamp(1).cause(SaveCause.COMMAND).build(), new EagerSnapshotData(data));
        return assertInstanceOf(DecodedSnapshot.Valid.class, this.codec.decode(this.codec.encode(snapshot))).snapshot();
    }

    /**
     * 读取正式应用上下文并断言加载成功.
     *
     * @param pipeline 当前服务器的流水线
     * @param snapshot 输入快照
     * @return 成功加载的上下文
     */
    private static SnapshotApplyContext ready(PlayerDataPipeline pipeline, Snapshot snapshot) {
        return assertInstanceOf(PlayerDataPipeline.DecodeResult.Ready.class, pipeline.decode(snapshot)).context();
    }

    /**
     * 创建仅提供采集身份的玩家.
     *
     * @param id 玩家身份
     * @return 测试玩家
     */
    private Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "PolicyPlayer";
            default -> throw new AssertionError(method.getName());
        });
    }

    /**
     * 提取完整原始块供跨服字节比较.
     *
     * @param snapshot 原始快照
     * @param key 要比较的类型
     * @return 块头及压缩载荷
     */
    private static byte[] bytes(Snapshot snapshot, DataKey key) {
        var block = snapshot.content().raw(key);
        return Arrays.copyOfRange(block.bytes(), (int) block.offset(), (int) block.end());
    }

    /** 提供文本值的外部类型, 用于观察应用与重新采集. */
    private static final class TextType implements PlayerDataType<String> {
        private final DataKey key; // 外部类型标识
        private String applied; // 最后一次应用值

        /**
         * 创建外部类型声明.
         *
         * @param key 类型标识
         */
        private TextType(DataKey key) {
            this.key = key;
        }

        @Override
        @NotNull
        public DataKey key() { return this.key; }

        /** {@inheritDoc} */
        @Override
        @NotNull
        public String capture(@NotNull Player player, @NotNull CaptureMode mode) { return "fresh"; }

        /** {@inheritDoc} */
        @Override
        @NotNull
        public Tag encode(@NotNull String value) { return NBT.createString(value); }

        /** {@inheritDoc} */
        @Override
        @NotNull
        public String decode(@NotNull Tag tag, int mcDataVersion) { return tag.getAsString(); }

        /** {@inheritDoc} */
        @Override
        public void apply(@NotNull Player player, @NotNull String value) { this.applied = value; }
    }
}

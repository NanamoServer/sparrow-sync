package net.momirealms.sparrow.sync.plugin;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DocumentSnapshotCodec;
import net.momirealms.sparrow.sync.plugin.command.BukkitCommandManager;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.compatibility.CompatibilityManager;
import net.momirealms.sparrow.sync.plugin.configuration.ConfigurationManager;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.data.type.*;
import net.momirealms.sparrow.sync.util.ItemCodec;
import net.momirealms.sparrow.sync.session.gate.ConfigurationPacketGate;
import net.momirealms.sparrow.sync.session.gate.LoginGate;
import net.momirealms.sparrow.sync.session.gate.PaperEventGate;
import net.momirealms.sparrow.sync.executor.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.locale.TranslationManagerImpl;
import net.momirealms.sparrow.sync.plugin.classpath.ClassPathAppender;
import net.momirealms.sparrow.sync.plugin.dependency.Dependencies;
import net.momirealms.sparrow.sync.plugin.dependency.Dependency;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyManager;
import net.momirealms.sparrow.sync.plugin.logger.FileLogWriter;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.plugin.logger.filter.DisconnectLogFilter;
import net.momirealms.sparrow.sync.plugin.scheduler.BukkitSchedulerAdapter;
import net.momirealms.sparrow.sync.plugin.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.cluster.SessionLock;
import net.momirealms.sparrow.sync.cluster.HandoffManager;
import net.momirealms.sparrow.sync.redis.heartbeats.ServerHeartBeats;
import net.momirealms.sparrow.sync.redis.MessageBrokerManager;
import net.momirealms.sparrow.sync.redis.RedisConnector;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SnapshotService;
import net.momirealms.sparrow.sync.session.SnapshotStash;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.StorageType;
import net.momirealms.sparrow.sync.storage.mongo.MongoStorageProvider;
import net.momirealms.sparrow.sync.util.CharacterUtils;
import net.momirealms.sparrow.sync.util.ExceptionCollector;
import net.momirealms.sparrow.sync.util.ReflectionUtils;
import net.momirealms.sparrow.sync.util.VersionHelper;
import net.momirealms.sparrow.ui.SparrowUI;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SparrowSync implements Plugin {
    private static SparrowSync instance;

    private final SyncLogger logger;
    private final Path dataFolderPath;
    private final ClassPathAppender sharedClassPathAppender;
    private final ClassPathAppender privateClassPathAppender;
    private final SchedulerAdapter<?> scheduler;
    private final DependencyManager dependencyManager;
    private final CompatibilityManager compatibilityManager;
    private final ConfigurationManager configurationManager;
    private TranslationManager translationManager;
    private CommandManager commandManager;

    private JavaPlugin javaPlugin;
    private boolean isInitializing;
    private boolean successfullyLoaded = false;
    private boolean successfullyEnabled = false;
    private final AtomicBoolean reloading = new AtomicBoolean();

    private final PlayerSerialExecutor playerExecutor;
    private final DataRegistry dataRegistry;
    private final BinarySnapshotCodec binaryCodec;
    private final DocumentSnapshotCodec documentCodec;
    private final MongoStorageProvider storageProvider;
    private final SnapshotStash snapshotStash;
    private final PlayerDataPipeline playerDataPipeline;
    private final SnapshotService snapshotService;
    private final RedisConnector redisConnector;
    private final SessionLock sessionLock;
    private final MessageBrokerManager messageBrokerManager;
    private final ServerHeartBeats serverHeartBeats;
    private final HandoffManager handoffManager;
    private final SessionManager sessionManager;
    private final LoginGate loginGate;

    SparrowSync(PluginLogger logger, Path dataFolderPath, ClassPathAppender sharedClassPathAppender, ClassPathAppender privateClassPathAppender) {
        instance = this;
        this.logger = new SyncLogger(logger);
        this.dataFolderPath = dataFolderPath;
        this.sharedClassPathAppender = sharedClassPathAppender;
        this.privateClassPathAppender = privateClassPathAppender;

        this.scheduler = new BukkitSchedulerAdapter(this);
        this.dependencyManager = new DependencyManager(this);
        this.applyDependencies();
        this.setupProxy();
        this.configurationManager = new ConfigurationManager(this);
        this.configurationManager.reload();
        this.translationManager = new TranslationManagerImpl(this);
        this.translationManager.reload();
        // resolve 对绝对路径原样返回, 因此配置里的相对目录落在数据目录下, 绝对目录按原样使用
        if (PluginConfig.logging$localFile()) {
            FileLogWriter fileWriter = new FileLogWriter(
                    this.dataFolderPath.resolve(PluginConfig.logging$directory()),
                    PluginConfig.logging$timeFormat(), PluginConfig.logging$fileDateFormat(),
                    logger
            );
            this.logger.attachFile(fileWriter);
            this.logger.file(LogCategory.LIFECYCLE, null, null, LogConstants.PLUGIN_STARTED);
        }
        this.compatibilityManager = new CompatibilityManager(this);
        this.playerExecutor = new PlayerSerialExecutor(this.logger, PluginConfig.synchronization$workerThreads());
        ((Logger) LogManager.getRootLogger()).addFilter(new DisconnectLogFilter());

        // 业务模块
        this.dataRegistry = new DataRegistry();
        this.binaryCodec = new BinarySnapshotCodec(this);
        this.documentCodec = new DocumentSnapshotCodec(this);
        this.storageProvider = new MongoStorageProvider(this);
        this.snapshotStash = new SnapshotStash(this);
        this.playerDataPipeline = new PlayerDataPipeline(this);
        this.snapshotService = new SnapshotService(this);
        this.redisConnector = new RedisConnector(this);
        this.sessionLock = new SessionLock(this);
        this.messageBrokerManager = new MessageBrokerManager(this);
        this.serverHeartBeats = new ServerHeartBeats(this);
        this.sessionManager = new SessionManager(this);
        this.handoffManager = new HandoffManager(this);
        this.loginGate = VersionHelper.isPaper() && VersionHelper.isOrAbove1_21_7() ? new PaperEventGate(this) : new ConfigurationPacketGate(this);
    }

    public static SparrowSync instance() {
        return instance;
    }

    void setJavaPlugin(JavaPlugin javaPlugin) {
        this.javaPlugin = javaPlugin;
    }

    @Override
    public void onPluginBootstrap(BootstrapContext context) {
    }

    @Override
    public void onPluginLoad() {
        // 集成插件管理器
        this.compatibilityManager.onLoad();
        // 服务器身份缺失时不放行
        if (ServerConfig.serverId().isEmpty()) {
            this.logger.error(" ");
            this.logger.error(" ");
            this.logger.error(" ");
            this.logger.error("============================================================");
            this.logger.error(TranslationManager.console(LogConstants.SERVER_ID_MISSING));
            this.logger.error("============================================================");
            this.logger.error(" ");
            this.logger.error(" ");
            this.logger.error(" ");
            Bukkit.getServer().shutdown();
            return;
        }
        // 加载基础组件
        this.setUpInternalDataTypes();
        this.playerDataPipeline.onLoad();
        this.snapshotService.onLoad();
        this.sessionManager.onLoad();
        // 链接 Redis
        try {
            this.redisConnector.onLoad();
            this.sessionLock.onLoad();
            this.messageBrokerManager.onLoad();
            this.handoffManager.onLoad();
            this.serverHeartBeats.onLoad();
        } catch (Throwable throwable) {
            this.logger.error(TranslationManager.console(LogConstants.REDIS_SETUP_FAILED), throwable);
            Bukkit.getServer().shutdown();
            return;
        }
        // 加载并验证快照压缩器
        try {
            this.binaryCodec.onLoad();
        } catch (Throwable throwable) {
            this.logger.error(TranslationManager.console(LogConstants.STORAGE_COMPRESSOR_FAILED), throwable);
            Bukkit.getServer().shutdown();
            return;
        }
        // 链接持久化存储
        try {
            if (PluginConfig.database$type() != StorageType.MONGODB) {
                this.logger.error(TranslationManager.console(LogConstants.STORAGE_MYSQL_NOT_IMPLEMENTED));
                Bukkit.getServer().shutdown();
                return;
            }
            this.documentCodec.onLoad();
            this.snapshotStash.onLoad();
            this.storageProvider.onLoad();
            this.logger.info(TranslationManager.console(LogConstants.STORAGE_READY, PluginConfig.database$mongodb().database()));
        } catch (Throwable throwable) {
            this.logger.error(TranslationManager.console(LogConstants.STORAGE_SETUP_FAILED), throwable);
            Bukkit.getServer().shutdown();
            return;
        }
        // 插回没能落库的本地快照
        this.snapshotStash.restorePending(this.storageProvider); // 需同步
        this.successfullyLoaded = true;
    }

    @Override
    public void onPluginEnable() {
        if (this.successfullyEnabled) {
            logger().error(" ");
            logger().error(" ");
            logger().error(" ");
            this.logger.error("============================================================");
            logger().error(TranslationManager.console(LogConstants.PLUGIN_RELOAD_AT_RUNTIME));
            this.logger.error("============================================================");
            logger().error(" ");
            logger().error(" ");
            logger().error(" ");
            Bukkit.getPluginManager().disablePlugin(this.javaPlugin);
            return;
        }
        this.successfullyEnabled = true;
        if (!this.successfullyLoaded) {
            logger().error(" ");
            logger().error(" ");
            logger().error(" ");
            this.logger.error("============================================================");
            logger().error(TranslationManager.console(LogConstants.PLUGIN_LOAD_FAILED));
            this.logger.error("============================================================");
            logger().error(" ");
            logger().error(" ");
            logger().error(" ");
            Bukkit.getServer().shutdown();
            return;
        }
        // 命令管理器
        this.commandManager = new BukkitCommandManager(this);
        this.commandManager.registerDefaultFeatures();
        // 安装 SparrowUI
        SparrowUI.getInstance().setUp(this.javaPlugin);
        SparrowUI.getInstance().setExceptionHandler(this.logger::warn);
        // 延迟初始化事件
        this.isInitializing = true;
        this.initASMProxies();
        // 集成插件管理器
        this.compatibilityManager.onEnable();
        this.scheduler.sync().runDelayed(this::onServerLoaded);
    }

    public void onServerLoaded() {
        // 集成插件管理器
        this.compatibilityManager.onDelayedEnable();
        // 快照与会话管理器
        this.snapshotService.onDelayedEnable();
        this.sessionManager.onDelayedEnable();
        // 注入读取器
        this.injectPlayerDataStorage();
        // 安装进入世界前的数据加载门
        this.loginGate.onDelayedEnable();
        // 预热和标记
        this.scheduler.async().execute(ItemCodec::warmUp);
        this.isInitializing = false;
    }

    @Override
    public void onPluginReload() {
    }

    @Override
    public void onPluginDisable() {
        long shutdownDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PluginConfig.synchronization$shutdownTimeoutSeconds());
        if (this.sessionManager != null)        this.sessionManager.shutdown(); // 封口外部保存并为 ACTIVE 会话投递 SHUTDOWN 保存
        if (this.snapshotService != null)       this.snapshotService.sealAndAwaitHandoffs(Math.max(0, shutdownDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        if (this.playerExecutor != null)        this.playerExecutor.shutdown(Math.max(0, shutdownDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        if (this.snapshotService != null)       this.snapshotService.stashUnsettled(); // 排空超时没保存完的快照落盘, 下次启动插回
        if (this.scheduler != null)             this.scheduler.shutdownScheduler();
        if (this.scheduler != null)             this.scheduler.shutdownExecutor();
        if (this.serverHeartBeats != null)      this.serverHeartBeats.shutdown(); // 注销集群身份, 心跳键删除或随 TTL 消失
        if (this.messageBrokerManager != null)  this.messageBrokerManager.shutdown();
        if (this.redisConnector != null)        this.redisConnector.shutdown();
        if (this.storageProvider != null)       this.storageProvider.shutdown();
        if (this.dependencyManager != null)     this.dependencyManager.shutdown();
        if (this.logger != null) {
            this.logger.file(LogCategory.LIFECYCLE, null, null, LogConstants.PLUGIN_STOPPED);
            this.logger.close();
        }
        if (!Bukkit.getServer().isStopping()) {
            logger().error(" ");
            logger().error(" ");
            logger().error(" ");
            logger().error(TranslationManager.console(LogConstants.PLUGIN_DISABLE_AT_RUNTIME));
            logger().error(" ");
            logger().error(" ");
            logger().error(" ");
            Bukkit.getServer().shutdown();
        }
    }

    /**
     * 创建依赖管理器, 下载并加载插件依赖.
     * 该方法会收集通用依赖与平台依赖, 然后统一交由依赖管理器进行下载和类路径注入.
     * 依赖由 `commonDependencies()` 和 `platformDependencies()` 的返回结果共同决定.
     */
    public void applyDependencies() {
        ArrayList<Dependency> dependenciesToLoad = new ArrayList<>(this.platformDependencies());
        this.dependencyManager.loadDependencies(dependenciesToLoad);
    }

    @Override
    public void setupProxy() {
        BukkitProxy.init(VersionHelper.MINECRAFT_VERSION.version(), getPatches());
    }

    // 注入玩家存储代理, 接管原版登录读取路径.
    public void injectPlayerDataStorage() {
        try {
            this.sessionManager.injectPlayerDataStorage(VersionHelper.MINECRAFT_VERSION.version());
        } catch (Throwable throwable) {
            this.logger.error(LogCategory.LIFECYCLE, null, null, throwable, LogConstants.PLAYER_DATA_STORAGE_INJECT_FAILED);
            Bukkit.getServer().shutdown();
        }
    }

    /** 注册内置的数据类型. */
    private void setUpInternalDataTypes() {
        PluginConfig.DataTypes enabled = PluginConfig.synchronization$dataTypes();
        if (enabled.inventory())        this.dataRegistry.register(new InventoryDataType());
        if (enabled.enderChest())       this.dataRegistry.register(new EnderChestDataType());
        if (enabled.persistentData())   this.dataRegistry.register(new PDCDataType());
        if (enabled.experience())       this.dataRegistry.register(new ExperienceDataType());
        if (enabled.health())           this.dataRegistry.register(new HealthScaleDataType());
        if (enabled.health())           this.dataRegistry.register(new HealthDataType());
        if (enabled.hunger())           this.dataRegistry.register(new HungerDataType());
        if (enabled.gameMode())         this.dataRegistry.register(new GameModeDataType());
        if (enabled.potionEffects())    this.dataRegistry.register(new PotionEffectsDataType());
        if (enabled.advancements())     this.dataRegistry.register(new AdvancementsDataType());
        if (enabled.statistics())       this.dataRegistry.register(new StatisticsDataType());
        if (enabled.attributes())       this.dataRegistry.register(new AttributesDataType());
        if (enabled.location())         this.dataRegistry.register(new LocationDataType());
        if (enabled.flightStatus())     this.dataRegistry.register(new FlightStatusDataType());
        if (enabled.enchantmentSeed())  this.dataRegistry.register(new EnchantmentSeedDataType());
    }

    /**
     * 以异步和同步两阶段执行插件重载.
     * 该方法会先在异步执行器中执行异步重载逻辑, 随后切换到同步执行器执行同步重载逻辑.
     *
     * @param asyncExecutor 异步任务执行器
     * @param syncExecutor 同步任务执行器
     * @return 一个异步完成的重载结果对象, 包含成功状态, 异步耗时, 同步耗时和问题数量
     */
    public CompletableFuture<ReloadResult> reloadPlugin(Executor asyncExecutor, Executor syncExecutor) {
        if (!this.reloading.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(ReloadResult.failure());
        }
        CompletableFuture<ReloadResult> future = new CompletableFuture<>();
        // 执行异步重载任务
        asyncExecutor.execute(() -> {
            long asyncTime = -1;
            int issues = 0;
            try {
                long startTime = System.currentTimeMillis();
                this.configurationManager.reload();
                this.translationManager.reload();
                asyncTime = System.currentTimeMillis() - startTime;
            } catch (Throwable e) {
                this.logger().warn(TranslationManager.console(LogConstants.PLUGIN_RELOAD_FAILED), e);
                this.reloading.set(false);
                future.complete(ReloadResult.failure());
            } finally {
                long finalAsyncTime = asyncTime;
                // 执行同步重载任务
                syncExecutor.execute(() -> {
                    try {
                        long syncStartTime = System.currentTimeMillis();
                        long syncTime = System.currentTimeMillis() - syncStartTime;
                        this.reloading.set(false);
                        future.complete(ReloadResult.success(finalAsyncTime, syncTime, 0));
                    } catch (Throwable e) {
                        this.logger().warn(TranslationManager.console(LogConstants.PLUGIN_RELOAD_FAILED), e);
                        this.reloading.set(false);
                        future.complete(ReloadResult.failure());
                    }
                });
            }
        });
        return future;
    }

    /**
     * 重载结果数据类.
     *
     * @param success 是否重载成功
     * @param asyncTime 异步阶段耗时, 单位为毫秒
     * @param syncTime 同步阶段耗时, 单位为毫秒
     * @param issues 重载过程中记录的问题数量
     */
    public record ReloadResult(boolean success, long asyncTime, long syncTime, int issues) {

        static ReloadResult failure() {
            return new ReloadResult(false, -1L, -1L, -1);
        }

        static ReloadResult success(long asyncTime, long syncTime, int issues) {
            return new ReloadResult(true, asyncTime, syncTime, issues);
        }
    }

    @Override
    public List<Dependency> platformDependencies() {
        return List.of(
                Dependencies.PLUGIN_BUKKIT_PROXY,
                // Common
                Dependencies.CAFFEINE,
                Dependencies.ZSTD_JNI,
                // MangoDB
                Dependencies.MONGODB_DRIVER_CORE, Dependencies.MONGODB_DRIVER_SYNC, Dependencies.MONGODB_DRIVER_REACTIVESTREAMS,
                Dependencies.MONGODB_DRIVER_BSON, Dependencies.MONGODB_DRIVER_KOTLIN_COROUTINE, Dependencies.REACTIVE_STREAMS,
                // Lettuce
                Dependencies.LETTUCE,
                Dependencies.REACTOR_CORE, Dependencies.REACTIVE_STREAMS,
                Dependencies.NETTY_RESOLVER, Dependencies.NETTY_RESOLVER_DNS, Dependencies.NETTY_CODEC_DNS,
                Dependencies.JACKSON_CORE, Dependencies.JACKSON_ANNOTATIONS, Dependencies.JACKSON_DATABIND, Dependencies.JACKSON_DATATYPE,
                // CLOUD
                Dependencies.GEANTY_REF,
                Dependencies.CLOUD_CORE, Dependencies.CLOUD_SERVICES,
                Dependencies.CLOUD_BUKKIT, Dependencies.CLOUD_PAPER, Dependencies.CLOUD_BRIGADIER, Dependencies.CLOUD_MINECRAFT_EXTRAS,
                // Adventure
                Dependencies.OPTION,
                Dependencies.EXAMINATION_API, Dependencies.EXAMINATION_STRING,
                Dependencies.ADVENTURE_KEY, Dependencies.ADVENTURE_API, Dependencies.ADVENTURE_NBT,
                Dependencies.MINIMESSAGE,
                Dependencies.TEXT_SERIALIZER_COMMONS, Dependencies.TEXT_SERIALIZER_LEGACY, Dependencies.TEXT_SERIALIZER_GSON, Dependencies.TEXT_SERIALIZER_GSON_LEGACY, Dependencies.TEXT_SERIALIZER_JSON
        );
    }

    /**
     * 在 dev 环境中预加载内嵌代理 Jar 中的所有类.
     *
     * @throws RuntimeException 当收集到任意类加载异常时抛出
     */
    private void initASMProxies() {
        if (!VersionHelper.IS_RUNNING_IN_DEV) return;
        this.logger().info("Initializing ASM proxies...");
        ClassLoader classLoader = ReflectionUtils.class.getClassLoader();
        ExceptionCollector<Throwable> collector = new ExceptionCollector<>(Throwable.class);
        try (InputStream resourceAsStream = classLoader.getResourceAsStream("sparrow-sync-proxy.jarinjar")) {
            if (resourceAsStream == null) return;
            try (ByteArrayInputStream bais = new ByteArrayInputStream(resourceAsStream.readAllBytes());
                 ZipInputStream zis = new ZipInputStream(bais)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String entryName = entry.getName();
                    if (!entryName.endsWith(".class")) continue;
                    if (entryName.startsWith("net/momirealms/sparrow/sync/proxy/minecraft/world/level/storage/PlayerDataStoragePatch")) continue;
                    String className = entryName.replace('/', '.').substring(0, entryName.length() - 6);
                    try {
                        Class.forName(className);
                    } catch (Throwable e) {
                        collector.add(e);
                    }
                }
            } catch (Throwable e) {
                collector.add(e);
            }
        } catch (Throwable e) {
            collector.add(e);
        }
        try {
            collector.throwIfPresent();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 收集当前服务端所具备的补丁标识.
     * 该列表会用于初始化代理层, 以便根据具体发行版差异加载不同兼容逻辑.
     *
     * @return 当前服务端命中的补丁名称列表, 如 `paper`, `folia` 等
     */
    private List<String> getPatches() {
        List<String> patches = new ObjectArrayList<>();
        if (VersionHelper.isPaper()) {
            patches.add("paper");
        }
        if (VersionHelper.isFolia()) {
            patches.add("folia");
        }
        if (VersionHelper.isLeaves()) {
            patches.add("leaves");
        }
        if (VersionHelper.isCanvas()) {
            patches.add("canvas");
        }
        return patches;
    }

    @Override
    public InputStream resourceStream(String filePath) {
        return getResource(CharacterUtils.replaceBackslashWithSlash(filePath));
    }

    private @Nullable InputStream getResource(String filename) {
        if (filename == null) {
            throw new IllegalArgumentException("filename cannot be null");
        }
        try {
            URL url = this.getClass().getClassLoader().getResource(filename);
            if (url == null) {
                return null;
            }
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            return connection.getInputStream();
        } catch (IOException ex) {
            return null;
        }
    }

    @Override
    public File dataFolderFile() {
        return this.dataFolderPath.toFile();
    }

    @Override
    public Path dataFolderPath() {
        return this.dataFolderPath;
    }

    @SuppressWarnings("deprecation")
    @Override
    public String pluginVersion() {
        return javaPlugin().getDescription().getVersion();
    }

    @Override
    public String serverVersion() {
        return VersionHelper.MINECRAFT_VERSION.version();
    }

    public JavaPlugin javaPlugin() {
        return this.javaPlugin;
    }

    @Override
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void saveResource(String resourcePath) {
        if (resourcePath.isEmpty()) {
            throw new IllegalArgumentException("ResourcePath cannot be null or empty");
        }

        File outFile = new File(dataFolderFile(), resourcePath);
        if (outFile.exists())
            return;

        resourcePath = resourcePath.replace('\\', '/');
        InputStream in = resourceStream(resourcePath);
        if (in == null)
            return;

        int lastIndex = resourcePath.lastIndexOf('/');
        File outDir = new File(dataFolderFile(), resourcePath.substring(0, Math.max(lastIndex, 0)));

        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        try {
            OutputStream out = new FileOutputStream(outFile);
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.close();
            in.close();

        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public boolean isReloading() {
        return this.reloading.get();
    }

    @Override
    public boolean isInitializing() {
        return this.isInitializing;
    }

    @Override
    public SyncLogger logger() {
        return this.logger;
    }

    @Override
    public ClassPathAppender sharedClassPathAppender() {
        return this.sharedClassPathAppender;
    }

    @Override
    public ClassPathAppender privateClassPathAppender() {
        return this.privateClassPathAppender;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <W> SchedulerAdapter<W> scheduler() {
        return (SchedulerAdapter<W>) this.scheduler;
    }

    @Override
    public DependencyManager dependencyManager() {
        return this.dependencyManager;
    }

    @Override
    public CompatibilityManager compatibilityManager() {
        return this.compatibilityManager;
    }

    @Override
    public ConfigurationManager configurationManager() {
        return this.configurationManager;
    }

    @Override
    public TranslationManager translationManager() {
        return this.translationManager;
    }

    public DataRegistry dataRegistry() {
        return this.dataRegistry;
    }

    public PlayerSerialExecutor playerExecutor() {
        return this.playerExecutor;
    }

    public PlayerDataPipeline playerDataPipeline() {
        return this.playerDataPipeline;
    }

    public SnapshotService snapshotService() {
        return this.snapshotService;
    }

    public SnapshotStash snapshotStash() {
        return this.snapshotStash;
    }

    public BinarySnapshotCodec binaryCodec() {
        return this.binaryCodec;
    }

    public DocumentSnapshotCodec documentCodec() {
        return this.documentCodec;
    }

    public SessionManager sessionManager() {
        return this.sessionManager;
    }

    public LoginGate loginGate() {
        return this.loginGate;
    }

    public RedisConnector redisConnector() {
        return this.redisConnector;
    }

    public SessionLock sessionLock() {
        return this.sessionLock;
    }

    public MessageBrokerManager messageBrokerManager() {
        return this.messageBrokerManager;
    }

    public ServerHeartBeats serverRegistry() {
        return this.serverHeartBeats;
    }

    public HandoffManager handoffManager() {
        return this.handoffManager;
    }

    public StorageProvider storageProvider() {
        return this.storageProvider;
    }
}

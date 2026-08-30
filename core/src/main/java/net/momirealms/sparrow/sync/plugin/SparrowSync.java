package net.momirealms.sparrow.sync.plugin;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.momirealms.sparrow.sync.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.codec.DocumentSnapshotCodec;
import net.momirealms.sparrow.sync.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.command.BukkitCommandManager;
import net.momirealms.sparrow.sync.command.CommandManager;
import net.momirealms.sparrow.sync.compatibility.CompatibilityManager;
import net.momirealms.sparrow.sync.configuration.ConfigurationManager;
import net.momirealms.sparrow.sync.configuration.PluginConfig;
import net.momirealms.sparrow.sync.configuration.ServerConfig;
import net.momirealms.sparrow.sync.data.SnapshotApplier;
import net.momirealms.sparrow.sync.data.item.ItemCodec;
import net.momirealms.sparrow.sync.data.type.*;
import net.momirealms.sparrow.sync.executor.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.locale.TranslationManagerImpl;
import net.momirealms.sparrow.sync.plugin.classpath.ClassPathAppender;
import net.momirealms.sparrow.sync.plugin.dependency.Dependencies;
import net.momirealms.sparrow.sync.plugin.dependency.Dependency;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyManager;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.filter.DisconnectLogFilter;
import net.momirealms.sparrow.sync.plugin.scheduler.BukkitSchedulerAdapter;
import net.momirealms.sparrow.sync.plugin.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.session.SessionListener;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SnapshotService;
import net.momirealms.sparrow.sync.session.SnapshotStash;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.mongo.MongoStorageProvider;
import net.momirealms.sparrow.sync.util.CharacterUtils;
import net.momirealms.sparrow.sync.util.ExceptionCollector;
import net.momirealms.sparrow.sync.util.ReflectionUtils;
import net.momirealms.sparrow.sync.util.VersionHelper;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SparrowSync implements Plugin {
    private static SparrowSync instance;

    private final PluginLogger logger;
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
    private boolean isReloading;
    private boolean isInitializing;
    private boolean successfullyLoaded = false;
    private boolean successfullyEnabled = false;

    private final DataRegistry dataRegistry = new DataRegistry();
    private PlayerSerialExecutor playerExecutor;
    private SnapshotApplier snapshotApplier; // todo 这个玩意其他地方有用吗? 是否可以考虑合并到 Service
    private StorageProvider storageProvider; // todo 这个玩意其他地方有用吗? 是否可以考虑合并到 Service
    private SnapshotStash snapshotStash;     // todo 这个玩意其他地方有用吗? 是否可以考虑合并到 Service
    private SnapshotService snapshotService;
    private SessionManager sessionManager;

    SparrowSync(PluginLogger logger, Path dataFolderPath, ClassPathAppender sharedClassPathAppender, ClassPathAppender privateClassPathAppender) {
        instance = this;
        this.logger = logger;
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
        this.compatibilityManager = new CompatibilityManager(this);
        this.playerExecutor = new PlayerSerialExecutor(this.logger, PluginConfig.synchronization$workerThreads());
        this.setUpInternalDataTypes();
        ((Logger) LogManager.getRootLogger()).addFilter(new DisconnectLogFilter());
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
        this.successfullyLoaded = true;
        this.compatibilityManager.onLoad(); // 集成插件管理器
        // 服务器身份缺失时不放行
        if (ServerConfig.serverId().isEmpty()) {
            this.logger.error(TranslationManager.console(LogConstants.SERVER_ID_MISSING));
            Bukkit.getServer().shutdown();
            return;
        }
        this.setupStorage();                // 启动存储
    }

    @Override
    public void onPluginEnable() {
        if (this.successfullyEnabled) {
            logger().error(" ");
            logger().error(" ");
            logger().error(" ");
            logger().error(TranslationManager.console(LogConstants.PLUGIN_RELOAD_AT_RUNTIME));
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
            logger().error(TranslationManager.console(LogConstants.PLUGIN_LOAD_FAILED));
            logger().error(" ");
            logger().error(" ");
            logger().error(" ");
            Bukkit.getServer().shutdown();
            return;
        }
        // 命令管理器
        this.commandManager = new BukkitCommandManager(this);
        this.commandManager.registerDefaultFeatures();
        // 延迟初始化事件
        this.isInitializing = true;
        this.initASMProxies();
        // 集成插件管理器
        this.compatibilityManager.onEnable();
        // 延迟重载逻辑
        this.scheduler.sync().runDelayed(this::onServerLoaded);
    }

    public void onServerLoaded() {
        // 集成插件管理器
        this.compatibilityManager.onDelayedEnable();
        // 冻结注册表并装配快照.
        if (this.snapshotApplier != null) return;
        this.snapshotApplier = new SnapshotApplier(this.dataRegistry, this.logger);
        this.logger.info(TranslationManager.console(LogConstants.PLUGIN_REGISTRY_FROZEN, String.valueOf(this.dataRegistry.declarations().size())));
        this.snapshotService = new SnapshotService(this, this.snapshotApplier, this.storageProvider, this.snapshotStash, this.logger);
        // 会话状态机. todo 登录管线统一走扣包 Gate (M9, Paper 与 Spigot 同一条), 落地前由 join 回落路径兜着
        this.sessionManager = new SessionManager(this, this.logger);
        Bukkit.getPluginManager().registerEvents(new SessionListener(this, this.snapshotService, this.sessionManager), this.javaPlugin);
        // 预热 DFU 的 ITEM_STACK CODEC.
        this.scheduler.async().execute(ItemCodec::warmUp);
        // 标记
        this.isInitializing = false;
    }

    @Override
    public void onPluginReload() {
    }

    @Override
    public void onPluginDisable() {
        if (this.sessionManager != null) this.sessionManager.shutdown(); // 为 ACTIVE 会话投递 SHUTDOWN 保存
        if (this.playerExecutor != null) this.playerExecutor.shutdown(PluginConfig.synchronization$shutdownTimeoutSeconds(), TimeUnit.SECONDS);
        if (this.snapshotService != null) this.snapshotService.stashUnsettled(); // 排空超时没保存完的快照落盘, 下次启动插回

        if (this.scheduler != null) this.scheduler.shutdownScheduler();
        if (this.scheduler != null) this.scheduler.shutdownExecutor();
        if (this.storageProvider != null) this.storageProvider.shutdown();
        if (this.dependencyManager != null) this.dependencyManager.shutdown();
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

    /**
     * 注册内置的数据类型并装配存储.
     */
    private void setUpInternalDataTypes() {
        this.dataRegistry.register(new ExperienceDataType());
        this.dataRegistry.register(new HealthDataType());
        this.dataRegistry.register(new HungerDataType());
        this.dataRegistry.register(new PotionEffectsDataType());
        this.dataRegistry.register(new GameModeDataType());
        this.dataRegistry.register(new InventoryDataType(logger));
        this.dataRegistry.register(new EnderChestDataType(logger));
        this.dataRegistry.register(new PDCDataType(Set.copyOf(PluginConfig.synchronization$pdcMergeNamespaces())));
    }

    /**
     * 安装并初始化持久化存储.
     */
    private void setupStorage() {
        // 预热加载 ZSTD 压缩
        CompressorRegistry compressor = PluginConfig.synchronization$compression();
        try {
            byte[] probe = compressor.compress(new byte[64]);
            compressor.decompress(probe, 0, probe.length, 256);
        } catch (Throwable throwable) {
            this.logger.error(TranslationManager.console(LogConstants.STORAGE_COMPRESSOR_FAILED), throwable);
            Bukkit.getServer().shutdown();
            return;
        }
        // 加载数据库
        try {
            BinarySnapshotCodec binaryCodec = new BinarySnapshotCodec(compressor);
            DocumentSnapshotCodec codec = new DocumentSnapshotCodec(this.dataRegistry, binaryCodec);
            this.snapshotStash = new SnapshotStash(this.dataFolderPath, binaryCodec, this.logger);
            switch (PluginConfig.database$type()) {
                case MONGODB -> {
                    PluginConfig.MongoOptions mongodb = PluginConfig.database$mongodb();
                    this.storageProvider = new MongoStorageProvider(mongodb, codec, this.playerExecutor, this.scheduler.async(), this.logger);
                    this.storageProvider.initialize();
                    this.logger.info(TranslationManager.console(LogConstants.STORAGE_READY, mongodb.database()));
                    // 上次没能落库的本地快照插回数据库
                    this.scheduler.async().execute(() -> this.snapshotStash.restorePending(this.storageProvider));
                }
                case MYSQL -> {
                    this.logger.error(TranslationManager.console(LogConstants.STORAGE_MYSQL_NOT_IMPLEMENTED));
                    Bukkit.getServer().shutdown();
                }
            }
        } catch (Throwable throwable) {
            this.logger.error(TranslationManager.console(LogConstants.STORAGE_SETUP_FAILED), throwable);
            Bukkit.getServer().shutdown();
        }
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
        CompletableFuture<ReloadResult> future = new CompletableFuture<>();
        asyncExecutor.execute(() -> {
            long asyncTime = -1;
            int issues = 0;
            try {
                if (this.isReloading) {
                    future.complete(ReloadResult.failure());
                    return;
                }
                this.isReloading = true;
                long startTime = System.currentTimeMillis();
                this.configurationManager.reload();
                this.translationManager.reload();
                // TODO 执行异步重载任务


                asyncTime = System.currentTimeMillis() - startTime;
            } catch (Throwable e) {
                this.logger().warn(TranslationManager.console(LogConstants.PLUGIN_RELOAD_FAILED), e);
                future.complete(ReloadResult.failure());
            } finally {
                long finalAsyncTime = asyncTime;
                syncExecutor.execute(() -> {
                    try {
                        long syncStartTime = System.currentTimeMillis();
                        // TODO 执行同步重载任务



                        long syncTime = System.currentTimeMillis() - syncStartTime;
                        future.complete(ReloadResult.success(finalAsyncTime, syncTime, issues));
                    } catch (Throwable e) {
                        this.logger().warn(TranslationManager.console(LogConstants.PLUGIN_RELOAD_FAILED), e);
                        future.complete(ReloadResult.failure());
                    } finally {
                        this.isReloading = false;
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
        return this.isReloading;
    }

    @Override
    public boolean isInitializing() {
        return this.isInitializing;
    }

    @Override
    public PluginLogger logger() {
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

    public SnapshotApplier snapshotApplier() {
        return this.snapshotApplier;
    }

    public SnapshotService snapshotService() {
        return this.snapshotService;
    }

    public SessionManager sessionManager() {
        return this.sessionManager;
    }

    public StorageProvider storageProvider() {
        return this.storageProvider;
    }
}

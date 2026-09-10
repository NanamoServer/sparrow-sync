package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.storage.StorageType;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.mapper.YamlMapper;
import net.momirealms.sparrow.yaml.mapper.YamlMapperFactory;
import net.momirealms.sparrow.yaml.route.Route;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.BlankLineBefore;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.Comment;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.Configuration;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.YamlIgnore;
import net.momirealms.sparrow.yaml.upgrade.YamlUpgradePipeline;
import net.momirealms.sparrow.yaml.upgrade.version.FieldVersionExtractor;
import org.bukkit.GameMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PluginConfig {
    private static final String CONFIG_FILE = "config.yml";
    private static volatile ConfigDefinition config;

    private final Plugin plugin;
    private final Path configFilePath;
    private final YamlMapper<ConfigDefinition> configMapper;
    private MapOptions startupMapOptions; // 首次成功加载的地图开关和来源模板, 随本次服务器运行固定

    PluginConfig(Plugin plugin, SparrowYaml sparrowYaml) {
        this.plugin = plugin;
        this.configFilePath = plugin.dataFolderPath().resolve(CONFIG_FILE);
        YamlUpgradePipeline upgradePipeline = YamlUpgradePipeline.builder()
                .versionExtractor(new FieldVersionExtractor("config-version"))
                .addPatch("14"::equals, patch -> patch.patch((defaults, local, context) -> {
                    Route type = Route.from("synchronization", "map", "type");
                    Route enabled = Route.from("synchronization", "map", "enabled");
                    String previous = local.getString(type);
                    if ("NONE".equals(previous) || "HIDE".equals(previous)) {
                        if (local.getNodeOrNull(enabled) == null) {
                            local.setAndGet(enabled, "HIDE".equals(previous));
                        }
                        local.setAndGet(type, "HIDE");
                    }
                    return local;
                }))
                .build();
        YamlMapperFactory mapperFactory = YamlMapperFactory.builder()
                .backupOnUpgrade(true)
                .sparrowYaml(sparrowYaml)
                .upgradePipeline(upgradePipeline)
                .build();
        this.configMapper = mapperFactory.create(ConfigDefinition.class, ConfigDefinition::new);
    }

    void reload() {
        try {
            ConfigDefinition loadedConfig = this.configMapper.load(this.configFilePath).value();
            if (this.startupMapOptions != null) {
                loadedConfig.synchronization.map.enabled = this.startupMapOptions.enabled;
                loadedConfig.synchronization.map.mapOwnerId = this.startupMapOptions.mapOwnerId;
            }
            loadedConfig.synchronization.map.validate();
            loadedConfig.synchronization.pdcMergeBlacklist = PDCMergeBlacklist.of(loadedConfig.synchronization.pdcMergeNamespaces);
            loadedConfig.synchronization.attributes.freeze();
            loadedConfig.synchronization.compiledSaveTriggers = SaveTriggers.of(loadedConfig.synchronization.saveTriggers);
            if (this.startupMapOptions == null) {
                this.startupMapOptions = loadedConfig.synchronization.map;
            }
            config = loadedConfig;
        } catch (Exception e) {
            this.plugin.logger().error("Failed to load " + CONFIG_FILE, e);
        }
    }

    // 配置文件
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class ConfigDefinition {
        @Comment("Configuration file version, do not modify this value")
        @Comment(lang = "zh-CN", value = "配置文件版本, 请勿修改此值")
        String configVersion = DependencyVersions.CONFIG_VERSION;

        @Comment("Enables or disables metrics collection via BStats")
        @Comment(lang = "zh-CN", value = "是否启用 BStats 统计数据收集")
        boolean metrics = true;

        @Comment("Enables automatic update checks")
        @Comment(lang = "zh-CN", value = "是否自动检查更新")
        boolean updateChecker = true;

        @Comment({
                "Language of console messages, e.g. zh_CN or en_US",
                "Leave empty to follow the system locale; if no matching translation file exists, fall back to en_US"
        })
        @Comment(lang = "zh-CN", value = {
                "控制台消息的语言, 例如 zh_CN, en_US 等",
                "留空时跟随系统语言, 缺少对应翻译文件时回退到 en_US"
        })
        String forcedLocale = "";

        @BlankLineBefore
        @Comment("Redis connection settings for cross-server session locks, messaging, and caching some data")
        @Comment(lang = "zh-CN", value = "Redis 连接设置, 用于跨服会话锁, 消息通信和部分数据缓存")
        RedisOptions redis = new RedisOptions();

        @BlankLineBefore
        @Comment("Persistent storage settings for player data snapshots")
        @Comment(lang = "zh-CN", value = "玩家数据快照的持久化存储设置")
        DatabaseOptions database = new DatabaseOptions();

        @BlankLineBefore
        @Comment("Data synchronization settings")
        @Comment(lang = "zh-CN", value = "数据同步设置")
        SynchronizationOptions synchronization = new SynchronizationOptions();

        @BlankLineBefore
        @Comment("Logging settings")
        @Comment(lang = "zh-CN", value = "日志设置")
        LoggingOptions logging = new LoggingOptions();
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class RedisOptions {
        String url = "redis://localhost:6379/0";
        String username = "";
        String password = "";

        public String url() {
            return this.url;
        }

        public String username() {
            return this.username;
        }

        public String password() {
            return this.password;
        }
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class DatabaseOptions {
        @Comment("Storage backend for player data snapshots, available values: MONGODB, MYSQL, POSTGRESQL")
        @Comment(lang = "zh-CN", value = "玩家数据快照所使用的存储方式, 可选值: MONGODB、MYSQL、POSTGRESQL")
        StorageType type = StorageType.MONGODB;

        @Comment("MYSQL database settings")
        @Comment(lang = "zh-CN", value = "MYSQL 数据库设置")
        MysqlOptions mysql = new MysqlOptions();

        @Comment("POSTGRESQL database settings")
        @Comment(lang = "zh-CN", value = "POSTGRESQL 数据库设置")
        PostgresOptions postgresql = new PostgresOptions();

        @Comment("MONGODB database settings")
        @Comment(lang = "zh-CN", value = "MONGODB 数据库设置")
        MongoOptions mongodb = new MongoOptions();
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class MongoOptions {
        String url = "mongodb://localhost:27017";
        String database = "minecraft";
        String username = "";
        String password = "";
        String authSource = "admin";
        String collectionPrefix = "sparrow_sync_";

        // 配置映射走无参构造加字段注入, 全参构造供程序化装配和测试使用
        public MongoOptions() {
        }

        public MongoOptions(String url, String database, String username, String password, String authSource, String collectionPrefix) {
            this.url = url;
            this.database = database;
            this.username = username;
            this.password = password;
            this.authSource = authSource;
            this.collectionPrefix = collectionPrefix;
        }

        public String url() {
            return this.url;
        }

        public String database() {
            return this.database;
        }

        public String username() {
            return this.username;
        }

        public String password() {
            return this.password;
        }

        public String authSource() {
            return this.authSource;
        }

        public String collectionPrefix() {
            return this.collectionPrefix;
        }
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class MysqlOptions {
        String url = "jdbc:mysql://localhost:3306/minecraft?connectTimeout=5000&socketTimeout=10000&characterEncoding=UTF-8"; // JDBC 地址与驱动参数, 超时单位为毫秒
        String username = "root";
        String password = "";
        String tablePrefix = "sparrow_sync_";

        public String url() {
            return this.url;
        }

        public String username() {
            return this.username;
        }

        public String password() {
            return this.password;
        }

        public String tablePrefix() {
            return this.tablePrefix;
        }
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class PostgresOptions {
        String url = "jdbc:postgresql://localhost:5432/minecraft?connectTimeout=5&socketTimeout=10"; // pgJDBC 超时单位为秒
        String username = "postgres";
        String password = "";
        @Comment("Up to 40 lowercase ASCII letters, digits or underscores; leave room for PostgreSQL index names")
        @Comment(lang = "zh-CN", value = "最多 40 个小写 ASCII 字母、数字或下划线, 为 PostgreSQL 索引名预留长度")
        String tablePrefix = "sparrow_sync_";

        public String url() {
            return this.url;
        }

        public String username() {
            return this.username;
        }

        public String password() {
            return this.password;
        }

        public String tablePrefix() {
            return this.tablePrefix;
        }
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class SynchronizationOptions {
        @Comment({
                "Number of serial executor threads for player data tasks, limited to 1-64",
                "Recommended values based on basic testing:",
                "For <= 150 players online on this server, set this to 2.",
                "For <= 350 players online on this server, set this to 4.",
                "For <= 700 players online on this server, set this to 8."
        })
        @Comment(lang = "zh-CN", value = {
                "处理玩家数据任务的串行线程数, 范围为 1-64",
                "经过一些简单测试的推荐值: ",
                "当前单服务器在线玩家数 <= 150 人时, 推荐设置为 2.",
                "当前单服务器在线玩家数 <= 350 人时, 推荐设置为 4.",
                "当前单服务器在线玩家数 <= 700 人时, 推荐设置为 8.",
        })
        int workerThreads = 4;

        @Comment({
                "Maximum time without a completed save request during shutdown, in seconds; non-positive values skip waiting",
                "Progress is reported every second; each increase resets the timeout, so total saving time may exceed this value",
                "After all save requests finish, map publication and executor draining share one additional fixed budget of this length",
                "On timeout or interruption, cleanup starts without an additional wait; unfinished complete snapshots are submitted for local stashing",
                "External process or container shutdown limits must allow enough time for saving and cleanup"
        })
        @Comment(lang = "zh-CN", value = {
                "当关服执行保存时发现保存任务一直没有被推进时的最长等待时间, 单位为秒, 非正数表示不等待",
                "超时或中断后直接进入收尾, 未完成的完整快照交由本地暂存处理"
        })
        int shutdownTimeoutSeconds = 30;

        @Comment("Maximum number of unpinned snapshots kept per player; excess snapshots are removed at an appropriate time, starting with the oldest")
        @Comment(lang = "zh-CN", value = "每名玩家最多保留的未固定快照的数量, 超出后会在合适的时机删除最旧的未固定快照")
        int maxSnapshots = 32;

        @Comment({
                "Maximum number of save retries for snapshots being saved when the database goes down",
                "-1 retries until the database recovers, typically suitable for temporary unavailability caused by network instability",
                "Snapshots that exhaust their retries are saved to local disk with a warning and inserted into the database after a server restart, without discarding the data",
                "Problems that retries cannot resolve, such as abnormal snapshot sizes or encoding errors, are not retried; the plugin saves the data to local disk where possible and alerts administrators"
        })
        @Comment(lang = "zh-CN", value = {
                "如果你的数据库突然宕机了, 正在进行保存的快照重新尝试保存的最大次数",
                "-1 表示持续重试直到数据库恢复, 基本适用于数据库网络波动导致的暂时不可用的情况",
                "重试次数耗尽的快照会被保存到本地磁盘并发出警告, 磁盘的快照将在服务器重新启动后重新插入数据库, 不会丢弃数据",
                "快照数据大小异常、编码错误等无法通过重试解决的问题将不会进行重试，同时会尽可能保存数据到本地磁盘并提醒管理员处理"
        })
        int maxSaveRetries = -1;

        @Comment("Maximum time to wait for player data to be ready during login, in seconds")
        @Comment(lang = "zh-CN", value = "在玩家登录阶段等待玩家数据准备完成的最长时间, 单位为秒")
        int loginTimeoutSeconds = 60;

        @Comment({
                "Compression method for new snapshots; existing data remains readable after changing this",
                "Available: ZSTD, DEFLATE, NONE",
                "  ZSTD    - fast saves and loads with a high compression ratio, recommended",
                "  DEFLATE - the JDK codec, requires no native library; use it if Zstd fails to load",
                "  NONE    - no compression; larger snapshots take longer to transfer to the database"
        })
        @Comment(lang = "zh-CN", value = {
                "新快照使用的压缩方式, 修改后仍可读取已有数据",
                "可选值: ZSTD、DEFLATE、NONE",
                "  ZSTD    - 保存和加载速度快, 压缩率高, 推荐使用",
                "  DEFLATE - JDK 自带压缩算法, 无需原生库, Zstd 加载失败时可使用",
                "  NONE    - 不压缩, 快照越大, 传输到数据库所需时间也越长"
        })
        CompressorRegistry compression = CompressorRegistry.ZSTD;

        @BlankLineBefore
        @Comment("Player data types enabled for synchronization; changes require a server restart")
        @Comment(lang = "zh-CN", value = "启用同步的玩家数据类型, 修改后需要重启服务器才能生效")
        DataTypes dataTypes = new DataTypes();

        @BlankLineBefore
        @Comment({
                "Writes compatible snapshot data directly into player data that has not yet been loaded, on an asynchronous thread during login preparation",
                "Strongly recommended: moves most of the synchronization work during login to a separate asynchronous thread and can also speed up vanilla player data loading",
                "This option does not block Netty threads or the server main thread; try disabling it if your server encounters errors or compatibility issues while it is enabled",
                "On Folia, disabling this leaves location to a best-effort asynchronous teleport whose result is neither awaited nor checked"
        })
        @Comment(lang = "zh-CN", value = {
                "在登录准备阶段将兼容的快照数据直接在异步线程写入未加载的玩家数据中",
                "非常推荐启用, 这将进入服务器时的大部分同步成本转移到了独立的异步线程进行, 还可以进一步加快玩家进入服务器时原版的数据加载速度",
                "此选项不会阻塞 Netty 线程和服务器主线程, 若你使用的服务端在此选项开启时发生了错误或兼容性问题, 请尝试关闭此选项",
                "在 Folia 上关闭此选项时, 玩家位置只会交由一次异步传送尽力恢复, 既不等待也不校验结果"
        })
        NativeAsyncApplyOptions nativeAsyncApply = new NativeAsyncApplyOptions();

        @BlankLineBefore
        @Comment("When performing an online rollback snapshot, you can choose to skip certain data without affecting the login synchronization")
        @Comment(lang = "zh-CN", value = "在线回滚快照时可以选择不同步部分数据, 不影响登录同步")
        OnlineRestoreOptions onlineRestore = new OnlineRestoreOptions();

        @BlankLineBefore
        @Comment("Map synchronization and origin settings")
        @Comment(lang = "zh-CN", value = "地图同步和来源设置")
        MapOptions map = new MapOptions();

        @BlankLineBefore
        @Comment("Advancement synchronization settings")
        @Comment(lang = "zh-CN", value = "成就进度同步设置")
        AdvancementsOptions advancements = new AdvancementsOptions();

        @BlankLineBefore
        @Comment("Attribute synchronization settings")
        @Comment(lang = "zh-CN", value = "属性同步设置")
        AttributeOptions attributes = new AttributeOptions();

        @BlankLineBefore
        @Comment({
                "Player persistent data (PDC) merge blacklist; listed paths are neither captured nor merged during synchronization",
                "pdc-merge-namespaces:",
                "  - \"sparrow-sync-ignore\" # represents custom_data -> sparrow-sync-ignore",
                "  - [\"sparrow-sync\", \"ignore\"] # represents custom_data -> sparrow-sync -> ignore"
        })
        @Comment(lang = "zh-CN", value = {
                "玩家持久化数据 (PDC) 合并黑名单, 同步时, 黑名单中的路径不会被采集或合并",
                "pdc-merge-namespaces:",
                "  - \"sparrow-sync-ignore\" # 表示 custom_data -> sparrow-sync-ignore",
                "  - [\"sparrow-sync\", \"ignore\"] # 表示 custom_data -> sparrow-sync -> ignore"
        })
        List<Object> pdcMergeNamespaces = List.of();

        @BlankLineBefore
        @Comment("Automatic data snapshot save settings")
        @Comment(lang = "zh-CN", value = "自动保存数据快照设置")
        SaveTriggerOptions saveTriggers = new SaveTriggerOptions();

        @YamlIgnore
        PDCMergeBlacklist pdcMergeBlacklist = PDCMergeBlacklist.empty();

        @YamlIgnore
        SaveTriggers compiledSaveTriggers = SaveTriggers.of(this.saveTriggers);
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class OnlineRestoreOptions {
        @Comment("Restores health online; life transitions use the server's normal death and respawn flow")
        @Comment(lang = "zh-CN", value = "在线回滚时同步血量, 生死切换使用服务端原生死亡和重生流程")
        boolean syncHealth = false;

        @Comment("Restores location online; teleport events and plugin restrictions still apply")
        @Comment(lang = "zh-CN", value = "在线回滚时同步位置, 传送事件和其他插件的限制仍然生效")
        boolean syncLocation = false;

        public boolean syncHealth() {
            return this.syncHealth;
        }

        public boolean syncLocation() {
            return this.syncLocation;
        }
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class MapOptions {
        @Comment({
                "Enables map item encoding and decoding; changes require a server restart. When disabled, the plugin does not process any map items or synchronize map data",
                "Because of how Minecraft stores maps, map synchronization is best-effort; enabling it means",
                "the plugin modifies map items, including updating and reassigning map-id values and recording required data in custom_data",
                "If you use a \"cross-server auction house\" or a \"plugin-managed portable backpack\" to bypass map item scanning during synchronization, the plugin cannot guarantee that the bypassed maps will display correctly",
                "Uninstalling the plugin cannot fully restore map data components, but we aim to keep maps viewable and functional; this feature comes with these trade-offs"
        })
        @Comment(lang = "zh-CN", value = {
                "是否启用地图物品的编码和解码, 修改后需要重启服务器生效; 关闭时插件不处理地图物品和地图数据",
                "注意: 因为 Minecraft 地图存储的特殊性, 我们仍然只能做到尽可能同步地图数据, 这意味着开启同步后",
                "地图物品会被本插件进行一定程度的修改, 比如更新和重分配 map-id, 在 custom_data 上记录一些必要的数据等, 这意味着卸载插件后无法完整复原最初的地图数据组件",
                "如果你使用了 \"跨服交易行, 由插件管理的随时背包\" 绕开同步时对地图物品的扫描的话, 插件也无法保证绕过的地图是否存在错误显示的问题",
                "总而言之, 插件会尽可能保证地图仍然是可看可正常工作的, 这是一项有一定代价的功能"
        })
        boolean enabled = false;

        @Comment({
                "Map data synchronization mode, available values: HIDE, SYNC",
                "HIDE does not synchronize map data between servers; when a player switches servers, it removes map-id and records custom_data, making the map unusable on the destination server to prevent players from using maps to steal other servers' map data",
                "SYNC saves and synchronizes all map data; maps captured in snapshots receive reassigned map-id values and custom_data, source map data is saved to the database, and persistent negative-ID replicas are created on other servers",
                "Map processing failures produce a warning and leave the original map data unchanged"
        })
        @Comment(lang = "zh-CN", value = {
                "地图数据的同步模式, 可选值: HIDE、SYNC",
                "HIDE 模式下, 不会同步服务器之间的地图数据, 而是在玩家跨服时移除地图的 map-id 并记录 custom_data, 使地图在跨服后无法继续工作, 这是为了防止玩家通过地图机制窃取其他服务器的地图数据",
                "SYNC 模式下, 会对所有地图数据进行保存和同步, 被快照捕获的地图会被重新分配 map-id 并记录 custom_data, 然后向数据库保存来源地图数据, 并在其他服务器上创建使用负数 ID 的持久化地图副本",
                "地图处理失败时会输出警告, 并保留原地图数据不变"
        })
        MapType type = MapType.SYNC;

        @Comment({
                "Map source ID for this server; supports ${server-id} and ${world-uuid}; changes require a server restart",
                "server-id is the server ID configured in server.yml",
                "world-uuid is the UUID of the overworld where the map data belongs",
                "Maps use this value to determine ownership; changing it makes previously encoded map items count as maps from another server"
        })
        @Comment(lang = "zh-CN", value = {
                "本服的地图源 ID, 支持占位符 ${server-id} 和 ${world-uuid}, 修改后需要重启服务器生效",
                "server-id 为 server.yml 配置中的服务器 ID",
                "world-uuid 为地图数据所属主世界的 UUID",
                "地图通过这个值来判断地图数据归属, 所以修改此值后, 之前编码的地图物品会被视为其他服务器的地图"
        })
        String mapOwnerId = "${server-id}-${world-uuid}";

        @Comment("Allows adding or removing banner markers on negative-ID maps; reloadable")
        @Comment(lang = "zh-CN", value = "是否允许对负数 ID 地图添加或移除旗帜标记, 可重载")
        boolean allowBannerModification = false;

        @Comment({"Allows vanilla locking of negative-ID maps; reloadable", "Creates a new local map ID; this does not repair the derived map's cross-server identity"})
        @Comment(lang = "zh-CN", value = {"是否允许原版锁定负数 ID 地图的操作, 可重载", "操作会生成本服新地图 ID, 派生地图的跨服身份仍需另行处理"})
        boolean allowLock = false;

        @Comment({"Allows vanilla scaling of negative-ID maps; reloadable", "Creates a new local map ID; this does not repair the derived map's cross-server identity or dimension"})
        @Comment(lang = "zh-CN", value = {"是否允许原版缩放负数 ID 地图的操作, 可重载", "操作会生成本服新地图 ID, 派生地图的跨服身份和维度仍需另行处理"})
        boolean allowScale = false;

        @Comment({"Allows copying negative-ID maps with cartography, crafting, and crafter recipes; reloadable", "Creative cloning and copies made directly by other plugins are outside these controls"})
        @Comment(lang = "zh-CN", value = {"是否允许通过制图台、工作台和合成器配方复制负数 ID 地图, 可重载", "不限制创造模式克隆和其他插件通过 API 复制物品"})
        boolean allowCopy = true;

        public boolean enabled() {
            return this.enabled;
        }

        @NotNull
        public MapType type() {
            return this.type;
        }

        @NotNull
        public String mapOwnerId() {
            return this.mapOwnerId;
        }

        public boolean allowBannerModification() {
            return this.allowBannerModification;
        }

        public boolean allowLock() {
            return this.allowLock;
        }

        public boolean allowScale() {
            return this.allowScale;
        }

        public boolean allowCopy() {
            return this.allowCopy;
        }

        /** 将来源模板中的服务器标识和主世界 UUID 替换为本服值. */
        @NotNull
        public String resolveOwnerId(@NotNull String serverId, @NotNull UUID worldUuid) {
            return this.mapOwnerId.replace("${server-id}", serverId).replace("${world-uuid}", worldUuid.toString());
        }

        private void validate() {
            if (!this.enabled) return;
            String literals = this.mapOwnerId.replace("${server-id}", "").replace("${world-uuid}", "");
            if (this.mapOwnerId.isBlank() || literals.contains("${")) {
                throw new IllegalArgumentException("map-owner-id must be non-blank and may only use ${server-id} and ${world-uuid}");
            }
        }
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class DataTypes {
        boolean advancements = true;
        boolean attributes = true;
        boolean enchantmentSeed = true;
        boolean enderChest = true;
        boolean experience = true;
        boolean flightStatus = true;
        boolean gameMode = true;
        boolean health = true;
        boolean hunger = true;
        boolean inventory = true;
        boolean location = false;
        boolean persistentData = true;
        boolean potionEffects = true;
        boolean statistics = true;

        public boolean advancements() {
            return this.advancements;
        }

        public boolean attributes() {
            return this.attributes;
        }

        public boolean enchantmentSeed() {
            return this.enchantmentSeed;
        }

        public boolean enderChest() {
            return this.enderChest;
        }

        public boolean experience() {
            return this.experience;
        }

        public boolean flightStatus() {
            return this.flightStatus;
        }

        public boolean gameMode() {
            return this.gameMode;
        }

        public boolean health() {
            return this.health;
        }

        public boolean hunger() {
            return this.hunger;
        }

        public boolean inventory() {
            return this.inventory;
        }

        public boolean location() {
            return this.location;
        }

        public boolean persistentData() {
            return this.persistentData;
        }

        public boolean potionEffects() {
            return this.potionEffects;
        }

        public boolean statistics() {
            return this.statistics;
        }
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class NativeAsyncApplyOptions {
        boolean playerData = true;
        boolean advancements = true;
        boolean statistics = true;

        public boolean playerData() {
            return this.playerData;
        }

        public boolean advancements() {
            return this.advancements;
        }

        public boolean statistics() {
            return this.statistics;
        }
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class AdvancementsOptions {
        @Comment({
                "Allows the plugin to inject into the player's PlayerAdvancements class",
                "Enabling this speeds up advancement data capture on the main thread; disabling it uses regular full capture",
                "Try disabling this option if your server encounters errors or compatibility issues while it is enabled"
        })
        @Comment(lang = "zh-CN", value = {
                "是否允许插件注入玩家的 PlayerAdvancements 类",
                "启用后可以加快玩家成就进度数据在主线程的采集速度, 关闭后则使用常规的全量采集",
                "若你使用的服务端在此选项开启时发生了错误或兼容性问题, 请尝试关闭此选项"
        })
        boolean injectProgressChanged = true;

        @Comment({
                "Preserves data and completion progress for advancement IDs unknown to this server",
                "When servers have different advancement registries, vanilla discards unknown advancements and their completion progress",
                "The plugin can retain this data while applying only advancements registered on the current server",
                "Disable this if every server in your network has an identical advancement registry; skipping some scans and checks can improve synchronization speed"
        })
        @Comment(lang = "zh-CN", value = {
                "是否保留当前服务器不认识的成就进度 ID 的数据及其完成情况",
                "如果服务器之间的成就进度注册表不一致时, 原版会将不认识的成就和完成进度直接丢弃",
                "插件可以将这一部分数据保留出来, 只在当前服务器应用已经被注册的成就进度数据",
                "如果你服务器组里的所有服务器的成就进度注册表都确保一致则可关闭, 关闭后插件将跳过一些扫描检查, 可以提升一部分同步速度"
        })
        boolean keepUnknownAdvancements = true;

        public boolean injectProgressChanged() {
            return this.injectProgressChanged;
        }

        public boolean keepUnknownAdvancements() {
            return this.keepUnknownAdvancements;
        }
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class AttributeOptions {
        @Comment({
                "Allows the plugin to inject into the player's AttributeInstance class",
                "Enabling this speeds up attribute data capture on the main thread; disabling it uses regular full capture",
                "Try disabling this option if your server encounters errors or compatibility issues while it is enabled"
        })
        @Comment(lang = "zh-CN", value = {
                "是否允许插件注入玩家的 AttributeInstance 类",
                "启用后可以加快玩家属性数据在主线程的采集速度, 关闭后则使用常规的全量采集",
                "若你使用的服务端在此选项开启时发生了错误或兼容性问题, 请尝试关闭此选项"
        })
        boolean injectConsumer = true;

        @Comment({
                "Attribute keys to save in snapshots during attribute synchronization; supports * wildcards",
                "The list includes both modern and legacy vanilla attribute keys used by supported Minecraft versions"
        })
        @Comment(lang = "zh-CN", value = {
                "属性同步时需要快照保存的属性键, 支持 * 通配符",
                "列表包含受支持 Minecraft 版本使用的新旧原版属性键"
        })
        List<String> whitelist = List.of(
                "minecraft:generic.max_health",
                "minecraft:max_health",
                "minecraft:generic.max_absorption",
                "minecraft:max_absorption",
                "minecraft:generic.luck",
                "minecraft:luck",
                "minecraft:generic.scale",
                "minecraft:scale",
                "minecraft:generic.step_height",
                "minecraft:step_height",
                "minecraft:generic.gravity",
                "minecraft:gravity"
        );

        @Comment("Attribute keys kept local to each server; supports * wildcards")
        @Comment(lang = "zh-CN", value = "仅保留在各服务器本地的属性键, 支持 * 通配符")
        List<String> modifierBlacklist = List.of(
                "minecraft:effect.*",
                "minecraft:creative_mode_*"
        );

        @YamlIgnore
        private KeyPattern[] attributePatterns = compilePatterns(this.whitelist);
        @YamlIgnore
        private KeyPattern[] modifierPatterns = compilePatterns(this.modifierBlacklist);

        @NotNull
        public List<String> whitelist() {
            return this.whitelist;
        }

        public boolean injectConsumer() {
            return this.injectConsumer;
        }

        @NotNull
        public List<String> modifierBlacklist() {
            return this.modifierBlacklist;
        }

        /** 判断属性 key 是否在同步白名单内. */
        public boolean attributeAllowed(@NotNull String attribute) {
            String key = namespaced(attribute);
            for (int i = 0; i < this.attributePatterns.length; i++) {
                if (this.attributePatterns[i].matches(key)) return true;
            }
            return false;
        }

        /** 判断 modifier key 是否留在当前服务器. */
        public boolean modifierBlacklisted(@NotNull String modifier) {
            String key = namespaced(modifier);
            for (int i = 0; i < this.modifierPatterns.length; i++) {
                if (this.modifierPatterns[i].matches(key)) return true;
            }
            return false;
        }

        private void freeze() {
            this.whitelist = List.copyOf(this.whitelist);
            this.modifierBlacklist = List.copyOf(this.modifierBlacklist);
            this.attributePatterns = compilePatterns(this.whitelist);
            this.modifierPatterns = compilePatterns(this.modifierBlacklist);
        }

        private static KeyPattern[] compilePatterns(List<String> patterns) {
            KeyPattern[] compiled = new KeyPattern[patterns.size()];
            for (int i = 0; i < compiled.length; i++) {
                String pattern = namespaced(patterns.get(i));
                compiled[i] = new KeyPattern(pattern, pattern.indexOf('*'));
            }
            return compiled;
        }

        private static boolean matchesPattern(@NotNull String pattern, @NotNull String value) {
            int patternIndex = 0;
            int valueIndex = 0;
            int wildcardIndex = -1;
            int retryIndex = -1;
            while (valueIndex < value.length()) {
                if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == value.charAt(valueIndex)) {
                    patternIndex++;
                    valueIndex++;
                    continue;
                }
                if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                    wildcardIndex = patternIndex++;
                    retryIndex = valueIndex;
                    continue;
                }
                if (wildcardIndex < 0) return false;
                patternIndex = wildcardIndex + 1;
                valueIndex = ++retryIndex;
            }
            while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                patternIndex++;
            }
            return patternIndex == pattern.length();
        }

        @NotNull
        private static String namespaced(@NotNull String key) {
            return key.indexOf(':') < 0 ? "minecraft:" + key : key;
        }

        private record KeyPattern(String value, int wildcard) {
            private boolean matches(String key) {
                if (this.wildcard < 0) return this.value.equals(key);
                if (this.wildcard == this.value.length() - 1) return key.regionMatches(0, this.value, 0, this.wildcard);
                return matchesPattern(this.value, key);
            }
        }
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class SaveTriggerOptions {
        @Comment("Snapshot save policy for world changes")
        @Comment(lang = "zh-CN", value = "切换世界时的快照保存策略")
        WorldChangeTriggerOptions worldChange = new WorldChangeTriggerOptions();

        @Comment("Snapshot save policy for world saves")
        @Comment(lang = "zh-CN", value = "世界保存时的快照保存策略")
        WorldSaveTriggerOptions worldSave = new WorldSaveTriggerOptions();

        @Comment("Snapshot save policy for game mode changes")
        @Comment(lang = "zh-CN", value = "切换游戏模式时的快照保存策略")
        GameModeChangeTriggerOptions gameModeChange = new GameModeChangeTriggerOptions();

        @Comment("Snapshot save policy for player deaths")
        @Comment(lang = "zh-CN", value = "玩家死亡时的快照保存策略")
        DeathTriggerOptions death = new DeathTriggerOptions();
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class WorldChangeTriggerOptions {
        @Comment("Whether to create a data snapshot when a player changes worlds")
        @Comment(lang = "zh-CN", value = "是否在玩家切换世界时创建数据快照")
        boolean enabled = false;

        @Comment("Worlds that do not trigger a snapshot save when a player leaves them")
        @Comment(lang = "zh-CN", value = "玩家离开哪些世界时不触发快照保存")
        List<String> ignoredFromWorlds = List.of();

        @Comment("Worlds that do not trigger a snapshot save when a player enters them")
        @Comment(lang = "zh-CN", value = "玩家进入哪些世界时不触发快照保存")
        List<String> ignoredToWorlds = List.of();
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class WorldSaveTriggerOptions {
        @Comment("Whether to save snapshots for players in a world when that world is saved")
        @Comment(lang = "zh-CN", value = "是否在世界保存时为世界上的玩家进行快照保存")
        boolean enabled = true;
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class GameModeChangeTriggerOptions {
        @Comment("Whether to save a snapshot when a player changes game mode")
        @Comment(lang = "zh-CN", value = "是否在玩家切换游戏模式时进行快照保存")
        boolean enabled = false;

        @Comment({
                "Game modes that do not trigger a save when a player switches to them",
                "Available: SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR"
        })
        @Comment(lang = "zh-CN", value = {
                "玩家切换到哪些游戏模式时不触发保存",
                "可选值: SURVIVAL、CREATIVE、ADVENTURE、SPECTATOR"
        })
        List<GameMode> ignoredTargetModes = List.of();
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class DeathTriggerOptions {
        @Comment("Whether to record the player's pre-death data and create a data snapshot when they die")
        @Comment(lang = "zh-CN", value = "是否在玩家死亡时记录玩家死亡前的数据并创建一份数据快照")
        boolean saveBeforeDeath = false;

        @Comment("Whether to record the player's post-death data and create a data snapshot when they die")
        @Comment(lang = "zh-CN", value = "是否在玩家死亡时记录玩家死亡后的数据并创建一份数据快照")
        boolean saveAfterDeath = true;

        @Comment("Worlds where death snapshots are not created")
        @Comment(lang = "zh-CN", value = "在哪些世界中不创建死亡快照")
        List<String> ignoredWorlds = List.of();
    }

    public record SaveTriggers(@NotNull WorldChangeTrigger worldChange,
                               @NotNull WorldSaveTrigger worldSave,
                               @NotNull GameModeChangeTrigger gameModeChange,
                               @NotNull DeathTrigger deathTrigger) {

        @NotNull
        private static SaveTriggers of(@NotNull SaveTriggerOptions options) {
            return new SaveTriggers(
                    new WorldChangeTrigger(options.worldChange.enabled, Set.copyOf(options.worldChange.ignoredFromWorlds), Set.copyOf(options.worldChange.ignoredToWorlds)),
                    new WorldSaveTrigger(options.worldSave.enabled),
                    new GameModeChangeTrigger(options.gameModeChange.enabled, Set.copyOf(options.gameModeChange.ignoredTargetModes)),
                    new DeathTrigger(options.death.saveBeforeDeath, options.death.saveAfterDeath, Set.copyOf(options.death.ignoredWorlds))
            );
        }
    }

    public record WorldChangeTrigger(boolean enabled, @NotNull Set<String> ignoredFromWorlds, @NotNull Set<String> ignoredToWorlds) {
    }

    public record WorldSaveTrigger(boolean enabled) {
    }

    public record GameModeChangeTrigger(boolean enabled, @NotNull Set<GameMode> ignoredTargetModes) {
    }

    public record DeathTrigger(boolean saveBeforeDeath, boolean saveAfterDeath, @NotNull Set<String> ignoredWorlds) {
    }

    /**
     * 相对于 {@code custom_data} 根节点的不可变黑名单路径树.
     */
    public static final class PDCMergeBlacklist {
        private static final PDCMergeBlacklist TERMINAL = new PDCMergeBlacklist(Map.of());
        private static final PDCMergeBlacklist EMPTY = new PDCMergeBlacklist(Map.of());

        private final Map<String, PDCMergeBlacklist> children;

        private PDCMergeBlacklist(Map<String, PDCMergeBlacklist> children) {
            this.children = children;
        }

        /**
         * 将字符串和分段列表构建为不可变路径树, 前缀路径覆盖其全部后代.
         *
         * @param entries YAML 中的黑名单条目
         * @return 可供采集与合并直接查询的路径树
         */
        @NotNull
        public static PDCMergeBlacklist of(@NotNull List<?> entries) {
            Builder root = new Builder();
            for (Object entry : entries) {
                if (entry instanceof String segment) {
                    root.add(segment);
                    continue;
                }
                if (entry instanceof List<?> path && !path.isEmpty()) {
                    root.add(path);
                    continue;
                }
                throw invalidEntry(entry);
            }
            return root.freeze();
        }

        public boolean terminal() {
            return this == TERMINAL;
        }

        public boolean hasChildren() {
            return !this.children.isEmpty();
        }

        @Nullable
        public PDCMergeBlacklist child(@NotNull String segment) {
            return this.children.get(segment);
        }

        private static PDCMergeBlacklist empty() {
            return EMPTY;
        }

        private static IllegalArgumentException invalidEntry(Object entry) {
            return new IllegalArgumentException("PDC merge blacklist entries must be a path string or a non-empty list of path strings: " + entry);
        }

        private static final class Builder {
            private boolean terminal;
            private Map<String, Builder> children;

            private void add(String segment) {
                if (this.children == null) {
                    this.children = new HashMap<>();
                }
                this.children.computeIfAbsent(segment, ignored -> new Builder()).finish();
            }

            private void add(List<?> path) {
                Builder current = this;
                for (Object element : path) {
                    if (!(element instanceof String segment)) {
                        throw invalidEntry(path);
                    }
                    if (current.terminal) return;
                    if (current.children == null) {
                        current.children = new HashMap<>();
                    }
                    current = current.children.computeIfAbsent(segment, ignored -> new Builder());
                }
                current.finish();
            }

            private void finish() {
                this.terminal = true;
                this.children = null;
            }

            private PDCMergeBlacklist freeze() {
                if (this.terminal) return TERMINAL;
                if (this.children == null) return EMPTY;
                Map<String, PDCMergeBlacklist> frozenChildren = new HashMap<>(this.children.size());
                for (Map.Entry<String, Builder> entry : this.children.entrySet()) {
                    frozenChildren.put(entry.getKey(), entry.getValue().freeze());
                }
                return new PDCMergeBlacklist(Map.copyOf(frozenChildren));
            }
        }
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class LoggingOptions {
        @Comment({
                "Records detailed daily data logs for troubleshooting, including logs not shown in the console",
                "Logs are written to a <date>.log file, stored in plugins/sparrow-sync/logs by default",
                "Disabling this is strongly discouraged because it makes plugin errors difficult to investigate; changes require a server restart"
        })
        @Comment(lang = "zh-CN", value = {
                "插件会详细记录每天的数据日志, 以方便出现异常时进行排查, 这其中也包括未显示在控制台中的日志",
                "日志将会被写入一个 <date>.log 文件, 并默认存储在 plugins/sparrow-sync/logs 文件夹中",
                "非常不推荐关闭, 关闭后若插件发生错误则难以进行排查, 修改需要重启服务器后生效",
        })
        boolean localFile = true;

        @Comment("Log directory, accepts absolute or relative paths; relative paths are resolved against the plugin data folder")
        @Comment(lang = "zh-CN", value = "日志保存目录, 你可以填写绝对路径或相对路径, 相对路径以插件数据目录为基准")
        String directory = "logs";

        @Comment("Timestamp format for each log line, using a Java DateTimeFormatter pattern")
        @Comment(lang = "zh-CN", value = "每行日志的时间格式, 使用 Java DateTimeFormatter 格式")
        String timeFormat = "HH:mm:ss.SSS";

        @Comment({
                "Date format for log file names, using a Java DateTimeFormatter pattern",
                "This determines when a new file is created, e.g. yyyy-MM rotates monthly; closed files are compressed as <date>-<index>.log.gz"
        })
        @Comment(lang = "zh-CN", value = {
                "日志文件名的日期格式, 使用 Java DateTimeFormatter 格式",
                "此格式决定何时创建新文件, 例如 yyyy-MM 表示按月切换, 关闭的文件会压缩为 <date>-<index>.log.gz"
        })
        String fileDateFormat = "yyyy-MM-dd";

        @Comment({
                "Selects which snapshot save causes are reported to the console after a successful save",
                "When local-file is enabled, all successful saves are still written to the log file"
        })
        @Comment(lang = "zh-CN", value = {
                "选择哪些原因触发的快照在保存成功后输出到控制台",
                "启用 local-file 时, 所有保存成功的记录仍会写入日志文件"
        })
        ConsoleSaveCauses consoleSaveCauses = new ConsoleSaveCauses();
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class ConsoleSaveCauses {
        boolean disconnect = true;
        boolean worldChange = false;
        boolean gameModeChange = false;
        boolean preDeath = false;
        boolean death = false;
        boolean shutdown = false;
        boolean worldSave = false;
        boolean command = true;
        boolean restore = true;
        boolean edit = true;
        boolean api = false;
        boolean unknown = false;

        boolean enabled(@NotNull SaveCause cause) {
            return switch (cause) {
                case DISCONNECT -> this.disconnect;
                case WORLD_CHANGE -> this.worldChange;
                case GAME_MODE_CHANGE -> this.gameModeChange;
                case PRE_DEATH -> this.preDeath;
                case DEATH -> this.death;
                case SHUTDOWN -> this.shutdown;
                case WORLD_SAVE -> this.worldSave;
                case COMMAND -> this.command;
                case RESTORE -> this.restore;
                case EDIT -> this.edit;
                case API -> this.api;
                case MIGRATION -> false; // 迁移由批次结果汇报, 单份快照不触发控制台保存消息.
                case UNKNOWN -> this.unknown;
            };
        }
    }

    // 读取一律经这里穿透到当前那份配置, 方法名以 $ 还原配置文件里的层级.
    // 同一次触发要读取的相关选项组合成一个不可变值, 调用方每次重新取得当前快照

    public static boolean checkUpdate() {
        return config.updateChecker;
    }

    public static boolean metrics() {
        return config.metrics;
    }

    @Nullable
    public static Locale forcedLocale() {
        return TranslationManager.parseLocale(config.forcedLocale);
    }

    public static boolean logging$consoleSave(@NotNull SaveCause cause) {
        return config.logging.consoleSaveCauses.enabled(cause);
    }

    public static boolean logging$localFile() {
        return config.logging.localFile;
    }

    public static String logging$directory() {
        return config.logging.directory;
    }

    public static String logging$timeFormat() {
        return config.logging.timeFormat;
    }

    public static String logging$fileDateFormat() {
        return config.logging.fileDateFormat;
    }

    public static int synchronization$workerThreads() {
        return config.synchronization.workerThreads;
    }

    public static int synchronization$shutdownTimeoutSeconds() {
        return config.synchronization.shutdownTimeoutSeconds;
    }

    public static int synchronization$loginTimeoutSeconds() {
        return config.synchronization.loginTimeoutSeconds;
    }

    @NotNull
    public static NativeAsyncApplyOptions synchronization$nativeAsyncApply() {
        return config.synchronization.nativeAsyncApply;
    }

    @NotNull
    public static MapOptions synchronization$map() {
        return config.synchronization.map;
    }

    @NotNull
    public static AdvancementsOptions synchronization$advancements() {
        return config.synchronization.advancements;
    }

    @NotNull
    public static SaveTriggers synchronization$saveTriggers() {
        return config.synchronization.compiledSaveTriggers;
    }

    public static int synchronization$maxSaveRetries() {
        return config.synchronization.maxSaveRetries;
    }

    public static int synchronization$maxSnapshots() {
        return config.synchronization.maxSnapshots;
    }

    public static CompressorRegistry synchronization$compression() {
        return config.synchronization.compression;
    }

    @NotNull
    public static DataTypes synchronization$dataTypes() {
        return config.synchronization.dataTypes;
    }

    @NotNull
    public static OnlineRestoreOptions synchronization$onlineRestore() {
        return config.synchronization.onlineRestore;
    }


    @NotNull
    public static AttributeOptions synchronization$attributes() {
        return config.synchronization.attributes;
    }

    @NotNull
    public static PDCMergeBlacklist synchronization$pdcMergeNamespaces() {
        return config.synchronization.pdcMergeBlacklist;
    }

    public static StorageType database$type() {
        return config.database.type;
    }

    @NotNull
    public static MongoOptions database$mongodb() {
        return config.database.mongodb;
    }

    @NotNull
    public static MysqlOptions database$mysql() {
        return config.database.mysql;
    }

    @NotNull
    public static PostgresOptions database$postgresql() {
        return config.database.postgresql;
    }

    @NotNull
    public static RedisOptions redis() {
        return config.redis;
    }
}

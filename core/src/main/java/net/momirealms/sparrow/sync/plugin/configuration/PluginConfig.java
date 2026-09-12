package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
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
import net.momirealms.sparrow.yaml.serializer.auto.annotation.YamlProperty;
import net.momirealms.sparrow.yaml.upgrade.YamlUpgradePipeline;
import net.momirealms.sparrow.yaml.upgrade.version.FieldVersionExtractor;
import org.bukkit.GameMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
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
                .versionExtractor(new FieldVersionExtractor("___version___"))
                .addPatch("14"::equals, patch -> patch.patch((defaults, local, context) -> {
                    Route type = Route.from("synchronization", "map", "type");
                    Route enabled = Route.from("synchronization", "map", "enabled");
                    String previous = local.getString(type);
                    if ("NONE".equals(previous) || "HIDE".equals(previous)) {
                        if (local.getNodeOrNull(enabled) == null) {
                            local.setAndGet(enabled, "HIDE".equals(previous));
                        }
                        local.setAndGet(Route.from("synchronization", "map", "synchronization_mode"), "HIDE");
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
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + CONFIG_FILE, e);
        }
    }

    // 配置文件
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class ConfigDefinition {
        @YamlProperty("___version___")
        @Comment("Configuration version. Do not edit.")
        @Comment(lang = "zh-CN", value = "配置文件版本, 请勿修改.")
        String version = DependencyVersions.CONFIG_VERSION;

        @Comment("Send plugin usage statistics to bStats.")
        @Comment(lang = "zh-CN", value = "是否向 bStats 提交插件使用统计.")
        boolean metrics = true;

        @Comment("Automatically check for new versions.")
        @Comment(lang = "zh-CN", value = "是否自动检查新版本.")
        boolean updateChecker = true;

        @Comment({
                "Console message language, such as zh_CN or en_US.",
                "Leave blank to use the system language. English is used when no matching translation is available."
        })
        @Comment(lang = "zh-CN", value = {
                "控制台消息的语言, 例如 zh_CN 或 en_US.",
                "留空则跟随系统语言, 没有对应翻译时使用英文."
        })
        String forcedLocale = "";

        @BlankLineBefore
        @Comment("Redis connection settings for data synchronization between servers.")
        @Comment(lang = "zh-CN", value = "Redis 连接设置, 用于服务器之间的数据同步.")
        RedisOptions redis = new RedisOptions();

        @BlankLineBefore
        @Comment("Database settings for player data snapshots.")
        @Comment(lang = "zh-CN", value = "玩家数据快照的数据库设置.")
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
        @Comment("Database used to store player snapshots. Choose MONGODB, MYSQL or POSTGRESQL.")
        @Comment(lang = "zh-CN", value = "保存玩家数据快照的数据库, 可选 MONGODB、MYSQL、POSTGRESQL.")
        StorageType type = StorageType.MONGODB;

        @Comment("MySQL database settings")
        @Comment(lang = "zh-CN", value = "MYSQL 数据库设置")
        MysqlOptions mysql = new MysqlOptions();

        @Comment("PostgreSQL database settings")
        @Comment(lang = "zh-CN", value = "POSTGRESQL 数据库设置")
        PostgresOptions postgresql = new PostgresOptions();

        @Comment("MongoDB database settings")
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
        @Comment("Table name prefix, up to 40 characters. Use only lowercase English letters, digits and underscores.")
        @Comment(lang = "zh-CN", value = "数据表名前缀, 最多 40 个字符, 只能使用小写英文字母、数字和下划线.")
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
                "Number of threads for player data tasks, from 1 to 64.",
                "Use this server's online player count as a starting point, then adjust as needed.",
                "Suggested values are 2 for up to 150 players, 4 for up to 350, and 8 for up to 700."
        })
        @Comment(lang = "zh-CN", value = {
                "处理玩家数据的线程数, 可填 1-64.",
                "可参考本服的在线人数设置, 再按实际运行情况调整.",
                "150 人以内建议设为 2, 350 人以内设为 4, 700 人以内设为 8."
        })
        int workerThreads = 4;

        @Comment({
                "How long to wait, in seconds, when shutdown saves stop making progress. Values <= 0 skip the wait.",
                "On timeout or interruption, the plugin tries to save any remaining complete snapshots locally and upload them to the database on the next startup."
        })
        @Comment(lang = "zh-CN", value = {
                "关服保存长时间没有进展时, 最多再等待多少秒, <= 0 表示不等待.",
                "超时或中断后结束等待, 尚未保存的完整快照会尝试暂存到本地, 下次服务器启动时重新上传到数据库."
        })
        int shutdownTimeoutSeconds = 30;

        @Comment("Maximum number of unpinned snapshots per player. The oldest are removed when this limit is exceeded. Pinned snapshots are kept.")
        @Comment(lang = "zh-CN", value = "每名玩家最多保留多少份未固定的快照, 超出后会清理最旧的快照, 被固定的快照不受影响.")
        int maxSnapshots = 32;

        @Comment({
                "Maximum retries after a database save fails. Set to -1 to keep retrying through temporary network or database outages.",
                "When retries run out, the plugin logs a warning and stores the snapshot locally for upload on the next startup.",
                "Invalid data is not retried. The plugin tries to save it locally and alerts an administrator."
        })
        @Comment(lang = "zh-CN", value = {
                "数据库保存失败后的最多重试次数, -1 表示一直重试, 适合应对临时断网或数据库宕机.",
                "重试用尽后会告警并将快照暂存到本地, 下次启动时再存入数据库.",
                "数据本身有问题时不会重试, 会尽量暂存到本地并提醒管理员处理."
        })
        int maxSaveRetries = -1;

        @Comment("Maximum time, in seconds, to wait for player data during login.")
        @Comment(lang = "zh-CN", value = "玩家登录时最多等待多少秒来准备数据.")
        int loginTimeoutSeconds = 60;

        @Comment({
                "Compression for new snapshots. Choose ZSTD, DEFLATE or NONE.",
                "  ZSTD    - fast saves and loads with good compression. Recommended.",
                "  DEFLATE - built into Java and needs no native library. Use if ZSTD fails to load.",
                "  NONE    - no compression. Larger snapshots take longer to send to the database."
        })
        @Comment(lang = "zh-CN", value = {
                "新快照的压缩方式, 可选值: ZSTD、DEFLATE、NONE",
                "  ZSTD    - 保存和加载速度快, 压缩率高, 推荐使用",
                "  DEFLATE - JDK 自带压缩算法, 无需原生库, Zstd 加载失败时可使用",
                "  NONE    - 不压缩, 快照越大, 传输到数据库所需时间也越长"
        })
        CompressorRegistry compression = CompressorRegistry.ZSTD;

        @BlankLineBefore
        @Comment("Choose which player data to synchronize. Restart the server after changing this.")
        @Comment(lang = "zh-CN", value = "选择需要同步的玩家数据, 修改后重启生效.")
        DataTypes dataTypes = new DataTypes();

        @BlankLineBefore
        @Comment({
                "Data types to discard if this server does not recognize them. Unlisted types are kept.",
                "Use namespace:name, such as sparrow_sync:location. Restart the server after changing this."
        })
        @Comment(lang = "zh-CN", value = {
                "本服无法识别的数据类型中, 哪些需要丢弃, 未列出的会保留.",
                "按 namespace:name 填写, 例如 sparrow_sync:location, 修改后重启生效."
        })
        List<DataKey> discardUnknownData = List.of(
                DataKey.sparrow("attributes"),
                DataKey.sparrow("enchantment_seed"),
                DataKey.sparrow("experience"),
                DataKey.sparrow("flight_status"),
                DataKey.sparrow("game_mode"),
                DataKey.sparrow("health"),
                DataKey.sparrow("hunger"),
                DataKey.sparrow("location")
        );

        @BlankLineBefore
        @Comment({
                "Speed up data loading during login and reduce lag from synchronization. Recommended.",
                "Try disabling this if it causes login errors or compatibility problems."
        })
        @Comment(lang = "zh-CN", value = {
                "加快玩家登录时的数据加载, 减少同步造成的卡顿, 推荐开启.",
                "开启后若出现登录异常或兼容问题, 可以尝试关闭."
        })
        NativeAsyncApplyOptions nativeAsyncApply = new NativeAsyncApplyOptions();

        @BlankLineBefore
        @Comment("Cross-server snapshot cache settings")
        @Comment(lang = "zh-CN", value = "跨服快照缓存设置")
        SnapshotCacheOptions snapshotCache = new SnapshotCacheOptions();

        @BlankLineBefore
        @Comment({
                "Skip these data types when restoring a snapshot to an online player. Login synchronization is unaffected. Leave the list empty to restore all types.",
                "Use the same format as discard-unknown-data, such as health or sparrow_sync:health."
        })
        @Comment(lang = "zh-CN", value = {
                "在线恢复快照时跳过这些数据, 不影响玩家登录时的同步, 留空则全部恢复.",
                "填写方式与 discard-unknown-data 相同, 例如 health 或 sparrow_sync:health."
        })
        List<DataKey> skipOnlineRestoreData = List.of(DataKey.sparrow("health"), DataKey.sparrow("location"));

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
                "Player custom data (PDC) to exclude from synchronization. Follow the examples below.",
                "pdc-merge-namespaces:",
                "  - \"sparrow-sync-ignore\" # Exclude the entire entry",
                "  - [\"sparrow-sync\", \"ignore\"] # Exclude the ignore entry under sparrow-sync"
        })
        @Comment(lang = "zh-CN", value = {
                "不参与同步的玩家自定义数据 (PDC), 可按下面的例子填写.",
                "pdc-merge-namespaces:",
                "  - \"sparrow-sync-ignore\" # 忽略整个数据项",
                "  - [\"sparrow-sync\", \"ignore\"] # 忽略 sparrow-sync 下的 ignore 数据项"
        })
        List<Object> pdcMergeNamespaces = List.of();

        @BlankLineBefore
        @Comment("When to save snapshots automatically.")
        @Comment(lang = "zh-CN", value = "自动保存快照的时机.")
        SaveTriggerOptions saveTriggers = new SaveTriggerOptions();

        @YamlIgnore
        PDCMergeBlacklist pdcMergeBlacklist = PDCMergeBlacklist.empty();

        @YamlIgnore
        SaveTriggers compiledSaveTriggers = SaveTriggers.of(this.saveTriggers);
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class SnapshotCacheOptions {
        @Comment("Cache snapshots saved on logout or shutdown in Redis to speed up the player's next login on another server.")
        @Comment(lang = "zh-CN", value = {
                "将玩家退出或关服时已保存的快照缓存到 Redis, 加快下次跨服登录."
        })
        boolean enabled = true;

        @Comment("How long to keep cached snapshots, in seconds. Allow enough time for players to leave one server and join the next.")
        @Comment(lang = "zh-CN", value = "快照缓存保留多少秒, 应留够玩家从退出一台服务器到进入下一台的时间.")
        int ttlSeconds = 15;

        public boolean enabled() {
            return this.enabled;
        }

        public int ttlSeconds() {
            return this.ttlSeconds;
        }
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class MapOptions {
        @Comment({
                "Enable cross-server map handling. When disabled, the plugin leaves map items and map data alone. Restart the server after changing this.",
                "Enabling this modifies map items. Uninstalling the plugin will not fully restore them.",
                "Maps transferred through cross-server auction houses or plugin backpacks may display incorrectly if they bypass this plugin's synchronization."
        })
        @Comment(lang = "zh-CN", value = {
                "是否处理跨服地图, 关闭后插件不处理地图物品和地图数据, 修改后重启生效.",
                "开启后插件会修改地图物品, 卸载插件后无法完整还原.",
                "通过跨服交易行、插件背包等方式转移的地图因为未经过本插件同步, 可能会显示异常."
        })
        boolean enabled = false;

        @Comment({
                "How to handle cross-server maps. Choose HIDE or SYNC.",
                "HIDE makes maps unusable on other servers so they cannot accidentally show another server's map content.",
                "SYNC synchronizes map content so players can still view it after switching servers."
        })
        @Comment(lang = "zh-CN", value = {
                "跨服地图的处理方式, 可选 HIDE 或 SYNC.",
                "HIDE 让地图在其他服务器上无法使用, 防止它意外显示其他服务器的地图内容.",
                "SYNC 同步地图内容, 让玩家跨服后仍能查看.",
        })
        @YamlProperty("synchronization_mode")
        MapType synchronization_mode = MapType.SYNC;

        @Comment({
                "Identifies which server a map comes from. Keeping the default is recommended. Restart the server after changing this.",
                "${server-id} is the server ID in server.yml. ${world-uuid} is the UUID of the map's overworld.",
                "Changing this makes existing maps count as maps from another server."
        })
        @Comment(lang = "zh-CN", value = {
                "本服务器的地图标志, 用来区分地图来自哪台服务器, 修改后重启生效, 建议保留默认值.",
                "${server-id} 对应 server.yml 中的服务器 ID, ${world-uuid} 对应地图所属主世界的 UUID.",
                "修改后, 原有地图会被当作其他服务器的地图."
        })
        String mapOwnerId = "${server-id}-${world-uuid}";

        @Comment("Which operations players can perform on cross-server maps. Reload to apply changes.")
        @Comment(lang = "zh-CN", value = "玩家对跨服地图可以进行哪些操作, 重载生效.")
        MapPlayerOperationOptions playerOperation = new MapPlayerOperationOptions();

        public boolean enabled() {
            return this.enabled;
        }

        @NotNull
        public MapType synchronization_mode() {
            return this.synchronization_mode;
        }

        @NotNull
        public String mapOwnerId() {
            return this.mapOwnerId;
        }

        @NotNull
        public MapPlayerOperationOptions playerOperation() {
            return this.playerOperation;
        }

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
    public static class MapPlayerOperationOptions {
        @Comment("Allow adding or removing banner markers on cross-server maps. Reload to apply changes.")
        @Comment(lang = "zh-CN", value = "是否允许在跨服地图上添加或移除旗帜标记, 重载生效.")
        boolean allowBannerModification = false;

        @Comment({
                "Allow locking cross-server maps. Reload to apply changes.",
                "Locking creates a new map on this server. The new map may not synchronize correctly across servers."
        })
        @Comment(lang = "zh-CN", value = {
                "是否允许锁定跨服地图, 重载生效.",
                "锁定后会生成本服的新地图, 目前不保证新地图能正常跨服同步."
        })
        boolean allowLock = false;

        @Comment({
                "Allow scaling cross-server maps. Reload to apply changes.",
                "Scaling creates a new map on this server. The new map may not synchronize or display correctly across servers."
        })
        @Comment(lang = "zh-CN", value = {
                "是否允许缩放跨服地图, 重载生效.",
                "缩放后会生成本服的新地图, 目前不保证新地图能正常跨服同步和显示."
        })
        boolean allowScale = false;

        @Comment({
                "Allow copying cross-server maps using a cartography table, crafting table or crafter. Reload to apply changes.",
                "This does not block copies made in creative mode or by other plugins."
        })
        @Comment(lang = "zh-CN", value = {
                "是否允许用制图台、工作台或合成器复制跨服地图, 重载生效.",
                "不阻止创造模式和其他插件复制地图."
        })
        boolean allowCopy = true;

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
        @Comment("Synchronize player balances. Requires Vault and an economy plugin.")
        @Comment(lang = "zh-CN", value = "是否同步玩家余额, 需要安装 Vault 和经济插件.")
        boolean vault = false;

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

        public boolean vault() {
            return this.vault;
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
                "Speed up saving advancement progress and reduce lag from synchronization.",
                "Try disabling this if it causes advancement sync errors or compatibility problems."
        })
        @Comment(lang = "zh-CN", value = {
                "加快成就进度的保存, 减少同步造成的卡顿.",
                "开启后若出现成就同步异常或兼容问题, 可以尝试关闭."
        })
        boolean injectProgressChanged = true;

        @Comment({
                "Keep advancements and their progress even if this server does not have them. Disabling this discards that progress.",
                "If all servers have the same advancements, you can disable this to speed up synchronization."
        })
        @Comment(lang = "zh-CN", value = {
                "是否保留本服没有的成就及其完成进度, 关闭后这部分进度会丢失.",
                "如果各服的成就完全一致, 可以关闭以加快同步."
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
                "Speed up saving player attributes and reduce lag from synchronization.",
                "Try disabling this if it causes attribute sync errors or compatibility problems."
        })
        @Comment(lang = "zh-CN", value = {
                "加快玩家属性的保存, 减少同步造成的卡顿.",
                "开启后若出现属性同步异常或兼容问题, 可以尝试关闭."
        })
        boolean injectOnDirtyConsumer = true;

        @Comment({
                "Player attributes to synchronize. Use * to match multiple names.",
                "The default list includes attribute names used by different Minecraft versions."
        })
        @Comment(lang = "zh-CN", value = {
                "需要同步的玩家属性, 支持用 * 匹配多个名称.",
                "默认列表包含不同 Minecraft 版本使用的属性名称."
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

        @Comment("Attribute modifiers to exclude from synchronization. Each server keeps its own values. Use * to match multiple names.")
        @Comment(lang = "zh-CN", value = "不同步的属性加成, 各服保留自己的数值, 支持用 * 匹配多个名称.")
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

        public boolean injectOnDirtyConsumer() {
            return this.injectOnDirtyConsumer;
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
        @Comment("Automatic save settings for world changes.")
        @Comment(lang = "zh-CN", value = "切换世界时的自动保存设置.")
        WorldChangeTriggerOptions worldChange = new WorldChangeTriggerOptions();

        @Comment("Automatic save settings for world saves.")
        @Comment(lang = "zh-CN", value = "世界保存时的自动保存设置.")
        WorldSaveTriggerOptions worldSave = new WorldSaveTriggerOptions();

        @Comment("Automatic save settings for game mode changes.")
        @Comment(lang = "zh-CN", value = "切换游戏模式时的自动保存设置.")
        GameModeChangeTriggerOptions gameModeChange = new GameModeChangeTriggerOptions();

        @Comment("Automatic save settings for player deaths.")
        @Comment(lang = "zh-CN", value = "玩家死亡时的自动保存设置.")
        DeathTriggerOptions death = new DeathTriggerOptions();
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class WorldChangeTriggerOptions {
        @Comment("Save a snapshot when a player changes worlds.")
        @Comment(lang = "zh-CN", value = "是否在玩家切换世界时保存快照.")
        boolean enabled = false;

        @Comment("Skip saving a snapshot when a player leaves these worlds.")
        @Comment(lang = "zh-CN", value = "玩家离开这些世界时不保存快照.")
        List<String> ignoredFromWorlds = List.of();

        @Comment("Skip saving a snapshot when a player enters these worlds.")
        @Comment(lang = "zh-CN", value = "玩家进入这些世界时不保存快照.")
        List<String> ignoredToWorlds = List.of();
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class WorldSaveTriggerOptions {
        @Comment("Save snapshots for players in a world when that world is saved.")
        @Comment(lang = "zh-CN", value = "是否在世界保存时, 一并保存该世界内玩家的快照.")
        boolean enabled = true;
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class GameModeChangeTriggerOptions {
        @Comment("Save a snapshot when a player changes game mode.")
        @Comment(lang = "zh-CN", value = "是否在玩家切换游戏模式时保存快照.")
        boolean enabled = false;

        @Comment({
                "Skip saving a snapshot when a player switches to these game modes.",
                "Choose from SURVIVAL, CREATIVE, ADVENTURE and SPECTATOR."
        })
        @Comment(lang = "zh-CN", value = {
                "玩家切换到这些游戏模式时不保存快照.",
                "可填 SURVIVAL、CREATIVE、ADVENTURE、SPECTATOR."
        })
        List<GameMode> ignoredTargetModes = List.of();
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class DeathTriggerOptions {
        @Comment("Save a snapshot of the player before death.")
        @Comment(lang = "zh-CN", value = "是否保存玩家死亡前的快照.")
        boolean saveBeforeDeath = false;

        @Comment("Save a snapshot of the player after death.")
        @Comment(lang = "zh-CN", value = "是否保存玩家死亡后的快照.")
        boolean saveAfterDeath = true;

        @Comment("Skip death snapshots in these worlds.")
        @Comment(lang = "zh-CN", value = "玩家在这些世界死亡时不保存快照.")
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
                "Save detailed logs to files, including entries not shown in the console. Restart the server after changing this.",
                "Logs are stored in plugins/sparrow-sync/logs by default. Keep this enabled to help troubleshoot problems."
        })
        @Comment(lang = "zh-CN", value = {
                "是否将详细日志保存到文件, 包括控制台中没有显示的记录, 修改后重启生效.",
                "默认保存在 plugins/sparrow-sync/logs, 建议保持开启, 出问题时方便排查."
        })
        boolean localFile = true;

        @Comment("Log directory. Relative paths start from the plugin's data folder. You can also use an absolute path.")
        @Comment(lang = "zh-CN", value = "日志保存目录, 相对路径从插件的数据目录算起, 也可以填写完整路径.")
        String directory = "logs";

        @Comment("How many days to keep local logs. Expired .log and .log.gz files are deleted at startup. Values <= 0 keep all logs.")
        @Comment(lang = "zh-CN", value = "本地日志保留天数, 服务器在启动时会删除过期的 .log 和 .log.gz 文件, <= 0 表示不清理.")
        int retentionDays = 0;

        @Comment("Time format in log entries. HH:mm:ss shows hours, minutes and seconds; HH:mm:ss.SSS also includes milliseconds.")
        @Comment(lang = "zh-CN", value = "日志中的时间格式, 例如 HH:mm:ss 显示时分秒, HH:mm:ss.SSS 再加上毫秒.")
        String timeFormat = "HH:mm:ss.SSS";

        @Comment({
                "Date format for log file names. yyyy-MM-dd creates daily files; yyyy-MM creates monthly files.",
                "Old logs are compressed as <date>-<index>.log.gz."
        })
        @Comment(lang = "zh-CN", value = {
                "日志文件名的日期格式, yyyy-MM-dd 按天分文件, yyyy-MM 按月分文件.",
                "旧日志会压缩保存为 <date>-<index>.log.gz."
        })
        String fileDateFormat = "yyyy-MM-dd";

        @Comment({
                "Choose which types of successful saves to report in the console.",
                "When file logging is enabled, every successful save is recorded in the log file."
        })
        @Comment(lang = "zh-CN", value = {
                "哪些情况下保存成功后要在控制台提示.",
                "开启文件日志后, 每次保存成功都会记入日志文件."
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

    public static int logging$retentionDays() {
        return config.logging.retentionDays;
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
    public static SnapshotCacheOptions synchronization$snapshotCache() {
        return config.synchronization.snapshotCache;
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
    public static List<DataKey> synchronization$discardUnknownData() {
        return config.synchronization.discardUnknownData;
    }

    @NotNull
    public static List<DataKey> synchronization$skipOnlineRestoreData() {
        return config.synchronization.skipOnlineRestoreData;
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

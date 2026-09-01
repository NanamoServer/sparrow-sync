package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.storage.StorageType;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.mapper.YamlMapper;
import net.momirealms.sparrow.yaml.mapper.YamlMapperFactory;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.BlankLineBefore;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.Comment;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.Configuration;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.YamlIgnore;
import net.momirealms.sparrow.yaml.upgrade.YamlUpgradePipeline;
import net.momirealms.sparrow.yaml.upgrade.version.FieldVersionExtractor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PluginConfig {
    private static final String CONFIG_FILE = "config.yml";
    private static volatile ConfigDefinition config;

    private final Plugin plugin;
    private final Path configFilePath;
    private final YamlMapper<ConfigDefinition> configMapper;

    PluginConfig(Plugin plugin, SparrowYaml sparrowYaml) {
        this.plugin = plugin;
        this.configFilePath = plugin.dataFolderPath().resolve(CONFIG_FILE);
        YamlUpgradePipeline upgradePipeline = YamlUpgradePipeline.builder()
                .versionExtractor(new FieldVersionExtractor("config-version"))
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
            loadedConfig.synchronization.pdcMergeBlacklist = PDCMergeBlacklist.of(loadedConfig.synchronization.pdcMergeNamespaces);
            config = loadedConfig;
        } catch (Exception e) {
            this.plugin.logger().error("Failed to load " + CONFIG_FILE, e);
        }
    }

    // 配置文件
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class ConfigDefinition {
        @Comment("Do not modify this value")
        String configVersion = DependencyVersions.CONFIG_VERSION;

        @Comment("Enables or disables metrics collection via BStats")
        boolean metrics = true;

        @Comment("Enables automatic update checks")
        boolean updateChecker = true;

        @Comment({
                "Language of console messages, e.g. zh_cn",
                "Leave empty to follow the system locale, any locale without a translation file falls back to en"
        })
        String forcedLocale = "";

        @BlankLineBefore
        @Comment({
                "Identifies the cluster this server belongs to",
                "Every server sharing the same database and Redis must use the same value,",
                "it prefixes every Redis key so two clusters can share one Redis without interfering"
        })
        String clusterId = "main";

        @BlankLineBefore
        @Comment("Synchronization settings")
        SynchronizationOptions synchronization = new SynchronizationOptions();

        @BlankLineBefore
        @Comment("Redis, backs the cross server session lock and messaging")
        RedisOptions redis = new RedisOptions();

        @BlankLineBefore
        @Comment("Where player snapshots are persisted")
        DatabaseOptions database = new DatabaseOptions();

        @BlankLineBefore
        @Comment("Local activity log")
        LoggingOptions logging = new LoggingOptions();

        @BlankLineBefore
        @Comment("Debug")
        boolean debug = false;
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class LoggingOptions {
        @Comment({
                "Writes every plugin log line, including those hidden from the console,",
                "to a <date>.log file per day under the directory below",
                "Grep a player uuid there to get their full join/apply/save/quit timeline",
                "Read once at startup, reloading does not start or stop the writer"
        })
        boolean localFile = true;

        @Comment({
                "Where the log files go, resolved against the plugin data folder unless absolute"
        })
        String directory = "logs";

        @Comment({
                "Timestamp format of each log line, a java DateTimeFormatter pattern"
        })
        String timeFormat = "HH:mm:ss.SSS";

        @Comment({
                "Date format of the daily log file names, a java DateTimeFormatter pattern",
                "It decides when a new file starts, e.g. yyyy-MM would roll monthly instead of daily"
        })
        String fileDateFormat = "yyyy-MM-dd";
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class SynchronizationOptions {
        @Comment({
                "Number of worker threads handling per-player tasks, rounded up to a power of two",
                "Tasks of one player always run on the same worker in submission order"
        })
        int workerThreads = 4;

        @Comment({
                "How long to wait for pending saves to reach the storage on shutdown",
                "Draining 1500 players at 4 workers takes about 8s when a storage write costs 20ms,",
                "and about 19s at 50ms, so 60 seconds leaves room for a remote or busy database",
                "A supervisor that stops the server sooner (Docker allows 10s by default)",
                "cuts the drain short no matter what is set here"
        })
        int shutdownTimeoutSeconds = 60;

        @Comment({
                "How many snapshots to keep per player, oldest unpinned ones are rotated out",
                "Pinned snapshots never count against this limit and are never rotated"
        })
        int maxSnapshots = 32;

        @Comment({
                "How many times a snapshot that could not reach the database is put back in the queue",
                "-1 keeps retrying until the database comes back, which is what you want on an outage",
                "A snapshot that runs out of attempts is written to disk instead, never dropped",
                "Snapshots that fail for reasons retrying cannot fix (too large, encoding errors) skip this entirely"
        })
        int maxSaveRetries = -1;

        @Comment({
                "How long the login gate waits for player data before giving up, in seconds",
                "A player whose data is not ready in time is disconnected, never let in unsynced"
        })
        int loginTimeoutSeconds = 15;

        @Comment({
                "How newly written snapshots are compressed, existing data stays readable whatever is set here",
                "Available: ZSTD, DEFLATE, NONE",
                "  ZSTD    - the fastest saves and loads at the best ratio (recommended)",
                "  DEFLATE - the JDK codec, needs no native library, use it if Zstd fails to load here",
                "  NONE    - plain bytes, note that a bigger snapshot also takes longer to reach the database"
        })
        CompressorRegistry compression = CompressorRegistry.ZSTD;

        @Comment({
                "Persistent data (PDC) merge blacklist; every entry is a path relative to custom_data",
                "Blacklisted paths are neither captured nor merged",
                "Use \"sparrow-sync-ignore\" for custom_data -> sparrow-sync-ignore",
                "Use [\"sparrow-sync\", \"ignore\"] for custom_data -> sparrow-sync -> ignore"
        })
        List<Object> pdcMergeNamespaces = List.of(
                "sparrow-sync-ignore",
                List.of("sparrow-sync", "ignore")
        );

        @YamlIgnore
        PDCMergeBlacklist pdcMergeBlacklist = PDCMergeBlacklist.empty();
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
         * 将字符串和分段列表编译为不可变路径树, 前缀路径覆盖其全部后代.
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
    public static class RedisOptions {
        @Comment("Connection url, credentials go below instead of into the url")
        String url = "redis://localhost:6379";

        @Comment("Username, leave empty on a server without ACL users")
        String username = "";

        @Comment("Password, leave empty when the server requires none")
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
        @Comment({
                "Which backend keeps player snapshots, only the matching section below is read",
                "Available: MONGODB, MYSQL"
        })
        StorageType type = StorageType.MONGODB;

        @BlankLineBefore
        @Comment("Read when type is MONGODB")
        MongoOptions mongodb = new MongoOptions();

        @BlankLineBefore
        @Comment("Read when type is MYSQL")
        MysqlOptions mysql = new MysqlOptions();
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class MongoOptions {
        @Comment("Connection url, credentials go below instead of into the url")
        String url = "mongodb://localhost:27017";

        @Comment("Database name")
        String database = "sparrow_sync";

        @Comment("Username, leave empty to connect without authentication")
        String username = "";

        @Comment("Password")
        String password = "";

        @Comment("Authentication source database")
        String authSource = "admin";

        @Comment("Prefix of every collection created by this plugin")
        String collectionPrefix = "sparrow_";

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
        @Comment("Connection url, credentials go below instead of into the url")
        String url = "jdbc:mysql://localhost:3306/sparrow_sync";

        @Comment("Username")
        String username = "root";

        @Comment("Password")
        String password = "";

        @Comment("Prefix of every table created by this plugin")
        String tablePrefix = "sparrow_";

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



    // 读取一律经这里穿透到当前那份配置, 方法名以 $ 还原配置文件里的层级.
    // 不暴露任何配置段对象: 重载是换上新的一份而不是就地改写, 谁持有段对象谁就停在旧值上

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

    public static boolean debug() {
        return config.debug;
    }

    @NotNull
    public static String clusterId() {
        return config.clusterId;
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
    public static RedisOptions redis() {
        return config.redis;
    }
}

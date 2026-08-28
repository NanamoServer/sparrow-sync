package net.momirealms.sparrow.sync.configuration;

import net.momirealms.sparrow.sync.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.storage.StorageType;
import net.momirealms.sparrow.yaml.mapper.YamlMapper;
import net.momirealms.sparrow.yaml.mapper.YamlMapperFactory;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.BlankLineBefore;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.Comment;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.Configuration;
import net.momirealms.sparrow.yaml.upgrade.YamlUpgradePipeline;
import net.momirealms.sparrow.yaml.upgrade.version.FieldVersionExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class PluginConfig {
    private static PluginConfig instance;
    private final SparrowSync plugin;
    private final Path configFilePath;
    private final YamlUpgradePipeline upgradePipeline;
    private final YamlMapperFactory configurationFactory;
    private final YamlMapper<ConfigDefinition> configMapper;
    private ConfigDefinition config;

    public PluginConfig(SparrowSync plugin) {
        instance = this;
        this.plugin = plugin;
        this.configFilePath = this.plugin.dataFolderPath().resolve("config.yml");
        this.upgradePipeline = this.buildUpgradePipeline();
        this.configurationFactory = this.buildYamlMapperFactory();
        this.configMapper = this.configurationFactory.create(ConfigDefinition.class, ConfigDefinition::new);
    }

    public static PluginConfig instance() {
        return instance;
    }

    public ConfigDefinition config() {
        return this.config;
    }

    // 升级策略
    private YamlUpgradePipeline buildUpgradePipeline() {
        return YamlUpgradePipeline.builder()
                .versionExtractor(new FieldVersionExtractor("config-version"))
                .build();
    }

    // 注解类解析工厂
    private YamlMapperFactory buildYamlMapperFactory() {
        return YamlMapperFactory.builder()
                .sparrowYaml(this.plugin.sparrowYaml())
                .upgradePipeline(this.upgradePipeline)
                .build();
    }

    // 加载配置文件
    public void updateConfigCache() {
        try {
            this.config = this.configMapper.load(configFilePath).value();
        } catch (Exception e) {
            this.plugin.logger().error("Failed to update config.yml", e);
        }
    }

    /**
     * 根据传入的路径, 从插件资源文件夹中获取对应的文件.
     *
     * @param filePath 相对路径.
     * @return 目标文件真实 Path.
     */
    public Path resolveConfig(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("ResourcePath cannot be null or empty");
        }
        filePath = filePath.replace('\\', '/');
        Path configFile = this.plugin.dataFolderPath().resolve(filePath);
        // if the config doesn't exist, create it based on the template in the resources dir
        if (!Files.exists(configFile)) {
            try {
                Files.createDirectories(configFile.getParent());
            } catch (IOException ignored) {
            }
            try (InputStream is = this.plugin.resourceStream(filePath)) {
                if (is == null) {
                    throw new IllegalArgumentException("The embedded resource '" + filePath + "' cannot be found");
                }
                Files.copy(is, configFile);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return configFile;
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

        @Comment("Forces a specific locale (e.g., zh_cn)")
        Locale forcedLocale = null;

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
        @Comment("Debug")
        boolean debug = false;
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
                "Namespaces of persistent data (PDC) keys to synchronize, e.g. [craftengine, myplugin]",
                "Empty list replaces the whole container with the snapshot on apply;",
                "otherwise only keys under the listed namespaces are replaced and the rest stay untouched"
        })
        List<String> pdcMergeNamespaces = List.of();

        public int workerThreads() {
            return this.workerThreads;
        }

        public int shutdownTimeoutSeconds() {
            return this.shutdownTimeoutSeconds;
        }

        public int maxSnapshots() {
            return this.maxSnapshots;
        }

        public List<String> pdcMergeNamespaces() {
            return this.pdcMergeNamespaces;
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

        public StorageType type() {
            return this.type;
        }

        public MongoOptions mongodb() {
            return this.mongodb;
        }

        public MysqlOptions mysql() {
            return this.mysql;
        }
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



    public static boolean checkUpdate() {
        return instance.config.updateChecker;
    }

    public static boolean metrics() {
        return instance.config.metrics;
    }

    public static Locale forcedLocale() {
        return instance.config.forcedLocale;
    }

    public static SynchronizationOptions synchronization() {
        return instance.config.synchronization;
    }

    public static DatabaseOptions database() {
        return instance.config.database;
    }

    public static RedisOptions redis() {
        return instance.config.redis;
    }
}

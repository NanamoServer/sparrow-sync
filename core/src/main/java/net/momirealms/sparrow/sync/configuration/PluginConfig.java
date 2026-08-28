package net.momirealms.sparrow.sync.configuration;

import net.momirealms.sparrow.sync.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
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
}

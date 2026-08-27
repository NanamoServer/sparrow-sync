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
        @Comment("Debug")
        DebugOptions debug = DebugOptions.DISABLE;
    }

    public record DebugOptions(
            boolean common
    ) {
        public static DebugOptions DISABLE = new DebugOptions(false);
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
}

package net.momirealms.sparrow.sync.configuration;

import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.mapper.YamlMapper;
import net.momirealms.sparrow.yaml.mapper.YamlMapperFactory;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.Comment;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.Configuration;
import net.momirealms.sparrow.yaml.upgrade.YamlUpgradePipeline;
import net.momirealms.sparrow.yaml.upgrade.version.FieldVersionExtractor;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public final class ServerConfig {
    private static final String CONFIG_FILE = "server.yml";
    private static volatile ConfigDefinition config;

    private final Plugin plugin;
    private final Path configFilePath;
    private final YamlMapper<ConfigDefinition> configMapper;

    ServerConfig(Plugin plugin, SparrowYaml sparrowYaml) {
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
        // 首次生成时把推导出的服务器 id 写进文件, 用户看到的是真实取值而不是留给他填的空位
        String derivedServerId = deriveServerId(plugin.dataFolderPath());
        this.configMapper = mapperFactory.create(ConfigDefinition.class, () -> {
            ConfigDefinition definition = new ConfigDefinition();
            definition.serverId = derivedServerId;
            return definition;
        });
    }

    void reload() {
        try {
            config = this.configMapper.load(this.configFilePath).value();
        } catch (Exception e) {
            this.plugin.logger().error("Failed to load " + CONFIG_FILE, e);
        }
    }

    /**
     * 数据目录形如 {@code <服务器目录>/plugins/<插件名>}, 上溯两层即服务器目录, 取其名字作为默认 id.
     * 目录层级不足时返回空串, 由启动期校验拦下并要求手工配置.
     */
    private static String deriveServerId(Path dataFolder) {
        Path pluginsDirectory = dataFolder.toAbsolutePath().getParent();
        Path serverDirectory = pluginsDirectory == null ? null : pluginsDirectory.getParent();
        Path name = serverDirectory == null ? null : serverDirectory.getFileName();
        return name == null ? "" : name.toString();
    }

    /**
     * 本服在同步集群中的唯一标识, 未配置且无法推导时为空串.
     */
    @NotNull
    public static String serverId() {
        return config.serverId;
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class ConfigDefinition {
        @Comment("Do not modify this value")
        String configVersion = DependencyVersions.CONFIG_VERSION;

        @Comment({
                "Identifies this server inside the sync cluster, it must be unique across every server sharing the database",
                "Generated from the name of the server directory on first run, change it if that name is not what you want",
                "Snapshots record it, so renaming it later only affects snapshots written from now on"
        })
        String serverId = "";
    }
}

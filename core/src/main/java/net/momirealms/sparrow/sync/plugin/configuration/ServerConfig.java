package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.mapper.YamlMapper;
import net.momirealms.sparrow.yaml.mapper.YamlMapperFactory;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.BlankLineBefore;
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
        this.configMapper = mapperFactory.create(ConfigDefinition.class, ConfigDefinition::new);
    }

    void reload() {
        try {
            config = this.configMapper.load(this.configFilePath).value();
        } catch (Exception e) {
            this.plugin.logger().error("Failed to load " + CONFIG_FILE, e);
        }
    }

    /**
     * 本服在同步集群中的唯一标识, 未配置时为空串.
     */
    @NotNull
    public static String serverId() {
        return config.serverId;
    }

    /**
     * 本服所属集群的标识, 决定 Redis 键前缀, 共享同一数据库和 Redis 的服务器必须一致.
     */
    @NotNull
    public static String clusterId() {
        return config.clusterId;
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class ConfigDefinition {
        @Comment("Configuration file version, do not modify this value")
        @Comment(lang = "zh-CN", value = "配置文件版本, 请勿修改此值")
        String configVersion = DependencyVersions.CONFIG_VERSION;

        @Comment({
                "Unique identifier for this server in the synchronization cluster; every server participating in data synchronization must use a different value",
                "Set this before startup; SparrowSync shuts down the server if this value is empty",
                "Once set, changing this value is discouraged; a new value identifies a new server and may affect map data synchronization"
        })
        @Comment(lang = "zh-CN", value = {
                "本服务器在同步集群中的唯一标志符, 所有参与数据同步的服务器必须使用不同的值",
                "请在启动前设置, 此值为空时 SparrowSync 会关闭服务器",
                "注意: 一旦设置后, 不再推荐未来修改此值, 新的值会被视为新的服务器, 这可能会对地图数据同步造成一定的影响"
        })
        String serverId = "";

        // todo 待删除
        @BlankLineBefore
        @Comment({
                "Identifier of the data synchronization cluster this server belongs to; all servers synchronizing data together must use the same value",
                "Set this before startup; SparrowSync shuts down the server if this value is empty",
                "This prefixes every Redis key, allowing multiple clusters to share Redis without interfering with each other"
        })
        @Comment(lang = "zh-CN", value = {
                "本服务器所属的数据同步集群标志符, 所有参与数据同步的同一批服务器必须使用相同的值.",
                "请在启动前设置, 此值为空时 SparrowSync 会关闭服务器.",
                "此值用作所有 Redis 键的前缀, 使多个集群可以共用 Redis 而互不干扰"
        })
        String clusterId = "main";
    }
}

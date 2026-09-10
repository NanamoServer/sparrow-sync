package net.momirealms.sparrow.sync.plugin.configuration;

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

import java.io.IOException;
import java.io.UncheckedIOException;
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
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + CONFIG_FILE, e);
        }
    }

    /**
     * 本服在同步集群中的唯一标识, 未配置时为空串.
     */
    @NotNull
    public static String serverId() {
        return config.serverId;
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class ConfigDefinition {
        @Comment("Configuration file version, do not modify this value")
        @Comment(lang = "zh-CN", value = "配置文件版本, 请勿修改此值")
        String configVersion = DependencyVersions.SERVER_CONFIG_VERSION;

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
        String serverId = ""; // 同时参与默认地图源 ID, 改名后旧地图按原来源身份保留
    }
}

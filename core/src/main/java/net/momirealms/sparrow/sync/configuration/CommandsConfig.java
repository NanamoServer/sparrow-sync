package net.momirealms.sparrow.sync.configuration;

import net.momirealms.sparrow.sync.command.CommandConfig;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.mapper.YamlMapper;
import net.momirealms.sparrow.yaml.mapper.YamlMapperFactory;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.BlankLineBefore;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.Comment;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.Configuration;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.YamlProperty;
import net.momirealms.sparrow.yaml.upgrade.YamlUpgradePipeline;
import net.momirealms.sparrow.yaml.upgrade.version.FieldVersionExtractor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class CommandsConfig {
    private static final String CONFIG_FILE = "commands.yml";

    private final Path configFilePath;
    private final YamlMapper<ConfigDefinition> configMapper;
    private final ConfigDefinition configDefinition;

    CommandsConfig(Path dataFolderPath, SparrowYaml sparrowYaml) {
        this.configFilePath = dataFolderPath.resolve(CONFIG_FILE);
        YamlUpgradePipeline upgradePipeline = YamlUpgradePipeline.builder()
                .versionExtractor(new FieldVersionExtractor("config-version"))
                .build();
        YamlMapperFactory mapperFactory = YamlMapperFactory.builder()
                .backupOnUpgrade(true)
                .sparrowYaml(sparrowYaml)
                .upgradePipeline(upgradePipeline)
                .build();
        this.configMapper = mapperFactory.create(ConfigDefinition.class, ConfigDefinition::new);
        this.configDefinition = this.load();
    }

    @NotNull
    ConfigDefinition load() {
        try {
            return this.configMapper.load(this.configFilePath).value();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + CONFIG_FILE, e);
        }
    }

    public ConfigDefinition configDefinition() {
        return this.configDefinition;
    }

    @Configuration(naming = Configuration.Naming.SNAKE_CASE)
    public static class ConfigDefinition {
        @YamlProperty("config-version")
        @Comment("Do not modify this value")
        String configVersion = DependencyVersions.CONFIG_VERSION;

        @BlankLineBefore
        @Comment({
                "",
                "For safety reasons, editing this file requires a restart to apply",
                ""
        })
        CommandConfig test = new CommandConfig(true, List.of("/sparrow-sync test"), "ce.command.admin.test");

        @BlankLineBefore
        CommandConfig reload = new CommandConfig(true, List.of("/sparrow-sync reload"), "ce.command.admin.reload");

        @BlankLineBefore
        CommandConfig debugSaveBinary = new CommandConfig(true, List.of("/sparrow-sync debug save-binary"), "ce.command.debug.save_binary");

        @BlankLineBefore
        CommandConfig debugSaveJson = new CommandConfig(true, List.of("/sparrow-sync debug save-json"), "ce.command.debug.save_json");

        @BlankLineBefore
        CommandConfig debugApply = new CommandConfig(true, List.of("/sparrow-sync debug apply"), "ce.command.debug.apply");

        /**
         * 返回指定内置 Feature 的命令配置.
         *
         * @param featureID Feature 标识
         * @return 对应的命令配置
         * @throws IllegalArgumentException 当标识不属于内置 Feature 时
         */
        @NotNull
        public CommandConfig command(@NotNull String featureID) {
            return switch (featureID) {
                case "test" -> this.test;
                case "reload" -> this.reload;
                case "debug_save_binary" -> this.debugSaveBinary;
                case "debug_save_json" -> this.debugSaveJson;
                case "debug_apply" -> this.debugApply;
                default -> throw new IllegalArgumentException("Unknown default command feature: " + featureID);
            };
        }
    }
}

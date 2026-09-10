package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.command.CommandConfig;
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
        @Comment(lang = "zh-CN", value = "请勿修改此值")
        String configVersion = DependencyVersions.COMMANDS_CONFIG_VERSION;

        @BlankLineBefore
        @Comment({
                "",
                "For safety reasons, editing this file requires a restart to apply",
                ""
        })
        @Comment(lang = "zh-CN", value = {
                "",
                "出于安全考虑, 修改此文件后需要重启服务器才能生效",
                ""
        })
        CommandConfig test = new CommandConfig(true, List.of("/sparrow-sync test"), "ce.command.admin.test");

        @BlankLineBefore
        CommandConfig reload = new CommandConfig(true, List.of("/sparrow-sync reload"), "sparrow_sync.command.reload");

        @BlankLineBefore
        CommandConfig status = new CommandConfig(true, List.of("/sparrow-sync status"), "sparrow_sync.command.status");

        @BlankLineBefore
        CommandConfig gui = new CommandConfig(true, List.of("/sparrow-sync gui"), "sparrow_sync.command.view");

        @BlankLineBefore
        CommandConfig snapshotView = new CommandConfig(true, List.of("/sparrow-sync snapshot view"), "sparrow_sync.command.view");

        @BlankLineBefore
        CommandConfig exceptionList = new CommandConfig(true, List.of("/sparrow-sync exception list"), "sparrow_sync.command.view");

        @BlankLineBefore
        CommandConfig exceptionView = new CommandConfig(true, List.of("/sparrow-sync exception view"), "sparrow_sync.command.view");

        @BlankLineBefore
        CommandConfig snapshotList = new CommandConfig(true, List.of("/sparrow-sync snapshot list"), "sparrow_sync.command.view");

        @BlankLineBefore
        CommandConfig snapshotCapture = new CommandConfig(true, List.of("/sparrow-sync snapshot capture"), "sparrow_sync.command.capture");

        @BlankLineBefore
        CommandConfig snapshotRestore = new CommandConfig(true, List.of("/sparrow-sync snapshot restore"), "sparrow_sync.command.restore");

        @BlankLineBefore
        CommandConfig snapshotPin = new CommandConfig(true, List.of("/sparrow-sync snapshot pin"), "sparrow_sync.command.pin");

        @BlankLineBefore
        CommandConfig snapshotUnpin = new CommandConfig(true, List.of("/sparrow-sync snapshot unpin"), "sparrow_sync.command.unpin");

        @BlankLineBefore
        CommandConfig snapshotDelete = new CommandConfig(true, List.of("/sparrow-sync snapshot delete"), "sparrow_sync.command.delete");

        @BlankLineBefore
        CommandConfig snapshotExport = new CommandConfig(true, List.of("/sparrow-sync snapshot export"), "sparrow_sync.command.export");

        @BlankLineBefore
        CommandConfig snapshotImport = new CommandConfig(true, List.of("/sparrow-sync snapshot import"), "sparrow_sync.command.import");

        @BlankLineBefore
        CommandConfig dumpAll = new CommandConfig(true, List.of("/sparrow-sync data dump_all"), "sparrow_sync.command.dumpall");

        @BlankLineBefore
        CommandConfig importAll = new CommandConfig(true, List.of("/sparrow-sync data import_all"), "sparrow_sync.command.importall");

        @BlankLineBefore
        CommandConfig migrate = new CommandConfig(true, List.of("/sparrow-sync data migrate"), "sparrow_sync.command.migrate");

        @BlankLineBefore
        CommandConfig exceptionDelete = new CommandConfig(true, List.of("/sparrow-sync exception delete"), "sparrow_sync.command.exception.delete");

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
                case "status" -> this.status;
                case "gui" -> this.gui;
                case "snapshot_view" -> this.snapshotView;
                case "snapshot_list" -> this.snapshotList;
                case "exception_list" -> this.exceptionList;
                case "exception_view" -> this.exceptionView;
                case "snapshot_capture" -> this.snapshotCapture;
                case "snapshot_restore" -> this.snapshotRestore;
                case "snapshot_pin" -> this.snapshotPin;
                case "snapshot_unpin" -> this.snapshotUnpin;
                case "snapshot_delete" -> this.snapshotDelete;
                case "snapshot_export" -> this.snapshotExport;
                case "snapshot_import" -> this.snapshotImport;
                case "dump_all" -> this.dumpAll;
                case "import_all" -> this.importAll;
                case "migrate" -> this.migrate;
                case "exception_delete" -> this.exceptionDelete;
                default -> throw new IllegalArgumentException("Unknown default command feature: " + featureID);
            };
        }
    }
}

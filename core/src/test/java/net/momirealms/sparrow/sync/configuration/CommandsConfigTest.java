package net.momirealms.sparrow.sync.configuration;

import net.momirealms.sparrow.sync.command.CommandConfig;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.route.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsConfigTest {
    @TempDir
    Path directory;

    @Test
    void createsDefaultDocumentFromAnnotatedDefinition() throws IOException {
        SparrowYaml yaml = newYaml();
        CommandsConfig.ConfigDefinition config = new CommandsConfig(this.directory, yaml).load();

        assertCommand(config, "test", true, "ce.command.admin.test", "/sparrow-sync test");
        assertCommand(config, "reload", true, "ce.command.admin.reload", "/sparrow-sync reload");
        assertCommand(config, "debug_save_binary", true, "ce.command.debug.save_binary", "/sparrow-sync debug save-binary");
        assertCommand(config, "debug_save_json", true, "ce.command.debug.save_json", "/sparrow-sync debug save-json");
        assertCommand(config, "debug_apply", true, "ce.command.debug.apply", "/sparrow-sync debug apply");
        assertThrows(IllegalArgumentException.class, () -> config.command("unknown"));

        Path file = this.directory.resolve("commands.yml");
        YamlDocument document = yaml.load(file);
        assertEquals(DependencyVersions.CONFIG_VERSION, document.getString(Route.from("config-version")));
        assertNotNull(document.getNodeOrNull(Route.from("debug_save_binary")));
        assertNotNull(document.getNodeOrNull(Route.from("debug_save_json")));
        assertNotNull(document.getNodeOrNull(Route.from("debug_apply")));
        assertNull(document.getNodeOrNull(Route.from("debug-save-binary")));
        assertTrue(Files.readString(file).contains("# For safety reasons, editing this file requires a restart to apply"));
    }

    @Test
    void upgradesVersionOneDocumentAndPreservesConfiguredCommands() throws IOException {
        String original = """
                config-version: "1"
                test:
                  enable: false
                  permission: custom.command.test
                  usages:
                    - /custom test
                reload:
                  enable: true
                  permission: custom.command.reload
                  usages:
                    - /custom reload
                """;
        Path file = this.directory.resolve("commands.yml");
        Files.writeString(file, original, StandardCharsets.UTF_8);

        SparrowYaml yaml = newYaml();
        CommandsConfig.ConfigDefinition config = new CommandsConfig(this.directory, yaml).load();

        assertCommand(config, "test", false, "custom.command.test", "/custom test");
        assertCommand(config, "reload", true, "custom.command.reload", "/custom reload");
        assertCommand(config, "debug_save_binary", true, "ce.command.debug.save_binary", "/sparrow-sync debug save-binary");
        assertCommand(config, "debug_save_json", true, "ce.command.debug.save_json", "/sparrow-sync debug save-json");
        assertCommand(config, "debug_apply", true, "ce.command.debug.apply", "/sparrow-sync debug apply");

        YamlDocument upgraded = yaml.load(file);
        assertEquals(DependencyVersions.CONFIG_VERSION, upgraded.getString(Route.from("config-version")));
        assertNotNull(upgraded.getNodeOrNull(Route.from("debug_save_binary")));
        assertNotNull(upgraded.getNodeOrNull(Route.from("debug_save_json")));
        assertNotNull(upgraded.getNodeOrNull(Route.from("debug_apply")));

        List<Path> backups;
        try (Stream<Path> paths = Files.list(this.directory)) {
            backups = paths.filter(path -> path.getFileName().toString().startsWith("commands.yml.bak.")).toList();
        }
        assertEquals(1, backups.size());
        assertEquals(original, Files.readString(backups.getFirst(), StandardCharsets.UTF_8));
    }

    private static SparrowYaml newYaml() {
        return SparrowYaml.builder()
                .setAllowDuplicateKeys(false)
                .setAllowObjectKeys(false)
                .build();
    }

    private static void assertCommand(CommandsConfig.ConfigDefinition config, String featureID, boolean enabled, String permission, String usage) {
        CommandConfig command = config.command(featureID);
        assertEquals(enabled, command.isEnable());
        assertEquals(permission, command.getPermission());
        assertEquals(List.of(usage), command.getUsages());
    }
}

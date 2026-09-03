package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.route.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingConfigTest {
    @TempDir
    Path directory;

    @Test
    void defaultsPrintOnlyCommandSavesAndHidePerPlayerShutdownSaves() throws Exception {
        SparrowYaml yaml = newYaml();
        new PluginConfig(this.plugin(), yaml).reload();

        assertTrue(PluginConfig.logging$consoleSave(SaveCause.COMMAND));
        assertTrue(PluginConfig.logging$consoleSave(SaveCause.DISCONNECT));
        assertFalse(PluginConfig.logging$consoleSave(SaveCause.WORLD_SAVE));
        assertFalse(PluginConfig.logging$consoleSave(SaveCause.SHUTDOWN));

        YamlDocument document = yaml.load(this.directory.resolve("config.yml"));
        this.assertAllCauseDefaults(document);
    }

    @Test
    void reloadPublishesConfiguredConsoleSaveCauses() throws Exception {
        Files.writeString(this.directory.resolve("config.yml"), """
                config-version: "%s"
                logging:
                  console-save-causes:
                    disconnect: false
                    world-save: true
                    death: true
                    command: false
                    shutdown: true
                """.formatted(DependencyVersions.CONFIG_VERSION), StandardCharsets.UTF_8);

        new PluginConfig(this.plugin(), newYaml()).reload();

        assertTrue(PluginConfig.logging$consoleSave(SaveCause.WORLD_SAVE));
        assertTrue(PluginConfig.logging$consoleSave(SaveCause.DEATH));
        assertFalse(PluginConfig.logging$consoleSave(SaveCause.COMMAND));
        assertFalse(PluginConfig.logging$consoleSave(SaveCause.DISCONNECT));
        assertTrue(PluginConfig.logging$consoleSave(SaveCause.SHUTDOWN));
    }

    @Test
    void upgradesVersionNineListWithExpandedCauseDefaults() throws Exception {
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, """
                config-version: "9"
                logging:
                  console-save-causes:
                    - COMMAND
                  console-shutdown-player-saves: false
                  local-file: false
                """, StandardCharsets.UTF_8);
        SparrowYaml yaml = newYaml();

        new PluginConfig(this.plugin(), yaml).reload();

        assertFalse(PluginConfig.logging$localFile());
        assertTrue(PluginConfig.logging$consoleSave(SaveCause.COMMAND));
        assertTrue(PluginConfig.logging$consoleSave(SaveCause.DISCONNECT));
        assertFalse(PluginConfig.logging$consoleSave(SaveCause.SHUTDOWN));
        YamlDocument upgraded = yaml.load(file);
        assertEquals(DependencyVersions.CONFIG_VERSION, upgraded.getString(Route.from("config-version")));
        this.assertAllCauseDefaults(upgraded);
        assertFalse(upgraded.contains(Route.from("logging", "console-shutdown-player-saves")));
    }

    private void assertAllCauseDefaults(YamlDocument document) {
        SaveCause[] causes = SaveCause.values();
        for (int i = 0; i < causes.length; i++) {
            SaveCause cause = causes[i];
            boolean expected = cause == SaveCause.DISCONNECT || cause == SaveCause.COMMAND;
            String key = cause.name().toLowerCase(Locale.ROOT).replace('_', '-');
            assertEquals(expected, document.getBoolean(Route.from("logging", "console-save-causes", key)), cause.name());
        }
    }

    private static SparrowYaml newYaml() {
        return SparrowYaml.builder().setAllowDuplicateKeys(false).setAllowObjectKeys(false).build();
    }

    private Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "dataFolderPath" -> this.directory;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
}

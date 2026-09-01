package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.SaveTriggers;
import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.mapper.YamlMapper;
import net.momirealms.sparrow.yaml.mapper.YamlMapperFactory;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveTriggerConfigTest {
    @TempDir
    Path directory;

    @Test
    void defaultsAndTriggerCommentsAreWrittenToYaml() throws IOException {
        Path file = this.directory.resolve("save-trigger-defaults.yml");
        YamlMapper<PluginConfig.SynchronizationOptions> mapper = mapper();

        PluginConfig.SynchronizationOptions options = mapper.load(file).value();
        String yaml = Files.readString(file, StandardCharsets.UTF_8);

        assertFalse(options.saveTriggers.worldChange.enabled);
        assertTrue(options.saveTriggers.interval.enabled);
        assertEquals(5, options.saveTriggers.interval.minutes);
        assertTrue(options.saveTriggers.gameModeChange.enabled);
        assertFalse(options.saveTriggers.death.saveBeforeDeath);
        assertTrue(options.saveTriggers.death.saveAfterDeath);
        assertTrue(yaml.contains("ignored-from-worlds"), yaml);
        assertTrue(yaml.contains("ignored-to-worlds"), yaml);
        assertTrue(yaml.contains("ignored-target-modes"), yaml);
        assertTrue(yaml.contains("save-before-death"), yaml);
        assertTrue(yaml.contains("save-after-death"), yaml);
        assertTrue(yaml.contains("The snapshot includes the items that actually remain on the dead player"), yaml);
    }

    @Test
    void reloadPublishesOneNormalizedTriggerSnapshot() throws IOException {
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, """
                config-version: "%s"
                synchronization:
                  save-triggers:
                    world-change:
                      enabled: true
                      ignored-from-worlds: [spawn, spawn]
                      ignored-to-worlds: [dungeon]
                    interval:
                      enabled: true
                      minutes: 0
                    game-mode-change:
                      enabled: true
                      ignored-target-modes: [CREATIVE, CREATIVE, SPECTATOR]
                    death:
                      save-before-death: true
                      save-after-death: false
                      ignored-worlds: [arena, arena]
                """.formatted(DependencyVersions.CONFIG_VERSION), StandardCharsets.UTF_8);
        PluginConfig pluginConfig = new PluginConfig(this.plugin(), newYaml());

        pluginConfig.reload();

        SaveTriggers first = PluginConfig.synchronization$saveTriggers();
        assertTrue(first.worldChange().enabled());
        assertEquals(Set.of("spawn"), first.worldChange().ignoredFromWorlds());
        assertEquals(Set.of("dungeon"), first.worldChange().ignoredToWorlds());
        assertEquals(1, first.interval().minutes());
        assertEquals(Set.of(GameMode.CREATIVE, GameMode.SPECTATOR), first.gameModeChange().ignoredTargetModes());
        assertTrue(first.death().saveBeforeDeath());
        assertFalse(first.death().saveAfterDeath());
        assertEquals(Set.of("arena"), first.death().ignoredWorlds());

        Files.writeString(file, """
                config-version: "%s"
                synchronization:
                  save-triggers:
                    interval:
                      enabled: false
                      minutes: 12
                """.formatted(DependencyVersions.CONFIG_VERSION), StandardCharsets.UTF_8);

        pluginConfig.reload();

        SaveTriggers second = PluginConfig.synchronization$saveTriggers();
        assertNotSame(first, second);
        assertFalse(second.interval().enabled());
        assertEquals(12, second.interval().minutes());
        assertEquals(Set.of("spawn"), first.worldChange().ignoredFromWorlds());
    }

    private static YamlMapper<PluginConfig.SynchronizationOptions> mapper() {
        return YamlMapperFactory.builder()
                .sparrowYaml(newYaml())
                .build()
                .create(PluginConfig.SynchronizationOptions.class, PluginConfig.SynchronizationOptions::new);
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


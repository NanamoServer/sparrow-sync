package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.yaml.SparrowYaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OnlineRestoreConfigTest {
    @TempDir Path directory;

    @Test
    void upgradeAddsDisabledOptionsAndReloadKeepsThemIndependent() throws Exception {
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, """
                config-version: "24"
                synchronization:
                  data-types:
                    health: true
                    location: true
                """);
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (instance, method, args) -> {
            if (method.getName().equals("dataFolderPath")) return this.directory;
            throw new AssertionError(method.getName());
        });
        PluginConfig config = new PluginConfig(plugin, SparrowYaml.builder().setAllowDuplicateKeys(false).setAllowObjectKeys(false).build());
        config.reload();
        assertFalse(PluginConfig.synchronization$onlineRestore().syncHealth());
        assertFalse(PluginConfig.synchronization$onlineRestore().syncLocation());
        String written = Files.readString(file);
        assertTrue(written.contains("online-restore:"));
        assertTrue(written.contains("sync-health: false"));
        assertTrue(written.contains("sync-location: false"));
        Files.writeString(file, written.replace("sync-health: false", "sync-health: true"));
        config.reload();
        assertTrue(PluginConfig.synchronization$onlineRestore().syncHealth());
        assertFalse(PluginConfig.synchronization$onlineRestore().syncLocation());
        Files.writeString(file, written.replace("sync-location: false", "sync-location: true"));
        config.reload();
        assertFalse(PluginConfig.synchronization$onlineRestore().syncHealth());
        assertTrue(PluginConfig.synchronization$onlineRestore().syncLocation());
    }
}

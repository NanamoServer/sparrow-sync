package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.route.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ShutdownConfigTest {
    @TempDir
    Path directory;

    @Test
    void upgradingKeepsTimeoutAndNewConfigExplainsIdleAndTailBudgets() throws Exception {
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, """
                config-version: "27"
                synchronization:
                  shutdown-timeout-seconds: 47
                """);
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
            if (method.getName().equals("dataFolderPath")) return this.directory;
            throw new AssertionError(method.getName());
        });
        SparrowYaml yaml = SparrowYaml.builder().setAllowDuplicateKeys(false).setAllowObjectKeys(false).build();
        Field field = PluginConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        Object previous = field.get(null);
        try {
            new PluginConfig(plugin, yaml).reload();
            assertEquals(47, PluginConfig.synchronization$shutdownTimeoutSeconds());
            String written = Files.readString(file);
            assertEquals(DependencyVersions.CONFIG_VERSION, yaml.load(written).getString(Route.from("config-version")));
            try (var files = Files.list(this.directory)) {
                assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("config.yml.bak.")));
            }
            Files.delete(file);
            new PluginConfig(plugin, yaml).reload();
            assertEquals(30, PluginConfig.synchronization$shutdownTimeoutSeconds());
            String generated = Files.readString(file);
            assertTrue(generated.contains("each increase resets the timeout") || generated.contains("完成数增加时重置计时"));
            assertTrue(generated.contains("one additional fixed budget") || generated.contains("固定收尾预算"));
        } finally {
            field.set(null, previous);
        }
    }
}

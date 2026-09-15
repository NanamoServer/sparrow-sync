package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.yaml.mapper.YamlMapperFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UnknownDataConfigurationTest {
    @Test
    void keysRoundTripAndFrozenRegistrySurvivesReload(@TempDir Path directory) throws Exception {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
            if (method.getName().equals("dataFolderPath")) return directory;
            throw new AssertionError(method.getName());
        });
        ConfigurationManager manager = new ConfigurationManager(plugin);
        PluginConfig config = new PluginConfig(plugin, manager.sparrowYaml());
        Field current = PluginConfig.class.getDeclaredField("config");
        current.setAccessible(true);
        Object previous = current.get(null);
        try {
            config.reload();
            assertEquals(List.of(DataKey.sparrow("health"), DataKey.sparrow("location")), PluginConfig.synchronization$skipOnlineRestoreData());
            assertEquals(List.of(
                    DataKey.sparrow("attributes"),
                    DataKey.sparrow("enchantment_seed"),
                    DataKey.sparrow("experience"),
                    DataKey.sparrow("flight_status"),
                    DataKey.sparrow("game_mode"),
                    DataKey.sparrow("health"),
                    DataKey.sparrow("hunger"),
                    DataKey.sparrow("location")
            ), PluginConfig.synchronization$discardUnknownData());
            Path path = directory.resolve("config.yml");
            Files.writeString(path, """
                    ___version___: '4'
                    synchronization:
                      discard-unknown-data: [location, 'external:book']
                      skip-online-restore-data: [health, 'external:book']
                      map:
                        player-operation:
                          allow-banner-modification: true
                          allow-lock: true
                          allow-scale: true
                          allow-copy: false
                      attributes:
                        inject-on-dirty-consumer: false
                    """);
            config.reload();
            List<DataKey> skippedOnline = List.of(DataKey.sparrow("health"), DataKey.of("external", "book"));
            assertEquals(skippedOnline, PluginConfig.synchronization$skipOnlineRestoreData());
            PluginConfig.MapPlayerOperationOptions operations = PluginConfig.synchronization$map().playerOperation();
            assertTrue(operations.allowBannerModification());
            assertTrue(operations.allowLock());
            assertTrue(operations.allowScale());
            assertFalse(operations.allowCopy());
            assertFalse(PluginConfig.synchronization$attributes().injectOnDirtyConsumer());
            List<DataKey> configured = PluginConfig.synchronization$discardUnknownData();
            assertEquals(List.of(DataKey.sparrow("location"), DataKey.of("external", "book")), configured);
            DataRegistry registry = new DataRegistry();
            assertTrue(registry.shouldDropUnknown(DataKey.sparrow("location")));
            assertTrue(registry.shouldDropUnknown(DataKey.of("external", "book")));
            DataKey apiKey = DataKey.of("external", "api");
            registry.registerUnknownDrop(apiKey);
            registry.freeze();

            var mapper = YamlMapperFactory.builder().sparrowYaml(manager.sparrowYaml()).build()
                    .create(PluginConfig.ConfigDefinition.class, PluginConfig.ConfigDefinition::new);
            mapper.save(path, (PluginConfig.ConfigDefinition) current.get(null));
            assertTrue(Files.readString(path).contains("sparrow_sync:location"));
            config.reload();
            assertEquals(configured, PluginConfig.synchronization$discardUnknownData());
            assertEquals(skippedOnline, PluginConfig.synchronization$skipOnlineRestoreData());

            Files.writeString(path, "___version___: '4'\nsynchronization:\n  discard-unknown-data: ['external:new']\n");
            config.reload();
            assertEquals(List.of(DataKey.of("external", "new")), PluginConfig.synchronization$discardUnknownData());
            assertTrue(registry.shouldDropUnknown(DataKey.sparrow("location")));
            assertTrue(registry.shouldDropUnknown(DataKey.of("external", "book")));
            assertTrue(registry.shouldDropUnknown(apiKey));
            assertFalse(registry.shouldDropUnknown(DataKey.of("external", "new")));
        } finally {
            current.set(null, previous);
        }
    }
}

package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.map.MapSyncService;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.yaml.SparrowYaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class MapConfigTest {
    @TempDir
    Path directory;

    @Test
    void syncModeIsRecognizedByTheTypedConfiguration() throws Exception {
        Files.writeString(this.directory.resolve("config.yml"), """
                config-version: "%s"
                synchronization:
                  map:
                    enabled: true
                    type: SYNC
                    map-owner-id: "owner"
                """.formatted(DependencyVersions.CONFIG_VERSION));
        this.config().reload();
        assertEquals(MapType.SYNC, PluginConfig.synchronization$map().type());
    }

    @Test
    void defaultMapConfigurationWritesBothOptionsAndResolvesTheOverworldIdentity() throws Exception {
        this.config().reload();

        PluginConfig.MapOptions options = PluginConfig.synchronization$map();
        UUID worldUuid = UUID.fromString("12345678-1234-5678-9012-123456789012");
        assertTrue(options.enabled());
        assertEquals(MapType.SYNC, options.type());
        assertEquals("${server-id}-${world-uuid}", options.mapOwnerId());
        assertFalse(options.allowBannerModification());
        assertFalse(options.allowLock());
        assertFalse(options.allowScale());
        assertTrue(options.allowCopy());
        assertEquals("A-" + worldUuid, options.resolveOwnerId("A", worldUuid));
        assertNotEquals(options.resolveOwnerId("A", worldUuid), options.resolveOwnerId("A", UUID.randomUUID()));
        String yaml = Files.readString(this.directory.resolve("config.yml"));
        assertTrue(yaml.contains("map:"), yaml);
        assertTrue(yaml.contains("enabled: true"), yaml);
        assertTrue(yaml.contains("type: SYNC"), yaml);
        assertTrue(yaml.contains("map-owner-id:"), yaml);
        assertTrue(yaml.contains("${server-id}-${world-uuid}"), yaml);
        assertTrue(yaml.contains("allow-banner-modification: false"), yaml);
        assertTrue(yaml.contains("allow-lock: false"), yaml);
        assertTrue(yaml.contains("allow-scale: false"), yaml);
        assertTrue(yaml.contains("allow-copy: true"), yaml);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reloadKeepsStartupSwitchAndOwnerButUpdatesMode(boolean enabled) throws Exception {
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, """
                config-version: "%s"
                synchronization:
                  map:
                    enabled: %s
                    type: HIDE
                    map-owner-id: "maps/${world-uuid}/${server-id}"
                """.formatted(DependencyVersions.CONFIG_VERSION, enabled));
        PluginConfig config = this.config();
        config.reload();
        PluginConfig.MapOptions first = PluginConfig.synchronization$map();
        UUID worldUuid = UUID.randomUUID();
        assertEquals(MapType.HIDE, first.type());
        assertEquals(enabled, first.enabled());
        assertEquals("maps/" + worldUuid + "/A", first.resolveOwnerId("A", worldUuid));

        Files.writeString(file, """
                config-version: "%s"
                synchronization:
                  map:
                    enabled: %s
                    type: SYNC
                    map-owner-id: "fixed-owner"
                """.formatted(DependencyVersions.CONFIG_VERSION, !enabled));
        config.reload();
        PluginConfig.MapOptions second = PluginConfig.synchronization$map();
        assertEquals(enabled, second.enabled());
        assertEquals(MapType.SYNC, second.type());
        assertEquals("maps/" + worldUuid + "/B", second.resolveOwnerId("B", worldUuid));
        assertEquals(enabled, first.enabled());
        assertEquals(MapType.HIDE, first.type());
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("enabled: " + !enabled), yaml);
        assertTrue(yaml.contains("fixed-owner"), yaml);

        this.config().reload();
        PluginConfig.MapOptions restarted = PluginConfig.synchronization$map();
        assertEquals(!enabled, restarted.enabled());
        assertEquals(MapType.SYNC, restarted.type());
        assertEquals("fixed-owner", restarted.resolveOwnerId("B", worldUuid));
    }

    @Test
    void interactionOptionsUpgradeAndReloadWithoutChangingTheStartupSettings() throws Exception {
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, """
                config-version: "18"
                synchronization:
                  map:
                    enabled: true
                    type: SYNC
                    map-owner-id: "existing-owner"
                """);
        PluginConfig config = this.config();
        config.reload();
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("allow-copy: true"), yaml);
        assertTrue(yaml.contains("allow-lock: false"), yaml);
        Files.writeString(file, yaml.replace("allow-banner-modification: false", "allow-banner-modification: true")
                .replace("allow-lock: false", "allow-lock: true").replace("allow-scale: false", "allow-scale: true")
                .replace("allow-copy: true", "allow-copy: false"));
        config.reload();
        PluginConfig.MapOptions options = PluginConfig.synchronization$map();
        assertTrue(options.allowBannerModification());
        assertTrue(options.allowLock());
        assertTrue(options.allowScale());
        assertFalse(options.allowCopy());
        assertEquals("existing-owner", options.mapOwnerId());
        assertTrue(options.enabled());
    }

    @Test
    void previousModesMigrateToTheSwitch() throws Exception {
        Path file = this.directory.resolve("config.yml");
        String[] previousTypes = {"NONE", "HIDE"};
        for (int i = 0; i < previousTypes.length; i++) {
            String previous = previousTypes[i];
            Files.writeString(file, """
                    config-version: "14"
                    synchronization:
                      map:
                        type: %s
                        map-owner-id: "existing-owner"
                    """.formatted(previous));
            this.config().reload();
            PluginConfig.MapOptions options = PluginConfig.synchronization$map();
            assertEquals("HIDE".equals(previous), options.enabled());
            assertEquals(MapType.HIDE, options.type());
            assertEquals("existing-owner", options.mapOwnerId());
        }
    }

    @Test
    void disabledSnapshotPreparationDoesNotEnterTheMapPipelineOrResolveTheOwner() throws Exception {
        Files.writeString(this.directory.resolve("config.yml"), """
                config-version: "%s"
                synchronization:
                  map:
                    enabled: false
                """.formatted(DependencyVersions.CONFIG_VERSION));
        this.config().reload();
        MapSyncService service = new MapSyncService(null);
        service.onDelayedEnable();
        assertNull(service.mode());
        SnapshotMeta meta = new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 1L, SaveCause.DISCONNECT, false, "A", 0);
        Snapshot snapshot = new Snapshot(meta, Map.of());
        // 启动时未创建地图服务, 保存和加载沿用原始物品快照.
        CompletableFuture<?> result = service.decodeAsync(snapshot);
        service.stopReceiving();
        service.finishPublishing(0, java.util.concurrent.TimeUnit.NANOSECONDS);
        assertSame(snapshot, result.join());
    }

    private PluginConfig config() {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "dataFolderPath" -> this.directory;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return new PluginConfig(plugin, SparrowYaml.builder().setAllowDuplicateKeys(false).setAllowObjectKeys(false).build());
    }
}

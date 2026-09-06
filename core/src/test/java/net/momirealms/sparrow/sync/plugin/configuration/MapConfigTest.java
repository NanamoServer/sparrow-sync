package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.session.SnapshotService;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.yaml.SparrowYaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
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
        assertFalse(options.enabled());
        assertEquals(MapType.HIDE, options.type());
        assertEquals("${server-id}-${world-uuid}", options.mapOwnerId());
        assertEquals("A-" + worldUuid, options.resolveOwnerId("A", worldUuid));
        assertNotEquals(options.resolveOwnerId("A", worldUuid), options.resolveOwnerId("A", UUID.randomUUID()));
        String yaml = Files.readString(this.directory.resolve("config.yml"));
        assertTrue(yaml.contains("map:"), yaml);
        assertTrue(yaml.contains("enabled: false"), yaml);
        assertTrue(yaml.contains("type: HIDE"), yaml);
        assertTrue(yaml.contains("map-owner-id:"), yaml);
        assertTrue(yaml.contains("${server-id}-${world-uuid}"), yaml);
    }

    @Test
    void reloadUsesTheSwitchAndCustomOwnerTemplate() throws Exception {
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, """
                config-version: "%s"
                synchronization:
                  map:
                    enabled: true
                    type: HIDE
                    map-owner-id: "maps/${world-uuid}/${server-id}"
                """.formatted(DependencyVersions.CONFIG_VERSION));
        PluginConfig config = this.config();
        config.reload();
        PluginConfig.MapOptions first = PluginConfig.synchronization$map();
        UUID worldUuid = UUID.randomUUID();
        assertEquals(MapType.HIDE, first.type());
        assertTrue(first.enabled());
        assertEquals("maps/" + worldUuid + "/A", first.resolveOwnerId("A", worldUuid));

        Files.writeString(file, """
                config-version: "%s"
                synchronization:
                  map:
                    enabled: false
                    type: HIDE
                    map-owner-id: "fixed-owner"
                """.formatted(DependencyVersions.CONFIG_VERSION));
        config.reload();
        PluginConfig.MapOptions second = PluginConfig.synchronization$map();
        assertFalse(second.enabled());
        assertEquals(MapType.HIDE, second.type());
        assertEquals("fixed-owner", second.resolveOwnerId("B", worldUuid));
        assertTrue(first.enabled());
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
        this.config().reload();
        SnapshotService service = new SnapshotService(null);
        SnapshotMeta meta = new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 1L, SaveCause.DISCONNECT, false, "A", 0);
        Snapshot snapshot = new Snapshot(meta, Map.of());
        // 地图服务和主世界 UUID 都未装配, 关闭开关直接返回原快照.
        Method prepare = SnapshotService.class.getDeclaredMethod("prepareMaps", Snapshot.class);
        prepare.setAccessible(true);
        CompletableFuture<?> result = (CompletableFuture<?>) prepare.invoke(service, snapshot);
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

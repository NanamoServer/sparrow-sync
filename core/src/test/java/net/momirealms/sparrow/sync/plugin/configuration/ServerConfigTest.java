package net.momirealms.sparrow.sync.plugin.configuration;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.stats.Stat;
import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType.NativeApplyResult;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.AdvancementValue;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.Advancements;
import net.momirealms.sparrow.sync.snapshot.data.type.StatisticsDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.StatisticsDataType.Statistics;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.route.Route;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void createsDefaultServerOptions() throws Exception {
        SparrowYaml yaml = newYaml();

        new ServerConfig(this.plugin(), yaml).reload();

        assertEquals("", ServerConfig.serverId());
        assertEquals("main", ServerConfig.clusterId());
        YamlDocument document = yaml.load(this.directory.resolve("server.yml"));
        assertEquals(DependencyVersions.CONFIG_VERSION, document.getString(Route.from("config-version")));
        assertTrue(document.getBoolean(Route.from("native-json")));
        String generated = Files.readString(this.directory.resolve("server.yml"), StandardCharsets.UTF_8);
        assertTrue(generated.contains("Disable this when the server reads these records from a non-standard data source"), generated);
    }

    @Test
    void reloadPublishesNativeJsonForLaterLoginPreparations() throws Exception {
        Path file = this.directory.resolve("server.yml");
        Files.writeString(file, """
                config-version: "%s"
                server-id: "test"
                native-json: false
                """.formatted(DependencyVersions.CONFIG_VERSION), StandardCharsets.UTF_8);
        ServerConfig serverConfig = new ServerConfig(this.plugin(), newYaml());

        serverConfig.reload();
        UUID player = UUID.randomUUID();
        CompoundTag playerData = new CompoundTag();
        assertEquals(NativeApplyResult.NOT_APPLIED, new StatisticsDataType().applyNative(player, playerData, new Statistics(new Stat<?>[0], new int[0])));
        assertEquals(NativeApplyResult.NOT_APPLIED, new AdvancementsDataType().applyNative(player, playerData, new Advancements(new AdvancementValue[0])));

        Files.writeString(file, """
                config-version: "%s"
                server-id: "test"
                native-json: true
                """.formatted(DependencyVersions.CONFIG_VERSION), StandardCharsets.UTF_8);
        serverConfig.reload();
    }

    @Test
    void upgradesVersionTenWithNativeJsonEnabled() throws Exception {
        Path file = this.directory.resolve("server.yml");
        Files.writeString(file, """
                config-version: "10"
                server-id: "test"
                cluster-id: "network"
                """, StandardCharsets.UTF_8);
        SparrowYaml yaml = newYaml();

        new ServerConfig(this.plugin(), yaml).reload();

        assertEquals("test", ServerConfig.serverId());
        assertEquals("network", ServerConfig.clusterId());
        YamlDocument upgraded = yaml.load(file);
        assertEquals(DependencyVersions.CONFIG_VERSION, upgraded.getString(Route.from("config-version")));
        assertTrue(upgraded.getBoolean(Route.from("native-json")));
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

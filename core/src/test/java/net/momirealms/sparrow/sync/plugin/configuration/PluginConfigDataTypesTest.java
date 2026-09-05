package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.AttributeOptions;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.DataTypes;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.route.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginConfigDataTypesTest {
    private static final List<String> ATTRIBUTE_WHITELIST = List.of(
            "minecraft:generic.max_health", "minecraft:max_health",
            "minecraft:generic.max_absorption", "minecraft:max_absorption",
            "minecraft:generic.luck", "minecraft:luck",
            "minecraft:generic.scale", "minecraft:scale",
            "minecraft:generic.step_height", "minecraft:step_height",
            "minecraft:generic.gravity", "minecraft:gravity"
    );
    private static final List<String> MODIFIER_BLACKLIST = List.of(
            "minecraft:effect.*", "minecraft:creative_mode_*"
    );

    @TempDir
    Path directory;

    @Test
    void createsDefaultDataTypeAndAttributeSections() throws Exception {
        SparrowYaml yaml = newYaml();
        new PluginConfig(this.plugin(), yaml).reload();

        assertTrue(PluginConfig.synchronization$nativeAsyncApply().playerData());
        assertTrue(PluginConfig.synchronization$advancements().keepUnknownAdvancements());
        assertTrue(PluginConfig.synchronization$advancements().injectProgressChanged());
        assertTrue(PluginConfig.synchronization$attributes().injectConsumer());
        assertDefaultDataTypes(PluginConfig.synchronization$dataTypes());
        AttributeOptions attributes = PluginConfig.synchronization$attributes();
        assertEquals(ATTRIBUTE_WHITELIST, attributes.whitelist());
        assertEquals(MODIFIER_BLACKLIST, attributes.modifierBlacklist());
        assertTrue(attributes.attributeAllowed("minecraft:generic.max_health"));
        assertTrue(attributes.attributeAllowed("minecraft:max_health"));
        assertTrue(attributes.attributeAllowed("max_health"));
        assertFalse(attributes.attributeAllowed("minecraft:movement_speed"));
        assertTrue(attributes.modifierBlacklisted("minecraft:effect.speed"));
        assertTrue(attributes.modifierBlacklisted("minecraft:creative_mode_block_range"));
        assertFalse(attributes.modifierBlacklisted("minecraft:base_attack_damage"));

        Path file = this.directory.resolve("config.yml");
        YamlDocument document = yaml.load(file);
        assertEquals(DependencyVersions.CONFIG_VERSION, document.getString(Route.from("config-version")));
        assertTrue(document.getBoolean(Route.from("synchronization", "native-async-apply", "player-data")));
        assertTrue(document.getBoolean(Route.from("synchronization", "advancements", "keep-unknown-advancements")));
        assertTrue(document.getBoolean(Route.from("synchronization", "advancements", "inject-progress-changed")));
        assertTrue(document.getBoolean(Route.from("synchronization", "attributes", "inject-consumer")));
        assertDefaultDataTypeDocument(document);
        assertEquals(ATTRIBUTE_WHITELIST, document.getList(String.class, Route.from("synchronization", "attributes", "whitelist")));
        assertEquals(MODIFIER_BLACKLIST, document.getList(String.class, Route.from("synchronization", "attributes", "modifier-blacklist")));
        String generated = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(generated.contains("Read once during startup; changes require a server restart"), generated);
        assertTrue(generated.contains("Reloading applies this option to login preparations started afterwards"), generated);
        assertTrue(generated.contains("Keeps advancement progress for IDs the applying server does not register"), generated);
        assertTrue(generated.contains("Reloading the plugin applies attribute filters to later captures and applications"), generated);
    }

    @Test
    void reloadPublishesLoginApplyOptionsForLaterPreparations() throws Exception {
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, """
                config-version: "%s"
                synchronization:
                  native-async-apply:
                    player-data: false
                  advancements:
                    keep-unknown-advancements: false
                    inject-progress-changed: false
                  attributes:
                    inject-consumer: false
                """.formatted(DependencyVersions.CONFIG_VERSION), StandardCharsets.UTF_8);
        PluginConfig pluginConfig = new PluginConfig(this.plugin(), newYaml());

        pluginConfig.reload();
        assertFalse(PluginConfig.synchronization$nativeAsyncApply().playerData());
        assertFalse(PluginConfig.synchronization$advancements().keepUnknownAdvancements());
        assertFalse(PluginConfig.synchronization$advancements().injectProgressChanged());
        assertFalse(PluginConfig.synchronization$attributes().injectConsumer());

        Files.writeString(file, """
                config-version: "%s"
                synchronization:
                  native-async-apply:
                    player-data: true
                  advancements:
                    keep-unknown-advancements: true
                    inject-progress-changed: true
                  attributes:
                    inject-consumer: true
                """.formatted(DependencyVersions.CONFIG_VERSION), StandardCharsets.UTF_8);
        pluginConfig.reload();

        assertTrue(PluginConfig.synchronization$nativeAsyncApply().playerData());
        assertTrue(PluginConfig.synchronization$advancements().keepUnknownAdvancements());
        assertTrue(PluginConfig.synchronization$advancements().injectProgressChanged());
        assertTrue(PluginConfig.synchronization$attributes().injectConsumer());
    }

    @Test
    void reloadPublishesNewAttributeFilterSnapshot() throws Exception {
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, """
                config-version: "%s"
                synchronization:
                  attributes:
                    whitelist: ["minecraft:max_health"]
                    modifier-blacklist: ["minecraft:effect.*"]
                """.formatted(DependencyVersions.CONFIG_VERSION), StandardCharsets.UTF_8);
        PluginConfig pluginConfig = new PluginConfig(this.plugin(), newYaml());

        pluginConfig.reload();

        AttributeOptions first = PluginConfig.synchronization$attributes();
        assertTrue(first.attributeAllowed("minecraft:max_health"));
        assertFalse(first.attributeAllowed("minecraft:luck"));
        assertTrue(first.modifierBlacklisted("minecraft:effect.speed"));

        Files.writeString(file, """
                config-version: "%s"
                synchronization:
                  attributes:
                    whitelist: ["minecraft:luck"]
                    modifier-blacklist: ["minecraft:creative_mode_*"]
                """.formatted(DependencyVersions.CONFIG_VERSION), StandardCharsets.UTF_8);

        pluginConfig.reload();

        AttributeOptions second = PluginConfig.synchronization$attributes();
        assertNotSame(first, second);
        assertFalse(second.attributeAllowed("minecraft:max_health"));
        assertTrue(second.attributeAllowed("minecraft:luck"));
        assertTrue(second.modifierBlacklisted("minecraft:creative_mode_block_range"));
        assertTrue(first.attributeAllowed("minecraft:max_health"));
    }

    @Test
    void upgradesVersionFourAndWritesMissingDataTypeDefaults() throws Exception {
        String original = """
                config-version: "4"
                metrics: false
                synchronization:
                  worker-threads: 8
                """;
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, original, StandardCharsets.UTF_8);
        SparrowYaml yaml = newYaml();

        new PluginConfig(this.plugin(), yaml).reload();

        assertFalse(PluginConfig.metrics());
        assertEquals(8, PluginConfig.synchronization$workerThreads());
        assertTrue(PluginConfig.synchronization$nativeAsyncApply().playerData());
        assertTrue(PluginConfig.synchronization$advancements().keepUnknownAdvancements());
        assertTrue(PluginConfig.synchronization$advancements().injectProgressChanged());
        assertTrue(PluginConfig.synchronization$attributes().injectConsumer());
        assertDefaultDataTypes(PluginConfig.synchronization$dataTypes());
        YamlDocument upgraded = yaml.load(file);
        assertEquals(DependencyVersions.CONFIG_VERSION, upgraded.getString(Route.from("config-version")));
        assertTrue(upgraded.getBoolean(Route.from("synchronization", "native-async-apply", "player-data")));
        assertTrue(upgraded.getBoolean(Route.from("synchronization", "advancements", "keep-unknown-advancements")));
        assertTrue(upgraded.getBoolean(Route.from("synchronization", "advancements", "inject-progress-changed")));
        assertTrue(upgraded.getBoolean(Route.from("synchronization", "attributes", "inject-consumer")));
        assertDefaultDataTypeDocument(upgraded);
        assertEquals(ATTRIBUTE_WHITELIST, upgraded.getList(String.class, Route.from("synchronization", "attributes", "whitelist")));
        assertEquals(MODIFIER_BLACKLIST, upgraded.getList(String.class, Route.from("synchronization", "attributes", "modifier-blacklist")));

        List<Path> backups;
        try (Stream<Path> paths = Files.list(this.directory)) {
            backups = paths.filter(path -> path.getFileName().toString().startsWith("config.yml.bak.")).toList();
        }
        assertEquals(1, backups.size());
        assertEquals(original, Files.readString(backups.getFirst(), StandardCharsets.UTF_8));
    }

    @Test
    void upgradesVersionSevenWithNativeApplyEnabled() throws Exception {
        String original = """
                config-version: "7"
                synchronization:
                  worker-threads: 8
                """;
        Path file = this.directory.resolve("config.yml");
        Files.writeString(file, original, StandardCharsets.UTF_8);
        SparrowYaml yaml = newYaml();

        new PluginConfig(this.plugin(), yaml).reload();

        assertTrue(PluginConfig.synchronization$nativeAsyncApply().playerData());
        assertTrue(PluginConfig.synchronization$advancements().keepUnknownAdvancements());
        assertTrue(PluginConfig.synchronization$advancements().injectProgressChanged());
        assertTrue(PluginConfig.synchronization$attributes().injectConsumer());
        YamlDocument upgraded = yaml.load(file);
        assertEquals(DependencyVersions.CONFIG_VERSION, upgraded.getString(Route.from("config-version")));
        assertTrue(upgraded.getBoolean(Route.from("synchronization", "native-async-apply", "player-data")));
        assertTrue(upgraded.getBoolean(Route.from("synchronization", "advancements", "keep-unknown-advancements")));
        assertTrue(upgraded.getBoolean(Route.from("synchronization", "advancements", "inject-progress-changed")));
        assertTrue(upgraded.getBoolean(Route.from("synchronization", "attributes", "inject-consumer")));
    }

    private static void assertDefaultDataTypes(DataTypes types) {
        assertTrue(types.inventory());
        assertTrue(types.enderChest());
        assertTrue(types.persistentData());
        assertTrue(types.experience());
        assertTrue(types.health());
        assertTrue(types.hunger());
        assertTrue(types.gameMode());
        assertTrue(types.potionEffects());
        assertTrue(types.advancements());
        assertTrue(types.statistics());
        assertTrue(types.attributes());
        assertFalse(types.location());
        assertTrue(types.flightStatus());
        assertTrue(types.enchantmentSeed());
    }

    private static void assertDefaultDataTypeDocument(YamlDocument document) {
        assertTrue(document.getBoolean(Route.from("synchronization", "data-types", "inventory")));
        assertTrue(document.getBoolean(Route.from("synchronization", "data-types", "ender-chest")));
        assertTrue(document.getBoolean(Route.from("synchronization", "data-types", "persistent-data")));
        assertTrue(document.getBoolean(Route.from("synchronization", "data-types", "experience")));
        assertTrue(document.getBoolean(Route.from("synchronization", "data-types", "health")));
        assertTrue(document.getBoolean(Route.from("synchronization", "data-types", "hunger")));
        assertTrue(document.getBoolean(Route.from("synchronization", "data-types", "game-mode")));
        assertTrue(document.getBoolean(Route.from("synchronization", "data-types", "potion-effects")));
        assertTrue(document.getBoolean(Route.from("synchronization", "data-types", "advancements")));
        assertTrue(document.getBoolean(Route.from("synchronization", "data-types", "statistics")));
        assertTrue(document.getBoolean(Route.from("synchronization", "data-types", "attributes")));
        assertFalse(document.getBoolean(Route.from("synchronization", "data-types", "location")));
        assertTrue(document.getBoolean(Route.from("synchronization", "data-types", "flight-status")));
        assertTrue(document.getBoolean(Route.from("synchronization", "data-types", "enchantment-seed")));
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

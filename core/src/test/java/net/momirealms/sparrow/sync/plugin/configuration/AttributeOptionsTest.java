package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.AttributeOptions;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.yaml.SparrowYaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeOptionsTest {
    @TempDir
    Path directory;

    @Test
    void compiledFiltersPreserveLiteralPrefixAndWildcardSemantics() throws Exception {
        List<String> patterns = List.of("max_health", "minecraft:effect.*", "*:custom_*_bonus", "mine*:*speed*", "a**b*c", "*", "*:*", "", "example:", "*foo*");
        List<String> keys = List.of("max_health", "minecraft:max_health", "minecraft:effect.speed", "minecraft:effect.", "example:custom_boost_bonus", "minecraft:movement_speed", "abc", "example:max_health", "", "example:", "foo", "example:foo");
        for (String pattern : patterns) {
            AttributeOptions options = options(List.of(pattern), List.of(pattern));
            for (String key : keys) {
                boolean expected = legacyMatch(pattern, key);
                assertEquals(expected, options.attributeAllowed(key), pattern + " / " + key);
                assertEquals(expected, options.modifierBlacklisted(key), pattern + " / " + key);
            }
        }
    }

    @Test
    void compiledFiltersMatchLegacyAlgorithmForRandomPatterns() throws Exception {
        Random random = new Random(1783);
        for (int i = 0; i < 500; i++) {
            String pattern = randomString(random, "abc:*._", 12);
            AttributeOptions options = options(List.of(pattern), List.of(pattern));
            for (int j = 0; j < 40; j++) {
                String key = randomString(random, "abc:._", 16);
                assertEquals(legacyMatch(pattern, key), options.attributeAllowed(key), pattern + " / " + key);
            }
        }
    }

    @Test
    void emptyListsMatchNothingAndFrozenListsAreImmutable() throws Exception {
        AttributeOptions options = options(List.of(), List.of());
        assertFalse(options.attributeAllowed("minecraft:max_health"));
        assertFalse(options.modifierBlacklisted("minecraft:effect.speed"));
        assertThrows(UnsupportedOperationException.class, () -> options.whitelist().add("*"));
        assertThrows(UnsupportedOperationException.class, () -> options.modifierBlacklist().add("*"));
    }

    @Test
    void reloadPublishesCompiledFiltersAndKeepsOldSnapshotStable() throws Exception {
        Field field = PluginConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        Object previous = field.get(null);
        try {
            Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                case "dataFolderPath" -> this.directory;
                default -> throw new AssertionError(method.getName());
            });
            PluginConfig config = new PluginConfig(plugin, SparrowYaml.builder().build());
            this.writeConfig("max_health", "minecraft:effect.*");
            config.reload();
            AttributeOptions first = PluginConfig.synchronization$attributes();
            this.writeConfig("luck", "example:local_*");
            config.reload();
            AttributeOptions second = PluginConfig.synchronization$attributes();

            assertNotSame(first, second);
            assertTrue(first.attributeAllowed("minecraft:max_health"));
            assertFalse(first.attributeAllowed("minecraft:luck"));
            assertTrue(first.modifierBlacklisted("minecraft:effect.speed"));
            assertTrue(second.attributeAllowed("minecraft:luck"));
            assertFalse(second.attributeAllowed("minecraft:max_health"));
            assertTrue(second.modifierBlacklisted("example:local_bonus"));
            assertFalse(second.modifierBlacklisted("minecraft:effect.speed"));
            String saved = Files.readString(this.directory.resolve("config.yml"));
            assertFalse(saved.contains("attribute-patterns"));
            assertFalse(saved.contains("modifier-patterns"));
        } finally {
            field.set(null, previous);
        }
    }

    private void writeConfig(String whitelist, String blacklist) throws Exception {
        Files.writeString(this.directory.resolve("config.yml"), """
                config-version: "%s"
                synchronization:
                  attributes:
                    whitelist: ["%s"]
                    modifier-blacklist: ["%s"]
                """.formatted(DependencyVersions.CONFIG_VERSION, whitelist, blacklist));
    }

    private static AttributeOptions options(List<String> whitelist, List<String> blacklist) throws Exception {
        AttributeOptions options = new AttributeOptions();
        options.whitelist = whitelist;
        options.modifierBlacklist = blacklist;
        Method freeze = AttributeOptions.class.getDeclaredMethod("freeze");
        freeze.setAccessible(true);
        freeze.invoke(options);
        return options;
    }

    private static String randomString(Random random, String alphabet, int maxLength) {
        StringBuilder value = new StringBuilder();
        int length = random.nextInt(maxLength + 1);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    private static boolean legacyMatch(String pattern, String value) {
        pattern = pattern.indexOf(':') < 0 ? "minecraft:" + pattern : pattern;
        value = value.indexOf(':') < 0 ? "minecraft:" + value : value;
        int patternIndex = 0;
        int valueIndex = 0;
        int wildcardIndex = -1;
        int retryIndex = -1;
        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == value.charAt(valueIndex)) {
                patternIndex++;
                valueIndex++;
                continue;
            }
            if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                wildcardIndex = patternIndex++;
                retryIndex = valueIndex;
                continue;
            }
            if (wildcardIndex < 0) return false;
            patternIndex = wildcardIndex + 1;
            valueIndex = ++retryIndex;
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }
}

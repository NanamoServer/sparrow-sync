package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.AttributeOptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AttributeOptionsTest {
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

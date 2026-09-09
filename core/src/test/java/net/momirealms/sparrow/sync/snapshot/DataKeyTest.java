package net.momirealms.sparrow.sync.snapshot;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DataKeyTest {
    @Test
    void factoriesAndAccessorsPreserveKeyFormat() {
        DataKey key = new DataKey("custom", "inventory");
        assertFalse(DataKey.class.isRecord());
        assertEquals("custom", key.namespace());
        assertEquals("inventory", key.value());
        assertEquals("custom:inventory", key.asString());
        assertEquals(key.asString(), key.toString());
        assertEquals(key, DataKey.of("custom", "inventory"));
        assertEquals(key, DataKey.parse("custom:inventory"));
        assertEquals(DataKey.sparrow("inventory"), DataKey.parse("inventory"));
    }

    @Test
    void equalKeysCanRetrieveMapEntries() {
        DataKey key = DataKey.of("custom", "inventory");
        DataKey equal = DataKey.parse("custom:inventory");
        Map<DataKey, String> values = new HashMap<>();
        values.put(key, "snapshot");
        assertEquals("snapshot", values.get(equal));
        assertEquals(key, equal);
        assertEquals(equal, key);
        assertEquals(key.hashCode(), equal.hashCode());
        assertNotEquals(key, DataKey.of("other", "inventory"));
        assertNotEquals(key, DataKey.of("custom", "other"));
        assertNotEquals(key, null);
        assertNotEquals(key, "custom:inventory");
    }

    @Test
    void orderingUsesNamespaceThenValueAndDeduplicatesEqualKeys() {
        TreeSet<DataKey> keys = new TreeSet<>();
        keys.add(DataKey.of("b", "a"));
        keys.add(DataKey.of("a", "b"));
        keys.add(DataKey.of("a", "a"));
        keys.add(DataKey.parse("a:a"));
        assertEquals(3, keys.size());
        assertEquals(DataKey.of("a", "a"), keys.pollFirst());
        assertEquals(DataKey.of("a", "b"), keys.pollFirst());
        assertEquals(DataKey.of("b", "a"), keys.pollFirst());
    }
}

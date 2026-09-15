package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.junit.jupiter.api.Test;

import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataKeyTest {
    @Test
    void factoriesAndAccessorsPreserveKeyFormat() {
        DataKey key = new DataKey("custom", "inventory");
        assertEquals("custom", key.namespace());
        assertEquals("inventory", key.value());
        assertEquals("custom:inventory", key.asString());
        assertEquals(key, DataKey.of("custom", "inventory"));
        assertEquals(key, DataKey.parse("custom:inventory"));
        assertEquals(DataKey.sparrow("inventory"), DataKey.parse("inventory"));
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

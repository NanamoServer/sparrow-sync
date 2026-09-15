package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.test.PluginConfigExtension;
import net.momirealms.sparrow.sync.test.StubPlayerDataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(PluginConfigExtension.class)
class DataRegistryTest {
    private static final DataKey A = DataKey.of("sparrow", "alpha");
    private static final DataKey B = DataKey.of("sparrow", "bravo");
    private static final DataKey C = DataKey.of("sparrow", "charlie");

    @Test
    void unknownDropsOnlyApplyToUnregisteredKeys() {
        DataRegistry registry = new DataRegistry();
        assertFalse(registry.shouldDropUnknown(A));
        registry.registerUnknownDrop(A);
        registry.registerUnknownDrop(A);
        registry.registerUnknownDrop(B);
        assertTrue(registry.shouldDropUnknown(A));
        registry.register(new StubPlayerDataType(A));
        registry.freeze();
        assertFalse(registry.shouldDropUnknown(A));
        assertTrue(registry.shouldDropUnknown(B));
        assertFalse(registry.shouldDropUnknown(C));
        assertThrows(IllegalStateException.class, () -> registry.registerUnknownDrop(B));
        assertThrows(IllegalStateException.class, () -> registry.registerUnknownDrop(C));
    }

    @Test
    void applyOrderPutsDependencyBeforeDependent() {
        DataRegistry registry = new DataRegistry();
        registry.register(new StubPlayerDataType(C, false, Set.of(B)));
        registry.register(new StubPlayerDataType(B, false, Set.of(A)));
        registry.register(new StubPlayerDataType(A));
        assertEquals(List.of(A, B, C), registry.applyOrder());
    }

    @Test
    void applyOrderIsDeterministicRegardlessOfRegistrationOrder() {
        DataRegistry first = new DataRegistry();
        first.register(new StubPlayerDataType(C));
        first.register(new StubPlayerDataType(A));
        first.register(new StubPlayerDataType(B));
        DataRegistry second = new DataRegistry();
        second.register(new StubPlayerDataType(B));
        second.register(new StubPlayerDataType(A));
        second.register(new StubPlayerDataType(C));
        assertEquals(List.of(A, B, C), first.applyOrder());
        assertEquals(first.applyOrder(), second.applyOrder());
    }

    @Test
    void applyOrderFailsFastOnThreeNodeCycle() {
        DataRegistry registry = new DataRegistry();
        registry.register(new StubPlayerDataType(A, false, Set.of(C)));
        registry.register(new StubPlayerDataType(B, false, Set.of(A)));
        registry.register(new StubPlayerDataType(C, false, Set.of(B)));
        IllegalStateException exception = assertThrows(IllegalStateException.class, registry::applyOrder);
        assertTrue(exception.getMessage().contains("sparrow:alpha -> sparrow:charlie -> sparrow:bravo -> sparrow:alpha"));
    }

    @Test
    void cycleReportNamesOnlyCycleNodes() {
        DataKey delta = DataKey.of("sparrow", "delta");
        DataRegistry registry = new DataRegistry();
        registry.register(new StubPlayerDataType(A, false, Set.of(C)));
        registry.register(new StubPlayerDataType(B, false, Set.of(A)));
        registry.register(new StubPlayerDataType(C, false, Set.of(B)));
        registry.register(new StubPlayerDataType(delta, false, Set.of(A)));
        IllegalStateException exception = assertThrows(IllegalStateException.class, registry::applyOrder);
        assertTrue(exception.getMessage().contains(A.asString()));
        assertTrue(exception.getMessage().contains(B.asString()));
        assertTrue(exception.getMessage().contains(C.asString()));
        assertFalse(exception.getMessage().contains(delta.asString()));
    }

    @Test
    void applyOrderIgnoresUnregisteredDependencies() {
        DataRegistry registry = new DataRegistry();
        registry.register(new StubPlayerDataType(B, false, Set.of(DataKey.of("sparrow", "missing"))));
        registry.register(new StubPlayerDataType(A));
        assertEquals(List.of(A, B), registry.applyOrder());
    }

    @Test
    void registerRejectsDuplicateKey() {
        DataRegistry registry = new DataRegistry();
        registry.register(new StubPlayerDataType(A));
        assertThrows(IllegalStateException.class, () -> registry.register(new StubPlayerDataType(A)));
    }

    @Test
    void frozenRegistryRejectsRegistration() {
        DataRegistry registry = new DataRegistry();
        registry.register(new StubPlayerDataType(A));
        registry.freeze();

        assertThrows(IllegalStateException.class, () -> registry.register(new StubPlayerDataType(B)));
        assertTrue(registry.frozen());
        assertTrue(registry.registered(A));
    }

    @Test
    void freezeCompilesTopologicalSlotsOnce() {
        DataRegistry registry = new DataRegistry();
        StubPlayerDataType bravo = new StubPlayerDataType(B, false, Set.of(A));
        StubPlayerDataType alpha = new StubPlayerDataType(A);
        registry.register(bravo);
        registry.register(alpha);

        registry.freeze();

        assertEquals(2, registry.size());
        assertEquals(A, registry.keyAt(0));
        assertEquals(B, registry.keyAt(1));
        assertSame(alpha, registry.typeAt(0));
        assertSame(bravo, registry.typeAt(1));
        assertEquals(0, registry.slot(A));
        assertEquals(1, registry.slot(B));
        assertEquals(-1, registry.slot(C));
        assertSame(registry.applyOrder(), registry.applyOrder());
    }

    @Test
    void failedFreezeDoesNotPublishPartialLayout() {
        DataRegistry registry = new DataRegistry();
        registry.register(new StubPlayerDataType(A, false, Set.of(B)));
        registry.register(new StubPlayerDataType(B, false, Set.of(A)));

        assertThrows(IllegalStateException.class, registry::freeze);

        assertFalse(registry.frozen());
        assertEquals(-1, registry.slot(A));
    }
}

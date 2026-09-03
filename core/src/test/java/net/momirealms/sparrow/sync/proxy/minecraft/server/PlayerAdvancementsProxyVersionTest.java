package net.momirealms.sparrow.sync.proxy.minecraft.server;

import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.CriterionListenerProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.CriterionProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.CriterionProxy;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerAdvancementsProxyVersionTest {

    @Test
    void listenerTablesUseVersionedFields() throws ReflectiveOperationException {
        Annotation legacy = annotation(PlayerAdvancementsProxy.class.getDeclaredMethod("getCriterionData", Object.class), "FieldGetter");
        Annotation current = annotation(PlayerAdvancementsProxy.class.getDeclaredMethod("getActiveTriggers", Object.class), "FieldGetter");

        assertArrayEquals(new String[]{"criterionData"}, (String[]) legacy.annotationType().getMethod("name").invoke(legacy));
        assertEquals("max_version=26.1.2", legacy.annotationType().getMethod("activeIf").invoke(legacy));
        assertArrayEquals(new String[]{"activeTriggers"}, (String[]) current.annotationType().getMethod("name").invoke(current));
        assertEquals("min_version=26.2", current.annotationType().getMethod("activeIf").invoke(current));
    }

    @Test
    void fullListenerRebuildMethodsAreNotExposed() {
        assertThrows(NoSuchMethodException.class, () -> PlayerAdvancementsProxy.class.getDeclaredMethod("clearTriggers", Object.class));
        assertThrows(NoSuchMethodException.class, () -> PlayerAdvancementsProxy.class.getDeclaredMethod("registerListeners", Object.class, Object.class));
    }

    @Test
    void version1218BindsDeltaApplyProxies() {
        BukkitProxy.init("1.21.8", List.of("paper"));

        assertNotNull(PlayerAdvancementsProxy.INSTANCE);
        assertNotNull(CriterionProgressProxy.INSTANCE);
        assertNotNull(CriterionProxy.INSTANCE);
        assertNotNull(CriterionListenerProxy.INSTANCE);
    }

    @Test
    void legacyListenerTokenCanBeRecreatedForRemoval() {
        BukkitProxy.init("1.21.8", List.of("paper"));

        Object first = CriterionListenerProxy.INSTANCE.newInstance(null, null, "criterion");
        Object second = CriterionListenerProxy.INSTANCE.newInstance(null, null, "criterion");

        assertEquals(first, second);
    }

    private static Annotation annotation(Method method, String name) {
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            if (annotation.annotationType().getSimpleName().equals(name)) {
                return annotation;
            }
        }
        throw new IllegalStateException("missing " + name + " on " + method.getName());
    }
}

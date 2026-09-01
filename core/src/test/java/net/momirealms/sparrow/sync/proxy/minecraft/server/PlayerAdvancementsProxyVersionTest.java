package net.momirealms.sparrow.sync.proxy.minecraft.server;

import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.proxy.MinecraftPredicate;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerAdvancementsProxyVersionTest {

    @Test
    void listenerBindingsCoverSupportedVersionBoundary() throws ReflectiveOperationException {
        assertTrue(active("1.21.4", "stopListening"));
        assertFalse(active("1.21.4", "clearTriggers"));
        assertTrue(active("26.1.2", "stopListening"));
        assertFalse(active("26.1.2", "clearTriggers"));
        assertFalse(active("26.2", "stopListening"));
        assertTrue(active("26.2", "clearTriggers"));
    }

    @Test
    void version1218BindsProxyWithoutResolvingClearTriggers() {
        BukkitProxy.init("1.21.8", List.of("paper"));

        assertNotNull(PlayerAdvancementsProxy.INSTANCE);
    }

    private static boolean active(String version, String methodName) throws ReflectiveOperationException {
        Method method = PlayerAdvancementsProxy.class.getDeclaredMethod(methodName, Object.class);
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            if (annotation.annotationType().getSimpleName().equals("MethodInvoker")) {
                String expression = (String) annotation.annotationType().getMethod("activeIf").invoke(annotation);
                return new MinecraftPredicate(version, List.of("paper")).test(expression);
            }
        }
        throw new IllegalStateException("missing MethodInvoker on " + methodName);
    }
}

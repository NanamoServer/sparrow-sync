package net.momirealms.sparrow.sync.proxy.minecraft.server;

import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlayerAdvancementsProxyVersionTest {

    @Test
    void listenerBindingUsesRenamedMethodCandidates() throws ReflectiveOperationException {
        Method method = PlayerAdvancementsProxy.class.getDeclaredMethod("clearTriggers", Object.class);
        Annotation invoker = invoker(method);

        assertArrayEquals(new String[]{"clearTriggers", "stopListening"}, (String[]) invoker.annotationType().getMethod("name").invoke(invoker));
        assertEquals("", invoker.annotationType().getMethod("activeIf").invoke(invoker));
    }

    @Test
    void version1218BindsProxyThroughStopListeningCandidate() {
        BukkitProxy.init("1.21.8", List.of("paper"));

        assertNotNull(PlayerAdvancementsProxy.INSTANCE);
    }

    private static Annotation invoker(Method method) {
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            if (annotation.annotationType().getSimpleName().equals("MethodInvoker")) {
                return annotation;
            }
        }
        throw new IllegalStateException("missing MethodInvoker on " + method.getName());
    }
}

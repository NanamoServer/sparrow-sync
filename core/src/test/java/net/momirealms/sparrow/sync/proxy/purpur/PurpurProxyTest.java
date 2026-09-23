package net.momirealms.sparrow.sync.proxy.purpur;

import net.momirealms.sparrow.sync.proxy.MinecraftPredicate;
import net.momirealms.sparrow.sync.proxy.minecraft.server.level.ServerPlayerProxy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PurpurProxyTest {
    @ParameterizedTest
    @ValueSource(strings = {"1.21.4", "1.21.8", "26.2"})
    void bindingsRequirePurpurPatch(String version) throws Exception {
        assertPurpurCondition(version, BossBarTaskProxy.class, "ReflectionProxy");
        for (String name : List.of("tpsBar", "compassBar", "ramBar")) {
            assertPurpurCondition(version, ServerPlayerProxy.class.getDeclaredMethod(name, Object.class), "MethodInvoker");
            assertPurpurCondition(version, ServerPlayerProxy.class.getDeclaredMethod(name, Object.class, boolean.class), "MethodInvoker");
        }
    }

    private static void assertPurpurCondition(String version, AnnotatedElement element, String annotationName) throws Exception {
        Annotation binding = List.of(element.getDeclaredAnnotations()).stream()
                .filter(annotation -> annotation.annotationType().getSimpleName().equals(annotationName))
                .findFirst().orElseThrow();
        String condition = (String) binding.annotationType().getMethod("activeIf").invoke(binding);
        assertFalse(new MinecraftPredicate(version, List.of("paper", "folia")).test(condition));
        assertTrue(new MinecraftPredicate(version, List.of("paper", "purpur")).test(condition));
    }
}

package net.momirealms.sparrow.sync.proxy.minecraft;

import net.momirealms.sparrow.sync.proxy.MinecraftPredicate;
import net.momirealms.sparrow.sync.proxy.minecraft.world.entity.ai.attributes.AttributeInstanceProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.entity.player.InventoryProxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CaptureProxyVersionTest {
    @ParameterizedTest
    @ValueSource(strings = {"1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11", "26.1.2", "26.2"})
    void proxiesCoverCaptureVersionsWithoutPaperOnlyMembers(String version) throws Exception {
        for (Class<?> type : List.of(InventoryProxy.class, AttributeInstanceProxy.class)) {
            Annotation proxy = annotation(type, "ReflectionProxy");
            String activeIf = (String) proxy.annotationType().getMethod("activeIf").invoke(proxy);
            assertTrue(new MinecraftPredicate(version, List.of()).test(activeIf));
            assertTrue(new MinecraftPredicate(version, List.of("paper", "folia")).test(activeIf));
            assertFalse(new MinecraftPredicate("1.21.3", List.of("paper")).test(activeIf));
        }
    }

    private static Annotation annotation(AnnotatedElement element, String name) {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            if (annotation.annotationType().getSimpleName().equals(name)) return annotation;
        }
        throw new AssertionError("missing " + name);
    }
}

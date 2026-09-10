package net.momirealms.sparrow.sync.proxy.minecraft.network;

import net.momirealms.sparrow.sync.proxy.MinecraftPredicate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionProxyVersionTest {

    @ParameterizedTest
    @CsvSource({
            "1.21.4, true, false", "1.21.6, true, false", "1.21.7, true, true",
            "1.21.8, true, true", "1.21.9, true, true", "1.21.11, true, true", "26.2, true, true",
            "1.21.7, false, false", "1.21.8, false, false", "26.2, false, false"
    })
    void bindsOnlyOnPaperWithDeferredPlayerConstruction(String version, boolean paper, boolean expected) throws ReflectiveOperationException {
        Annotation proxy = annotation(ConnectionProxy.class, "ReflectionProxy");
        String condition = (String) proxy.annotationType().getMethod("activeIf").invoke(proxy);

        assertEquals(expected, new MinecraftPredicate(version, paper ? List.of("paper") : List.of()).test(condition));
    }

    private static Annotation annotation(AnnotatedElement element, String name) {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            if (annotation.annotationType().getSimpleName().equals(name)) return annotation;
        }
        throw new IllegalStateException("missing " + name + " on " + element);
    }
}

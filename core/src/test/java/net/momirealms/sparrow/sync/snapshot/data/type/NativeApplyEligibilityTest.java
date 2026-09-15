package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.network.ConnectionProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.test.ConnectionFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class NativeApplyEligibilityTest {
    private Field configField;
    private Object previousConfig;

    @BeforeAll
    static void bootstrap() {
        BukkitProxy.init("1.21.8", List.of("paper"));
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void installConfig() throws ReflectiveOperationException {
        this.configField = PluginConfig.class.getDeclaredField("config");
        this.configField.setAccessible(true);
        this.previousConfig = this.configField.get(null);
        this.configField.set(null, new PluginConfig.ConfigDefinition());
    }

    @AfterEach
    void restoreConfig() throws ReflectiveOperationException {
        this.configField.set(null, this.previousConfig);
    }

    @ParameterizedTest
    @CsvSource({
            "false, false, false, false", "false, false, false, true",
            "false, false, true, false", "false, false, true, true",
            "false, true, false, false", "false, true, false, true",
            "false, true, true, false", "false, true, true, true",
            "true, false, false, false", "true, false, false, true",
            "true, false, true, false", "true, false, true, true",
            "true, true, false, false", "true, true, false, true",
            "true, true, true, false", "true, true, true, true"
    })
    void checksEachConfigFlagAndTheActualConnection(boolean playerData, boolean advancements, boolean statistics, boolean earlyPlayer) throws ReflectiveOperationException {
        setOption("playerData", playerData);
        setOption("advancements", advancements);
        setOption("statistics", statistics);
        Connection connection = ConnectionFixture.create();
        PlayerSession session = new SessionManager(null).tryOpen(UUID.randomUUID(), "Steve", connection);
        ServerPlayer cached = earlyPlayer ? (ServerPlayer) allocateWithoutConstructor(ServerPlayer.class) : null;
        connection.savedPlayerForLoginEventLegacy = cached;

        assertSame(cached, ConnectionProxy.INSTANCE.getSavedPlayerForLegacyEvents(connection));
        assertEquals(advancements && !earlyPlayer, new AdvancementsDataType().shouldApply(session));
        assertEquals(statistics && !earlyPlayer, new StatisticsDataType().shouldApply(session));
        assertEquals(playerData, new ExperienceDataType().shouldApply(session));
    }

    @Test
    void missingPaperPatchDisablesBothTypesBeforeReadingTheConnection() throws ReflectiveOperationException {
        Set<String> isolated = Set.of(AdvancementsDataType.class.getName(), StatisticsDataType.class.getName(), "net.momirealms.sparrow.sync.util.VersionHelper");
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("io.papermc.paper.adventure.PaperAdventure") || name.equals(ConnectionProxy.class.getName())) {
                    throw new ClassNotFoundException(name);
                }
                if (!isolated.contains(name)) return super.loadClass(name, resolve);
                Class<?> type = this.findLoadedClass(name);
                if (type == null) {
                    try (InputStream input = this.getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        byte[] bytes = input.readAllBytes();
                        type = this.defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException exception) {
                        throw new ClassNotFoundException(name, exception);
                    }
                }
                if (resolve) this.resolveClass(type);
                return type;
            }
        };

        PlayerSession session = new SessionManager(null).tryOpen(UUID.randomUUID(), "Steve", ConnectionFixture.create());
        for (Class<?> type : List.of(AdvancementsDataType.class, StatisticsDataType.class)) {
            Class<?> isolatedType = Class.forName(type.getName(), true, loader);
            Object instance = allocateWithoutConstructor(isolatedType);
            assertEquals(false, isolatedType.getMethod("shouldApply", PlayerSession.class).invoke(instance, session));
        }
    }

    private static void setOption(String name, boolean value) throws ReflectiveOperationException {
        Field field = PluginConfig.NativeAsyncApplyOptions.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(PluginConfig.synchronization$nativeAsyncApply(), value);
    }

    private static Object allocateWithoutConstructor(Class<?> type) throws ReflectiveOperationException {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
    }
}

package net.momirealms.sparrow.sync.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerUtilsTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void spigotTeleportKeepsCallingThreadAndReportsAcceptance(boolean accepted) throws ReflectiveOperationException {
        Thread caller = Thread.currentThread();
        Location target = new Location(null, 1, 2, 3, 4, 5);
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (instance, method, arguments) -> {
            assertEquals("teleport", method.getName());
            assertSame(caller, Thread.currentThread());
            assertSame(target, arguments[0]);
            return accepted;
        });

        CompletableFuture<?> result = this.teleportOnSpigot(player, target);

        assertTrue(result.isDone());
        assertEquals(accepted, result.join());
    }

    @Test
    void spigotTeleportReportsFailureThroughTheFuture() throws ReflectiveOperationException {
        IllegalStateException failure = new IllegalStateException("teleport failed");
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (instance, method, arguments) -> {
            assertEquals("teleport", method.getName());
            throw failure;
        });

        CompletableFuture<?> result = this.teleportOnSpigot(player, new Location(null, 1, 2, 3));

        assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
    }

    private CompletableFuture<?> teleportOnSpigot(Player player, Location target) throws ReflectiveOperationException {
        Set<String> isolated = Set.of(PlayerUtils.class.getName(), VersionHelper.class.getName(), MinecraftVersion.class.getName());
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("io.papermc.paper.adventure.PaperAdventure")) {
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
        Class<?> utils = Class.forName(PlayerUtils.class.getName(), true, loader);
        return (CompletableFuture<?>) utils.getMethod("teleport", Player.class, Location.class).invoke(null, player, target);
    }
}

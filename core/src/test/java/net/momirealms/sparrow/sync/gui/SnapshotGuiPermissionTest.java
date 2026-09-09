package net.momirealms.sparrow.sync.gui;

import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.locale.TranslationManagerImpl;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotGuiPermissionTest {
    @Test
    void editingRequiresOnlyCurrentUiEditPermission() throws Exception {
        Field translation = TranslationManagerImpl.class.getDeclaredField("instance");
        translation.setAccessible(true);
        Object previous = translation.get(null);
        translation.set(null, Proxy.newProxyInstance(TranslationManager.class.getClassLoader(), new Class[]{TranslationManager.class}, (instance, method, args) -> args[0]));
        try {
            List<String> checked = new ArrayList<>();
            AtomicBoolean edit = new AtomicBoolean(true);
            var menu = new SnapshotDetailGui(null, viewer(edit, checked), "Target", null, null, null);
            assertTrue(invoke(menu, "editable"));
            edit.set(false);
            assertFalse(invoke(menu, "editable"));
            assertEquals(List.of("sparrow_sync.ui.edit", "sparrow_sync.ui.edit"), checked);
        } finally {
            translation.set(null, previous);
        }
    }

    private static Player viewer(AtomicBoolean edit, List<String> checked) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[]{Player.class}, (instance, method, args) -> {
            if (method.getName().equals("hasPermission")) {
                String permission = (String) args[0];
                checked.add(permission);
                return permission.equals("sparrow_sync.ui.edit") && edit.get();
            }
            return method.getName().equals("locale") ? Locale.ENGLISH : null;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removalDelegatesToNavigationAndClosesSilentlyOnFailure(boolean fail) throws Exception {
        List<String> calls = new ArrayList<>();
        Window window = (Window) Proxy.newProxyInstance(Window.class.getClassLoader(), new Class[]{Window.class}, (instance, method, args) -> {
            calls.add(method.getName());
            return switch (method.getName()) {
                case "backOrClose" -> fail ? CompletableFuture.failedFuture(new IllegalStateException("navigation failed")) : CompletableFuture.completedFuture(null);
                case "close" -> CompletableFuture.completedFuture(null);
                default -> throw new AssertionError(method.getName());
            };
        });
        var menu = new SnapshotDetailGui(null, null, "Target", null, null, null);
        Field field = SnapshotDetailGui.class.getDeclaredField("window");
        field.setAccessible(true);
        field.set(menu, window);
        Method removed = SnapshotDetailGui.class.getDeclaredMethod("removed");
        removed.setAccessible(true);
        removed.invoke(menu);
        assertEquals(fail ? List.of("backOrClose", "close") : List.of("backOrClose"), calls);
    }

    private static boolean invoke(Object menu, String name) throws Exception {
        Method method = menu.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return (boolean) method.invoke(menu);
    }
}

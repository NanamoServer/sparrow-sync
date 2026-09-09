package net.momirealms.sparrow.sync.gui;

import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.locale.TranslationManagerImpl;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

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
            for (Object menu : menus(viewer(edit, checked), new AtomicBoolean(true))) {
                assertTrue(invoke(menu, "editable"));
                edit.set(false);
                assertFalse(invoke(menu, "editable"));
                edit.set(true);
            }
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

    private static List<Object> menus(Player viewer, AtomicBoolean open) throws Exception {
        Window window = (Window) Proxy.newProxyInstance(Window.class.getClassLoader(), new Class[]{Window.class}, (instance, method, args) -> {
            assertEquals("isOpen", method.getName());
            return open.get();
        });
        List<Object> menus = List.of(new SnapshotDetailGui(null, viewer, "Target", null, null, null));
        for (Object menu : menus) {
            Field field = menu.getClass().getDeclaredField("window");
            field.setAccessible(true);
            field.set(menu, window);
        }
        return menus;
    }

    private static boolean invoke(Object menu, String name) throws Exception {
        Method method = menu.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return (boolean) method.invoke(menu);
    }
}

package net.momirealms.sparrow.sync.gui;

import net.minecraft.server.level.ServerPlayer;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.locale.TranslationManagerImpl;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
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
        BukkitProxy.init("1.21.8", List.of("paper"));
        NmsPlayerFixture.bindStaticRegistryOps();
        Field translation = TranslationManagerImpl.class.getDeclaredField("instance");
        translation.setAccessible(true);
        Object previous = translation.get(null);
        List<Locale> locales = new ArrayList<>();
        translation.set(null, Proxy.newProxyInstance(TranslationManager.class.getClassLoader(), new Class[]{TranslationManager.class}, (instance, method, args) -> {
            locales.add((Locale) args[1]);
            return args[0];
        }));
        try {
            List<String> checked = new ArrayList<>();
            AtomicBoolean edit = new AtomicBoolean(true);
            TestViewer viewer = viewer(edit, checked);
            var menu = new SnapshotDetailGui(null, viewer, "Target", null, null, null);
            assertTrue(invoke(menu, "editable"));
            assertTrue(locales.isEmpty());
            edit.set(false);
            assertFalse(invoke(menu, "editable"));
            assertEquals(List.of("sparrow_sync.ui.edit", "sparrow_sync.ui.edit"), checked);
            assertEquals(List.of(Locale.SIMPLIFIED_CHINESE, Locale.SIMPLIFIED_CHINESE), locales);
        } finally {
            translation.set(null, previous);
        }
    }

    private static TestViewer viewer(AtomicBoolean edit, List<String> checked) {
        CraftPlayer source = NmsPlayerFixture.create();
        ServerPlayer handle = source.getHandle();
        handle.language = "zh_cn";
        TestViewer viewer = NmsPlayerFixture.allocate(TestViewer.class);
        NmsPlayerFixture.set(CraftEntity.class, viewer, "entity", handle);
        viewer.edit = edit;
        viewer.checked = checked;
        NmsPlayerFixture.silenceOutgoingPackets(source);
        return viewer;
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

    private static final class TestViewer extends CraftPlayer {
        private AtomicBoolean edit;
        private List<String> checked;

        private TestViewer() {
            super(null, null);
        }

        @Override
        public boolean hasPermission(String permission) {
            this.checked.add(permission);
            return permission.equals("sparrow_sync.ui.edit") && this.edit.get();
        }
    }
}

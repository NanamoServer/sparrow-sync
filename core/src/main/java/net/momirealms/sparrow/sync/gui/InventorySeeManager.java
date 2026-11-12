package net.momirealms.sparrow.sync.gui;

import net.kyori.adventure.text.TranslatableComponent;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.player.component.PlayerSession;
import net.momirealms.sparrow.sync.util.PlayerUtils;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class InventorySeeManager implements Listener, AutoCloseable {
    private final Map<Window, View> views = new HashMap<>();

    public InventorySeeManager() {
        Bukkit.getPluginManager().registerEvents(this, SparrowSync.instance().javaPlugin());
    }

    @NotNull
    public CompletableFuture<Window.OpenResult> open(@NotNull Player target, @NotNull Window window) {
        View view = new View(target, window);
        this.views.put(window, view);
        window.addOpenHandler(() -> this.onOpen(view));
        window.addCloseHandler(reason -> this.views.remove(window));
        return window.open().whenComplete((result, failure) -> {
            if (failure != null || result != Window.OpenResult.OPENED) {
                this.views.remove(window);
            }
        });
    }

    private void onOpen(View view) {
        // 待打开期间失效的窗口仍可能完成调度, 此时立即关闭.
        if (view.invalidated) {
            view.window.close();
            return;
        }
        PlayerSession session = SparrowSync.instance().playerManager().find(view.target.getUniqueId());
        if (!view.target.isOnline() || session == null || !session.isActive()) {
            this.close(view, MessageConstants.COMMAND_INVENTORY_SEE_PLAYER_OFFLINE);
        } else if (view.target.isDead()) {
            this.close(view, MessageConstants.COMMAND_INVENTORY_SEE_TARGET_DIED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        for (View view : this.views.values().toArray(View[]::new)) {
            if (view.window.viewer().getUniqueId().equals(player.getUniqueId())) {
                this.close(view, null);
            } else if (view.target.getUniqueId().equals(player.getUniqueId())) {
                this.close(view, MessageConstants.COMMAND_INVENTORY_SEE_PLAYER_OFFLINE);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        for (View view : this.views.values().toArray(View[]::new)) {
            if (view.target.getUniqueId().equals(event.getEntity().getUniqueId())) {
                this.close(view, MessageConstants.COMMAND_INVENTORY_SEE_TARGET_DIED);
            }
        }
    }

    private void close(View view, @Nullable TranslatableComponent.Builder message) {
        view.invalidated = true;
        this.views.remove(view.window);
        view.window.close();
        Player viewer = view.window.viewer();
        if (message != null && viewer.isOnline()) {
            PlayerUtils.sendMessage(viewer, TranslationManager.instance().render(message.build(), PlayerUtils.locale(viewer)));
        }
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);
        for (View view : this.views.values().toArray(View[]::new)) {
            this.close(view, null);
        }
    }

    private static final class View {
        private final Player target;
        private final Window window;
        private boolean invalidated;

        private View(Player target, Window window) {
            this.target = target;
            this.window = window;
        }
    }
}
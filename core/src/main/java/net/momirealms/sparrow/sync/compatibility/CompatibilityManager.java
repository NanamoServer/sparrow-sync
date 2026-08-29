package net.momirealms.sparrow.sync.compatibility;

import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

public final class CompatibilityManager {
    private final SparrowSync plugin;
    private boolean hasPlaceholderAPI;

    public CompatibilityManager(SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void onLoad() {
    }

    public void onEnable() {
    }

    public void onDelayedEnable() {
        if (this.isPluginEnabled("PlaceholderAPI")) {
            runCatchingHook(() -> this.hasPlaceholderAPI = true, "PlaceholderAPI");
        }
    }

    public boolean isPluginEnabled(String plugin) {
        return Bukkit.getPluginManager().isPluginEnabled(plugin);
    }

    public boolean hasPlugin(String plugin) {
        return this.getPlugin(plugin) != null;
    }

    private @Nullable Plugin getPlugin(String name) {
        return Bukkit.getPluginManager().getPlugin(name);
    }

    private void logHook(String plugin) {
        this.plugin.logger().info(TranslationManager.console(MessageConstants.PLUGIN_COMPATIBILITY, plugin));
    }

    private void runCatchingHook(ThrowableRunnable runnable, String plugin) {
        try {
            runnable.run();
            logHook(plugin);
        } catch (Throwable e) {
            this.plugin.logger().warn(TranslationManager.console(MessageConstants.PLUGIN_COMPATIBILITY_FAILED, plugin), e);
        }
    }

    @FunctionalInterface
    private interface ThrowableRunnable {
        void run() throws Throwable;
    }
}

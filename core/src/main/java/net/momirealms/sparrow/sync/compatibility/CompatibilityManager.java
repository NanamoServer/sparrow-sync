package net.momirealms.sparrow.sync.compatibility;

import net.momirealms.sparrow.sync.compatibility.migration.MigrationSource;
import net.momirealms.sparrow.sync.compatibility.migration.husksync.HuskSyncSourceV3;
import net.momirealms.sparrow.sync.compatibility.migration.husksync.HuskSyncSourceV4;
import net.momirealms.sparrow.sync.compatibility.migration.invsync.InvSyncSource;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

public final class CompatibilityManager {
    private final SparrowSync plugin;
    private MigrationSource huskSyncMigration;
    private MigrationSource invSyncMigration;

    public CompatibilityManager(SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void onLoad() {
    }

    public void onEnable() {
    }

    public void onDelayedEnable() {
        if (this.isPluginEnabled("InvSync")) {
            Plugin invSync = this.getPlugin("InvSync");
            assert invSync != null;
            this.runCatchingHook(() -> this.invSyncMigration =
                    new InvSyncSource(invSync, this.plugin.dataRegistry()), "InvSync");
        }
        if (this.isPluginEnabled("HuskSync")) {
            Plugin huskSync = this.getPlugin("HuskSync");
            assert huskSync != null;
            this.runCatchingHook(() -> this.huskSyncMigration = huskSync.getDescription().getVersion().startsWith("3.")
                    ? new HuskSyncSourceV3(huskSync, this.plugin.dataRegistry())
                    : new HuskSyncSourceV4(huskSync, this.plugin.dataRegistry()), "HuskSync");
        }
    }

    @Nullable
    public MigrationSource huskSyncMigration() {
        return this.huskSyncMigration;
    }

    @Nullable
    public MigrationSource invSyncMigration() {
        return this.invSyncMigration;
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
        this.plugin.logger().info(TranslationManager.console(LogConstants.PLUGIN_COMPATIBILITY, plugin));
    }

    private void runCatchingHook(ThrowableRunnable runnable, String plugin) {
        try {
            runnable.run();
            this.logHook(plugin);
        } catch (Throwable e) {
            this.plugin.logger().warn(TranslationManager.console(LogConstants.PLUGIN_COMPATIBILITY_FAILED, plugin), e);
        }
    }

    @FunctionalInterface
    private interface ThrowableRunnable {
        void run() throws Throwable;
    }
}

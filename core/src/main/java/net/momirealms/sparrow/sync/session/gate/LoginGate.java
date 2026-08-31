package net.momirealms.sparrow.sync.session.gate;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SnapshotService;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.jetbrains.annotations.NotNull;

public interface LoginGate {

    @NotNull
    static LoginGate create(@NotNull SparrowSync plugin, @NotNull SnapshotService snapshotService, @NotNull SessionManager sessionManager) {
        if (VersionHelper.isPaper() && VersionHelper.isOrAbove1_21_7()) {
            return new PaperEventGate(plugin, snapshotService, sessionManager);
        }
        return new ConfigurationPacketGate(plugin, snapshotService, sessionManager);
    }

    void register();
}

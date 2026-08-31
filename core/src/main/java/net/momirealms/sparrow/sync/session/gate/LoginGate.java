package net.momirealms.sparrow.sync.session.gate;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.session.SnapshotService;
import org.jetbrains.annotations.NotNull;

public interface LoginGate {

    @NotNull
    static LoginGate create(@NotNull SparrowSync plugin, @NotNull SnapshotService snapshotService, @NotNull SessionManager sessionManager) {
        return new ConfigurationPacketGate(plugin, snapshotService, sessionManager);
    }

    void register();
}

package net.momirealms.sparrow.sync.compatibility.plan;

import com.djrapitops.plan.capability.CapabilityService;
import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.ElementOrder;
import com.djrapitops.plan.extension.ExtensionService;
import com.djrapitops.plan.extension.FormatType;
import com.djrapitops.plan.extension.annotation.BooleanProvider;
import com.djrapitops.plan.extension.annotation.Conditional;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.extension.annotation.StringProvider;
import com.djrapitops.plan.extension.annotation.Tab;
import com.djrapitops.plan.extension.annotation.TabInfo;
import com.djrapitops.plan.extension.annotation.TabOrder;
import com.djrapitops.plan.extension.annotation.TableProvider;
import com.djrapitops.plan.extension.icon.Color;
import com.djrapitops.plan.extension.icon.Family;
import com.djrapitops.plan.extension.icon.Icon;
import com.djrapitops.plan.extension.table.Table;
import com.djrapitops.plan.extension.table.TableColumnFormat;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public record PlanHook(SparrowSync plugin) {
    private static final String VALUES_CAPABILITY = "DATA_EXTENSION_VALUES";
    private static final String TABLES_CAPABILITY = "DATA_EXTENSION_TABLES";

    public PlanHook(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void hookIntoPlan() {
        CapabilityService capabilities = CapabilityService.getInstance();
        if (!capabilities.hasCapability(VALUES_CAPABILITY) || !capabilities.hasCapability(TABLES_CAPABILITY)) return;
        this.registerDataExtension();
        capabilities.registerEnableListener(enabled -> {
            if (enabled) this.registerDataExtension();
        });
    }

    private void registerDataExtension() {
        try {
            ExtensionService.getInstance().register(new SparrowSyncDataExtension(this.plugin));
            this.plugin.logger().info("Registered SparrowSync Plan data extension");
        } catch (IllegalStateException | IllegalArgumentException exception) {
            this.plugin.logger().warn("Failed to register SparrowSync Plan data extension", exception);
        }
    }

    @TabInfo(
            tab = "Player Info",
            iconName = "id-card",
            iconFamily = Family.SOLID,
            elementOrder = {ElementOrder.VALUES}
    )
    @TabInfo(
            tab = "Snapshots",
            iconName = "clipboard-list",
            iconFamily = Family.SOLID,
            elementOrder = {ElementOrder.VALUES, ElementOrder.TABLE}
    )
    @TabInfo(
            tab = "Exception Snapshots",
            iconName = "exclamation-triangle",
            iconFamily = Family.SOLID,
            elementOrder = {ElementOrder.VALUES, ElementOrder.TABLE}
    )
    @TabOrder({"Player Info", "Snapshots", "Exception Snapshots"})
    @PluginInfo(
            name = "SparrowSync",
            iconName = "exchange-alt",
            iconFamily = Family.SOLID,
            color = Color.LIGHT_BLUE
    )
    @SuppressWarnings("unused")
    public static final class SparrowSyncDataExtension implements DataExtension {
        private static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(5);
        private static final int CACHE_LIMIT = 128;
        private static final int LIST_LIMIT = 20;

        private final SparrowSync plugin;
        private final Map<UUID, CachedPlayerData> cache = new ConcurrentHashMap<>();

        private SparrowSyncDataExtension(@NotNull SparrowSync plugin) {
            this.plugin = plugin;
        }

        @Override
        public CallEvents[] callExtensionMethodsOn() {
            return new CallEvents[]{CallEvents.PLAYER_JOIN, CallEvents.PLAYER_LEAVE};
        }

        private CachedPlayerData playerData(@NotNull UUID player) {
            long now = System.nanoTime();
            CachedPlayerData cached = this.cache.get(player);
            if (cached != null && now - cached.loadedAt() < CACHE_TTL_NANOS) return cached;

            CachedPlayerData loaded = this.loadPlayerData(player, now);
            this.cache.put(player, loaded);
            this.trimCache();
            return loaded;
        }

        private CachedPlayerData loadPlayerData(@NotNull UUID player, long loadedAt) {
            try {
                Optional<Snapshot> latest = SparrowSync.api().latestSnapshot(player).join();

                CompletableFuture<List<SnapshotMeta>> snapshots = SparrowSync.api()
                        .snapshots(player, 0, LIST_LIMIT)
                        .exceptionally(failure -> {
                            this.plugin.logger().warn("Failed to read SparrowSync snapshots for Plan: " + player, failure);
                            return List.of();
                        });
                CompletableFuture<List<SnapshotFiles.ExceptionEntry>> exceptions = this.plugin.snapshotService()
                        .listExceptions(player, null, 0, LIST_LIMIT)
                        .thenApply(SnapshotFiles.ExceptionPage::content)
                        .exceptionally(failure -> {
                            this.plugin.logger().warn("Failed to read SparrowSync exception snapshots for Plan: " + player, failure);
                            return List.of();
                        });
                return new CachedPlayerData(latest, snapshots.join(), exceptions.join(), loadedAt);
            } catch (RuntimeException exception) {
                this.plugin.logger().warn("Failed to read SparrowSync data for Plan: " + player, exception);
                return new CachedPlayerData(Optional.empty(), List.of(), List.of(), loadedAt);
            }
        }

        private void trimCache() {
            if (this.cache.size() <= CACHE_LIMIT) return;
            for (UUID player : this.cache.keySet()) {
                if (this.cache.size() <= CACHE_LIMIT) break;
                this.cache.remove(player);
            }
        }

        @BooleanProvider(
                text = "Has Synced",
                description = "Whether this player has a SparrowSync snapshot.",
                iconName = "exchange-alt",
                iconFamily = Family.SOLID,
                conditionName = "hasSynced",
                hidden = true
        )
        @Tab("Player Info")
        public boolean hasSynced(@NotNull UUID player) {
            return this.playerData(player).latest().isPresent();
        }

        @StringProvider(
                text = "Sync Status",
                description = "Whether SparrowSync has a stored snapshot for this player.",
                iconName = "info-circle",
                iconFamily = Family.SOLID
        )
        @Tab("Player Info")
        public String syncStatus(@NotNull UUID player) {
            return this.playerData(player).latest().isPresent() ? "Synced" : "No Snapshot";
        }

        @Conditional("hasSynced")
        @NumberProvider(
                text = "Last Sync",
                description = "When this player's latest snapshot was saved.",
                iconName = "clock",
                iconFamily = Family.SOLID,
                format = FormatType.DATE_SECOND
        )
        @Tab("Player Info")
        public long lastSync(@NotNull UUID player) {
            return this.playerData(player).latest().map(snapshot -> snapshot.meta().timestamp()).orElse(0L);
        }

        @Conditional("hasSynced")
        @StringProvider(
                text = "Snapshot ID",
                description = "The ID of this player's latest snapshot.",
                iconName = "bolt",
                iconFamily = Family.SOLID
        )
        @Tab("Player Info")
        public String snapshotId(@NotNull UUID player) {
            return this.playerData(player).latest().map(snapshot -> snapshot.meta().id().toString()).orElse("N/A");
        }

        @Conditional("hasSynced")
        @StringProvider(
                text = "Save Cause",
                description = "Why this player's latest snapshot was saved.",
                iconName = "flag",
                iconFamily = Family.SOLID
        )
        @Tab("Player Info")
        public String saveCause(@NotNull UUID player) {
            return this.playerData(player).latest().map(snapshot -> snapshot.meta().cause().name()).orElse("N/A");
        }

        @Conditional("hasSynced")
        @StringProvider(
                text = "Server",
                description = "The server that saved this player's latest snapshot.",
                iconName = "server",
                iconFamily = Family.SOLID
        )
        @Tab("Player Info")
        public String server(@NotNull UUID player) {
            return this.playerData(player).latest().map(snapshot -> snapshot.meta().server()).orElse("N/A");
        }

        @NumberProvider(
                text = "Snapshot Count",
                description = "The number of recent SparrowSync snapshots shown below.",
                iconName = "database",
                iconFamily = Family.SOLID
        )
        @Tab("Snapshots")
        public long snapshotCount(@NotNull UUID player) {
            return this.playerData(player).snapshots().size();
        }

        @TableProvider(tableColor = Color.LIGHT_BLUE)
        @Tab("Snapshots")
        public Table snapshots(@NotNull UUID player) {
            CachedPlayerData data = this.playerData(player);
            Table.Factory table = Table.builder()
                    .columnOne("Time", new Icon(Family.SOLID, "clock", Color.NONE))
                    .columnOneFormat(TableColumnFormat.DATE_SECOND)
                    .columnTwo("ID", new Icon(Family.SOLID, "bolt", Color.NONE))
                    .columnThree("Cause", new Icon(Family.SOLID, "flag", Color.NONE))
                    .columnFour("State", new Icon(Family.SOLID, "thumbtack", Color.NONE));
            for (SnapshotMeta snapshot : data.snapshots()) {
                table.addRow(snapshot.timestamp(), snapshot.id().toString(), snapshot.cause().name(), snapshot.pinned() ? "Pinned" : "Normal");
            }
            return table.build();
        }

        @NumberProvider(
                text = "Exception Count",
                description = "The number of recent local exception snapshots shown below.",
                iconName = "exclamation-triangle",
                iconFamily = Family.SOLID
        )
        @Tab("Exception Snapshots")
        public long exceptionCount(@NotNull UUID player) {
            return this.playerData(player).exceptions().size();
        }

        @TableProvider(tableColor = Color.LIGHT_BLUE)
        @Tab("Exception Snapshots")
        public Table exceptions(@NotNull UUID player) {
            Table.Factory table = Table.builder()
                    .columnOne("Time", new Icon(Family.SOLID, "clock", Color.NONE))
                    .columnOneFormat(TableColumnFormat.DATE_SECOND)
                    .columnTwo("Category", new Icon(Family.SOLID, "folder", Color.NONE))
                    .columnThree("Status", new Icon(Family.SOLID, "info-circle", Color.NONE))
                    .columnFour("File", new Icon(Family.SOLID, "file", Color.NONE));
            for (SnapshotFiles.ExceptionEntry exception : this.playerData(player).exceptions()) {
                table.addRow(exception.informationAvailable() ? exception.timestamp() : 0L,
                        exception.category(), exceptionStatus(exception), exception.path());
            }
            return table.build();
        }

        @NotNull
        private static String exceptionStatus(@NotNull SnapshotFiles.ExceptionEntry exception) {
            if (exception.headStatus() == SnapshotFiles.HeadStatus.MISSING) return "Header Missing";
            if (exception.headStatus() == SnapshotFiles.HeadStatus.UNREADABLE) return "Header Unreadable";
            return exception.bodyPresent() ? "Available" : "Body Missing";
        }

        private record CachedPlayerData(
                @NotNull Optional<Snapshot> latest,
                @NotNull List<SnapshotMeta> snapshots,
                @NotNull List<SnapshotFiles.ExceptionEntry> exceptions,
                long loadedAt
        ) {
        }
    }
}

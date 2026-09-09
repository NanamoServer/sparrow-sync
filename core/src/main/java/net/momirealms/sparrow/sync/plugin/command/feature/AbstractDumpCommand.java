package net.momirealms.sparrow.sync.plugin.command.feature;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.BukkitCommandFeature;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.snapshot.SnapshotDump;
import org.incendo.cloud.context.CommandContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

abstract class AbstractDumpCommand extends BukkitCommandFeature {
    protected AbstractDumpCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    protected void run(CommandContext<?> context, Function<SnapshotDump, SnapshotDump.Result> operation) {
        SnapshotDump dump = new SnapshotDump(this.plugin().storageProvider(), this.plugin().snapshotService().files(), this.plugin().binaryCodec(), this.plugin().mapSyncService()::importedMap);
        CompletableFuture.supplyAsync(() -> operation.apply(dump), this.plugin().scheduler().async()).whenComplete((result, failure) -> {
            if (failure != null) {
                this.plugin().logger().warn(TranslationManager.console("log.command.snapshot_failed", this.getFeatureID()), failure);
                this.handleFeedback(context, Component.translatable().key("command.query_failed"));
                return;
            }
            String key = "command." + this.getFeatureID() + (result.failure() == null ? ".completed" : ".failed");
            this.handleFeedback(context, Component.translatable().key(key), Component.text(result.file().toString()),
                    Component.text(result.users()), Component.text(result.maps()), Component.text(result.snapshots()), Component.text(result.failed()),
                    Component.text(result.elapsedMillis()), Component.text(this.plugin().snapshotService().files().exceptions().toString()), Component.text(result.current()));
            if (result.failure() != null) {
                this.plugin().logger().warn(TranslationManager.console("log.command.snapshot_failed", this.getFeatureID()), result.failure());
            }
        });
    }
}

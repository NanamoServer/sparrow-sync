package net.momirealms.sparrow.sync.plugin.command.feature;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotUnpinResult;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.parser.standard.UUIDParser;

import java.util.UUID;

public final class SnapshotUnpinCommand extends AbstractSnapshotCommand {
    public SnapshotUnpinCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder
                .required("id", UUIDParser.uuidParser())
                .handler(context -> {
                    UUID snapshotId = context.get("id");
                    this.finish(context, this.plugin().snapshotService().unpin(snapshotId), result -> {
                        String key = switch (result) {
                            case SnapshotUnpinResult.Unpinned ignored -> "unpinned";
                            case SnapshotUnpinResult.Unchanged ignored -> "unchanged";
                            case SnapshotUnpinResult.NotFound ignored -> "not_found";
                        };
                        this.feedback(context, key, snapshotId, "");
                    });
                });
    }

    @Override
    public String getFeatureID() {
        return "snapshot_unpin";
    }
}

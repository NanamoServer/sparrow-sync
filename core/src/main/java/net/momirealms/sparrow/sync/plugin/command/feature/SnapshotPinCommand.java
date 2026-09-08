package net.momirealms.sparrow.sync.plugin.command.feature;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.session.operation.SnapshotPinResult;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.parser.standard.UUIDParser;

import java.util.UUID;

public final class SnapshotPinCommand extends AbstractSnapshotCommand {
    public SnapshotPinCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder
                .required("id", UUIDParser.uuidParser())
                .handler(context -> {
                    UUID snapshotId = context.get("id");
                    this.finish(context, this.plugin().snapshotService().pin(snapshotId), result -> {
                        String key = switch (result) {
                            case SnapshotPinResult.Pinned ignored -> "pinned";
                            case SnapshotPinResult.Unchanged ignored -> "unchanged";
                            case SnapshotPinResult.NotFound ignored -> "not_found";
                        };
                        this.feedback(context, key, snapshotId, "");
                    });
                });
    }

    @Override
    public String getFeatureID() {
        return "snapshot_pin";
    }
}

package net.momirealms.sparrow.sync.plugin.command.feature;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.session.operation.SnapshotExportResult;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.parser.standard.EnumParser;
import org.incendo.cloud.parser.standard.UUIDParser;

import java.util.UUID;

public final class SnapshotExportCommand extends AbstractSnapshotCommand {
    public SnapshotExportCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder
                .required("format", EnumParser.enumParser(SnapshotFiles.Format.class))
                .required("id", UUIDParser.uuidParser())
                .handler(context -> {
                    UUID snapshotId = context.get("id");
                    this.finish(context, this.plugin().snapshotService().export(snapshotId, context.get("format"), context.sender() instanceof Player), result -> {
                        switch (result) {
                            case SnapshotExportResult.Exported exported -> this.feedback(context, "exported", exported.snapshotId(), exported.path());
                            case SnapshotExportResult.NotFound ignored -> this.feedback(context, "not_found", snapshotId, "");
                        }
                    });
                });
    }

    @Override
    public String getFeatureID() {
        return "snapshot_export";
    }
}

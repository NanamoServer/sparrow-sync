package net.momirealms.sparrow.sync.plugin.command.feature;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.plugin.command.parser.NetworkPlayerParser;
import net.momirealms.sparrow.sync.session.operation.SnapshotRestoreResult;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.parser.standard.UUIDParser;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SnapshotRestoreCommand extends AbstractSnapshotCommand {
    public SnapshotRestoreCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder.required("player", NetworkPlayerParser.playerParser(this.plugin().playerDirectory()))
                .required("id", UUIDParser.uuidParser())
                .handler(context -> this.withPlayer(context, player -> {
                    UUID snapshotId = context.get("id");
                    Player local = Bukkit.getPlayer(player.uuid());
                    CompletableFuture<SnapshotRestoreResult> restore;
                    if (local != null) {
                        restore = this.plugin().snapshotService().restore(local, snapshotId);
                    } else {
                        Optional<String> server = this.plugin().playerDirectory().server(player.name());
                        // 命令明确选择远程恢复或离线写入, 远程失败沿回执返回.
                        restore = server.map(s -> this.plugin().remoteSnapshotManager().restore(s, player.uuid(), snapshotId))
                                .orElseGet(() -> this.plugin().snapshotService().restoreOffline(player, snapshotId));
                    }
                    this.finish(context, restore, result -> {
                        UUID reportedId = switch (result) {
                            case SnapshotRestoreResult.Restored saved -> saved.snapshotId();
                            case SnapshotRestoreResult.RestoredOffline saved -> saved.snapshotId();
                            default -> snapshotId;
                        };
                        String key = switch (result) {
                            case SnapshotRestoreResult.Restored ignored -> "restored";
                            case SnapshotRestoreResult.RestoredOffline ignored -> "restored_offline";
                            case SnapshotRestoreResult.NotFound ignored -> "not_found";
                            case SnapshotRestoreResult.WrongPlayer ignored -> "wrong_player";
                            case SnapshotRestoreResult.Offline ignored -> "offline";
                            case SnapshotRestoreResult.Dead ignored -> "dead";
                            case SnapshotRestoreResult.Cancelled ignored -> "cancelled";
                            case SnapshotRestoreResult.Failed ignored -> "failed";
                            case SnapshotRestoreResult.Unavailable ignored -> "unavailable";
                        };
                        this.feedback(context, key, reportedId, "");
                    });
                }));
    }

    @Override
    public String getFeatureID() {
        return "snapshot_restore";
    }
}

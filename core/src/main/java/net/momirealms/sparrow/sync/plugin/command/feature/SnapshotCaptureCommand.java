package net.momirealms.sparrow.sync.plugin.command.feature;

import net.momirealms.sparrow.sync.player.PlayerIdentity;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.plugin.command.parser.NetworkPlayerParser;
import net.momirealms.sparrow.sync.session.operation.SnapshotCaptureResult;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SnapshotCaptureCommand extends AbstractSnapshotCommand {
    public SnapshotCaptureCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder.required("player", NetworkPlayerParser.playerParser(this.plugin().playerDirectory()))
                .handler(context -> {
                    String name = context.get("player");
                    Player local = Bukkit.getPlayerExact(name);
                    CompletableFuture<SnapshotCaptureResult> capture;
                    if (local != null) {
                        capture = this.plugin().snapshotService().capture(local);
                    } else {
                        // 实时采集从全服在线名单取得玩家 UUID 和目标服.
                        Optional<PlayerIdentity> player = this.plugin().playerDirectory().cached(name);
                        Optional<String> server = this.plugin().playerDirectory().server(name);
                        capture = player.isPresent() && server.isPresent()
                                ? this.plugin().remoteSnapshotManager().capture(server.get(), player.get().uuid())
                                : CompletableFuture.completedFuture(new SnapshotCaptureResult.Offline());
                    }
                    this.finish(context, capture, result -> {
                        UUID snapshotId = result instanceof SnapshotCaptureResult.Captured(UUID id) ? id : null;
                        String key = switch (result) {
                            case SnapshotCaptureResult.Captured ignored -> "captured";
                            case SnapshotCaptureResult.Offline ignored -> "offline";
                            case SnapshotCaptureResult.Cancelled ignored -> "cancelled";
                            case SnapshotCaptureResult.Failed ignored -> "failed";
                            case SnapshotCaptureResult.Unavailable ignored -> "unavailable";
                        };
                        this.feedback(context, key, snapshotId, "");
                    });
                });
    }

    @Override
    public String getFeatureID() {
        return "snapshot_capture";
    }
}

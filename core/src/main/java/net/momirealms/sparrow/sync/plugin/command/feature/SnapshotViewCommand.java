package net.momirealms.sparrow.sync.plugin.command.feature;

import net.momirealms.sparrow.sync.gui.SnapshotDetailGui;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.plugin.command.parser.NetworkPlayerParser;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.parser.standard.UUIDParser;

import java.util.concurrent.CompletableFuture;

public final class SnapshotViewCommand extends AbstractSnapshotCommand {
    public SnapshotViewCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder.senderType(Player.class).required("player", NetworkPlayerParser.playerParser(this.plugin().playerDirectory()))
                .required("id", UUIDParser.uuidParser())
                .handler(context -> {
                    Player viewer = context.sender();
                    this.finish(context, CompletableFuture.supplyAsync(() -> new SnapshotDetailGui(this.plugin(), viewer, context.get("player"), context.get("id"), null, null).build(), this.plugin().scheduler().async())
                            .thenCompose(Window::open), result -> {});
                });
    }

    @Override
    public String getFeatureID() {
        return "snapshot_view";
    }
}

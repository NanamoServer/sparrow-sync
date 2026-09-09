package net.momirealms.sparrow.sync.plugin.command.feature;

import net.momirealms.sparrow.sync.gui.SnapshotListGui;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.plugin.command.parser.NetworkPlayerParser;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;

import java.util.concurrent.CompletableFuture;

public final class GuiCommand extends AbstractSnapshotCommand {
    public GuiCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder.senderType(Player.class).required("player", NetworkPlayerParser.playerParser(this.plugin().playerDirectory()))
                .handler(context -> {
                    Player viewer = context.sender();
                    this.finish(context, this.plugin().playerDirectory().resolve(context.get("player")), found -> {
                        if (found.isEmpty()) {
                            this.handleFeedback(context, MessageConstants.COMMAND_GUI_PLAYER_NOT_FOUND);
                            return;
                        }
                        this.finish(context, CompletableFuture.supplyAsync(() ->
                                                new SnapshotListGui(this.plugin(), viewer, found.get().name()).build(), this.plugin().scheduler().async()
                                )
                                .thenCompose(Window::open), result -> {});
                    });
                });
    }

    @Override
    public String getFeatureID() {
        return "gui";
    }
}

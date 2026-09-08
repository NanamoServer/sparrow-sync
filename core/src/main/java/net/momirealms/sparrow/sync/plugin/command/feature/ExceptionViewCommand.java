package net.momirealms.sparrow.sync.plugin.command.feature;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.plugin.command.SnapshotTextPanel;
import net.momirealms.sparrow.sync.session.operation.SnapshotDetailResult;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.parser.standard.StringParser;

public final class ExceptionViewCommand extends AbstractSnapshotCommand {
    public ExceptionViewCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder.required("file", StringParser.greedyStringParser()).handler(context -> {
            if (context.sender() instanceof Player) {
                this.handleFeedback(context, Component.translatable().key("command.panel.gui_pending"));
                return;
            }
            this.finish(context, this.plugin().snapshotService().details().loadException(context.get("file")), archive -> {
                if (archive.result() instanceof SnapshotDetailResult.Failed failed) {
                    this.plugin().logger().warn(TranslationManager.console("log.command.snapshot_failed", this.getFeatureID()), failed.failure());
                }
                new SnapshotTextPanel(this.commandManager).archive(context.sender(), archive);
            });
        });
    }

    @Override
    public String getFeatureID() {
        return "exception_view";
    }
}

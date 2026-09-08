package net.momirealms.sparrow.sync.plugin.command.feature;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.plugin.command.SnapshotTextPanel;
import net.momirealms.sparrow.sync.plugin.command.parser.NetworkPlayerParser;
import net.momirealms.sparrow.sync.snapshot.page.SnapshotPagination;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.parser.standard.IntegerParser;

public final class SnapshotListCommand extends AbstractSnapshotCommand {
    public SnapshotListCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder.required("player", NetworkPlayerParser.playerParser(this.plugin().playerDirectory()))
                .optional("page", IntegerParser.integerParser(1))
                .handler(context -> this.withPlayer(context, player -> this.finish(context,
                        new SnapshotPagination(this.plugin().storageProvider()).load(SnapshotQuery.of(player.uuid()), context.<Integer>optional("page").orElse(1) - 1, SnapshotPagination.TEXT_PAGE_SIZE),
                        page -> new SnapshotTextPanel(this.commandManager).snapshots(context.sender(), player, page))));
    }

    @Override
    public String getFeatureID() {
        return "snapshot_list";
    }
}

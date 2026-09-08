package net.momirealms.sparrow.sync.plugin.command.feature;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.plugin.command.SnapshotTextPanel;
import net.momirealms.sparrow.sync.plugin.command.parser.NetworkPlayerParser;
import net.momirealms.sparrow.sync.snapshot.page.SnapshotPagination;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class ExceptionListCommand extends AbstractSnapshotCommand {
    public ExceptionListCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder.optional("player", NetworkPlayerParser.playerParser(this.plugin().playerDirectory()))
                .optional("page", IntegerParser.integerParser(1))
                .handler(this::list);
    }

    // 单个数字参数表示页码, 数字玩家名可通过显式追加页码查询.
    private void list(CommandContext<CommandSender> context) {
        String input = context.<String>optional("player").orElse(null);
        int page = context.<Integer>optional("page").orElse(1);
        if (input == null) {
            this.show(context, null, null, page);
        } else if (!context.contains("page") && input.matches("[+-]?[0-9]+")) {
            try {
                page = Integer.parseInt(input);
                if (page < 1) throw new NumberFormatException();
            } catch (NumberFormatException failure) {
                this.handleFeedback(context, Component.translatable().key("command.panel.invalid_page"));
                return;
            }
            this.show(context, null, null, page);
        } else {
            int selectedPage = page;
            this.withPlayer(context, player -> this.show(context, player.uuid(), player.name(), selectedPage));
        }
    }

    private void show(CommandContext<CommandSender> context, @Nullable UUID player, @Nullable String name, int page) {
        this.finish(context, this.plugin().snapshotService().exceptions().load(player, null, page - 1, SnapshotPagination.TEXT_PAGE_SIZE),
                result -> new SnapshotTextPanel(this.commandManager).exceptions(context.sender(), name, result));
    }

    @Override
    public String getFeatureID() {
        return "exception_list";
    }
}

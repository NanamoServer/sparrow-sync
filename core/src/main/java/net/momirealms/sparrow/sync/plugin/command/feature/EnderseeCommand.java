package net.momirealms.sparrow.sync.plugin.command.feature;

import net.momirealms.sparrow.sync.gui.EnderseeMenu;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.BukkitCommandFeature;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.plugin.command.parser.LocalPlayerParser;
import net.momirealms.sparrow.sync.player.component.PlayerSession;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.bukkit.parser.PlayerParser;
import org.incendo.cloud.exception.ArgumentParseException;
import org.incendo.cloud.parser.ParserDescriptor;
import org.jetbrains.annotations.NotNull;

public final class EnderseeCommand extends BukkitCommandFeature {
    private final ParserDescriptor<CommandSender, Player> playerParser = LocalPlayerParser.playerParser();

    public EnderseeCommand(@NotNull CommandManager manager, @NotNull SparrowSync plugin) {
        super(manager, plugin);
        manager.getCommandManager().exceptionController().register(
                ArgumentParseException.class,
                registration -> registration
                        .exceptionFilter(exception -> exception.getCause() instanceof PlayerParser.PlayerParseException
                                && exception.currentChain().getLast().parser() == this.playerParser.parser())
                        .exceptionHandler(context -> this.handleFeedback(context.context(), MessageConstants.COMMAND_INVENTORY_SEE_PLAYER_OFFLINE))
        );
    }

    @NotNull
    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder.senderType(Player.class).required("player", this.playerParser)
                .handler(context -> {
                    Player target = context.get("player");
                    if (!target.isOnline()) {
                        this.handleFeedback(context, MessageConstants.COMMAND_INVENTORY_SEE_PLAYER_OFFLINE);
                        return;
                    }
                    PlayerSession session = this.plugin().playerManager().find(target.getUniqueId());
                    if (session == null || !session.isActive()) {
                        this.handleFeedback(context, MessageConstants.COMMAND_INVENTORY_SEE_PLAYER_OFFLINE);
                        return;
                    }
                    if (target.getUniqueId().equals(context.sender().getUniqueId())) {
                        this.handleFeedback(context, MessageConstants.COMMAND_INVENTORY_SEE_SELF);
                        return;
                    }
                    new EnderseeMenu(target).open(context.sender()).whenComplete((result, failure) -> {
                        if (failure != null) {
                            this.plugin().logger().warn(TranslationManager.console("log.command.inventory_see_failed", this.getFeatureID()), failure);
                            this.handleFeedback(context, MessageConstants.COMMAND_INVENTORY_SEE_OPEN_FAILED);
                        }
                    });
                });
    }

    @NotNull
    @Override
    public String getFeatureID() {
        return "ender_see";
    }
}
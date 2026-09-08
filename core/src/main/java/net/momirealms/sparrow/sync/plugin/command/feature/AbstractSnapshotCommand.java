package net.momirealms.sparrow.sync.plugin.command.feature;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.player.PlayerIdentity;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.BukkitCommandFeature;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

abstract class AbstractSnapshotCommand extends BukkitCommandFeature {
    protected AbstractSnapshotCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    // 玩家名字解析与反馈共用同一入口, 各命令声明自己的参数和操作.
    protected void withPlayer(CommandContext<CommandSender> context, Consumer<PlayerIdentity> action) {
        String name = context.get("player");
        this.plugin().playerDirectory().resolve(name).whenComplete((player, failure) -> {
            if (failure != null) {
                this.handleFeedback(context, MessageConstants.COMMAND_QUERY_FAILED);
            } else if (player.isEmpty()) {
                this.handleFeedback(context, MessageConstants.COMMAND_PLAYER_NOT_FOUND, Component.text(name));
            } else {
                action.accept(player.get());
            }
        });
    }

    protected <T> void finish(CommandContext<CommandSender> context, CompletableFuture<T> operation, Consumer<T> feedback) {
        operation.whenComplete((result, failure) -> {
            if (failure != null) {
                this.plugin().logger().warn(TranslationManager.console("log.command.snapshot_failed", this.getFeatureID()), failure);
                this.handleFeedback(context, MessageConstants.COMMAND_QUERY_FAILED);
                return;
            }
            feedback.accept(result);
        });
    }

    // 各命令决定消息键与参数, 短 ID 保留完整值的悬停和复制交互.
    protected void feedback(CommandContext<CommandSender> context, String key, @Nullable UUID snapshotId, String detail) {
        Component id = snapshotId == null ? Component.empty() : Component.text(snapshotId.toString().substring(0, 8))
                .hoverEvent(Component.text(snapshotId.toString())).clickEvent(ClickEvent.copyToClipboard(snapshotId.toString()));
        this.handleFeedback(context, Component.translatable().key("command.snapshot." + key),
                Component.text(context.<String>optional("player").orElse("")), id, Component.text(detail));
    }
}

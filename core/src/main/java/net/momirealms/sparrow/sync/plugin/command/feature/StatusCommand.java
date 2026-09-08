package net.momirealms.sparrow.sync.plugin.command.feature;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.BukkitCommandFeature;
import net.momirealms.sparrow.sync.plugin.command.CommandArguments;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.StorageType;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.Suggestion;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class StatusCommand extends BukkitCommandFeature {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    private final StorageType storageType;
    private final String serverId;

    public StatusCommand(CommandManager commandManager, SparrowSync plugin) {
        super(commandManager, plugin);
        this.storageType = PluginConfig.database$type();
        this.serverId = ServerConfig.serverId();
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder.optional("player", StringParser.stringComponent(StringParser.StringMode.SINGLE)
                        .suggestionProvider((context, input) -> CompletableFuture.completedFuture(this.plugin().sessionManager().playerNames().stream().map(Suggestion::suggestion).toList())))
                .handler(context -> {
                    String input = context.<String>optional("player").orElse(null);
                    if (input == null) {
                        this.showSystem(context);
                    } else {
                        CommandArguments.player(this.plugin().storageProvider(), input).orTimeout(3, TimeUnit.SECONDS)
                                .whenComplete((player, failure) -> {
                                    if (failure != null) {
                                        this.handleFeedback(context, MessageConstants.COMMAND_QUERY_FAILED, Component.text(input));
                                        this.logFailure(input, failure);
                                    } else if (player.isEmpty()) {
                                        this.handleFeedback(context, MessageConstants.COMMAND_PLAYER_NOT_FOUND, Component.text(input));
                                    } else {
                                        this.showPlayer(context, input, player.get());
                                    }
                                });
                    }
                });
    }

    private void showSystem(CommandContext<CommandSender> context) {
        this.handleFeedback(context, MessageConstants.COMMAND_STATUS_SYSTEM,
                Component.text(this.plugin().pluginVersion()),
                Component.text(this.plugin().serverVersion()),
                Component.text(this.serverId),
                Component.text(switch (this.storageType) {
                    case MONGODB -> "MongoDB";
                    case MYSQL -> "MySQL";
                    case POSTGRESQL -> "PostgreSQL";
                }),
                this.plugin().redisConnector().available() ? MessageConstants.COMMAND_CONNECTED.asComponent() : MessageConstants.COMMAND_DISCONNECTED.asComponent(),
                Component.text(this.plugin().playerExecutor().workerCount()),
                Component.text(this.plugin().playerExecutor().pendingTasks()),
                Component.text(this.plugin().sessionManager().size()),
                Component.text(this.plugin().playerExecutor().failureCount()),
                Component.text(this.plugin().sessionManager().rejectedLoginCount()));
    }

    private void showPlayer(CommandContext<CommandSender> context, String input, UUID player) {
        CompletableFuture<Component> holder = this.query(
                this.plugin().sessionLock().holder(player),
                value -> value.<Component>map(lock -> Component.text(lock.serverId())).orElseGet(MessageConstants.COMMAND_STATUS_NO_LOCK::asComponent),
                "lock " + player);
        CompletableFuture<Component> latest = this.query(
                this.plugin().storageProvider().listRecentSnapshots(player, 1),
                metas -> {
                    if (metas.isEmpty()) return MessageConstants.COMMAND_STATUS_NO_SNAPSHOT.asComponent();
                    SnapshotMeta meta = metas.getFirst();
                    return ((TranslatableComponent) MessageConstants.COMMAND_STATUS_SNAPSHOT.asComponent()).arguments(
                            Component.text(meta.id().toString().substring(0, 8))
                                    .hoverEvent(Component.text(meta.id().toString()))
                                    .clickEvent(ClickEvent.copyToClipboard(meta.id().toString())),
                            Component.text(TIMESTAMP.format(Instant.ofEpochMilli(meta.timestamp()))),
                            Component.text(meta.cause().name()), Component.text(meta.server()),
                            meta.pinned() ? MessageConstants.COMMAND_PINNED.asComponent() : MessageConstants.COMMAND_UNPINNED.asComponent());
                },
                "snapshot " + player);
        holder.thenCombine(latest, (lock, snapshot) -> {
            PlayerSession local = this.plugin().sessionManager().find(player);
            Component state = local == null ? MessageConstants.COMMAND_STATUS_NO_SESSION.asComponent() : Component.text(local.state().name());
            this.handleFeedback(context, MessageConstants.COMMAND_STATUS_PLAYER,
                    Component.text(local == null ? (input.equalsIgnoreCase(player.toString()) ? input.substring(0, 8) : input) : local.playerName())
                            .hoverEvent(Component.text(player.toString()))
                            .clickEvent(ClickEvent.copyToClipboard(player.toString())),
                    Component.text(player.toString()), state, lock, snapshot);
            return null;
        });
    }

    private <T> CompletableFuture<Component> query(CompletableFuture<T> query, Function<T, Component> display, String label) {
        return query.orTimeout(5, TimeUnit.SECONDS).handle((value, failure) -> {
            if (failure != null) {
                this.logFailure(label, failure);
                return MessageConstants.COMMAND_STATUS_UNKNOWN.asComponent();
            }
            return display.apply(value);
        });
    }

    private void logFailure(String label, Throwable failure) {
        this.plugin().logger().warn(TranslationManager.console("log.command.status_query_failed", label), failure);
    }

    @Override
    public String getFeatureID() {
        return "status";
    }
}

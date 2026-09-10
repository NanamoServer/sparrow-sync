package net.momirealms.sparrow.sync.plugin.command.feature;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.sync.compatibility.migration.MigrationSource;
import net.momirealms.sparrow.sync.compatibility.migration.SnapshotMigration;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.BukkitCommandFeature;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
import net.momirealms.sparrow.sync.snapshot.SnapshotDump;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.Suggestion;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class MigrateCommand extends BukkitCommandFeature {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss-SSS").withZone(ZoneId.systemDefault());

    public MigrateCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder
                .required("source", StringParser.stringComponent(StringParser.StringMode.SINGLE)
                        .suggestionProvider((context, input) -> CompletableFuture.completedFuture(List.of(Suggestion.suggestion("husksync"), Suggestion.suggestion("invsync")))))
                .handler(this::run);
    }

    private void run(CommandContext<?> context) {
        if (!(context.sender() instanceof ConsoleCommandSender || context.sender() instanceof RemoteConsoleCommandSender)) {
            this.handleFeedback(context, Component.translatable().key("command.migrate.console_only"));
            return;
        }
        String name = context.<String>get("source").toLowerCase(Locale.ROOT);
        MigrationSource source = switch (name) {
            case "husksync" -> this.plugin().compatibilityManager().huskSyncMigration();
            case "invsync" -> this.plugin().compatibilityManager().invSyncMigration();
            default -> null;
        };
        if (source == null) {
            this.handleFeedback(context, Component.translatable().key("command.migrate.unavailable"), Component.text(name));
            return;
        }
        long started = System.currentTimeMillis();
        String file = "sparrow-sync-migrate-" + name + "-" + FILE_TIME.format(Instant.ofEpochMilli(started)) + ".zip";
        var files = this.plugin().snapshotService().files();
        SnapshotDump importer = new SnapshotDump(this.plugin().storageProvider(), files, this.plugin().binaryCodec(), this.plugin().mapSyncService()::importedMap);
        SnapshotMigration migration = new SnapshotMigration(files, this.plugin().binaryCodec(), importer, ServerConfig.serverId());
        CompletableFuture.supplyAsync(() -> migration.migrate(file, source, started, progress -> this.progress(context, name, progress)), this.plugin().scheduler().async())
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        this.plugin().logger().warn("Migration failed: " + name, failure);
                        this.handleFeedback(context, Component.translatable().key("command.query_failed"));
                        return;
                    }
                    this.completed(context, result);
                });
    }

    private void progress(CommandContext<?> context, String source, SnapshotMigration.Result result) {
        SnapshotDump.Result imported = result.imported();
        String stage = imported == null ? "generating" : "importing";
        this.handleFeedback(context, Component.translatable().key("command.migrate." + stage), Component.text(source), Component.text(result.file().toString()),
                Component.text(result.converted() + result.failed()), Component.text(result.converted()), Component.text(result.failed()),
                Component.text(imported == null ? 0 : imported.snapshots()), Component.text(imported == null ? 0 : imported.failed()));
    }

    private void completed(CommandContext<?> context, SnapshotMigration.Result result) {
        SnapshotDump.Result imported = result.imported();
        Throwable failure = result.failure() != null ? result.failure() : imported.failure();
        String status = result.failure() != null ? "generation_failed" : imported.failure() != null ? "import_failed"
                : result.failed() + imported.failed() == 0 ? "completed" : "partial";
        this.handleFeedback(context, Component.translatable().key("command.migrate." + status), Component.text(result.file().toString()),
                Component.text(result.converted()), Component.text(result.failed()), Component.text(imported == null ? 0 : imported.snapshots()),
                Component.text(imported == null ? 0 : imported.failed()), Component.text(result.elapsedMillis()),
                Component.text(this.plugin().snapshotService().files().exceptions().toString()),
                Component.text(imported == null ? result.current() : imported.current()), Component.text(failure == null ? "" : failure.toString()));
        if (failure != null) {
            this.plugin().logger().warn("Migration failed at " + (imported == null ? result.current() : imported.current()), failure);
        }
        if (result.failure() == null && imported.failure() != null) {
            String usage = this.plugin().configurationManager().commandsConfig().configDefinition().command("import_all").getUsages().getFirst();
            this.handleFeedback(context, Component.translatable().key("command.migrate.retry"), Component.text(usage + " " + result.file().getFileName()));
        }
    }

    @Override
    public String getFeatureID() {
        return "migrate";
    }
}

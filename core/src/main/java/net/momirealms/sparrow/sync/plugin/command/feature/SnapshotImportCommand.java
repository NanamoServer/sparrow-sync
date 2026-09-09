package net.momirealms.sparrow.sync.plugin.command.feature;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.session.operation.SnapshotImportResult;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.Suggestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public final class SnapshotImportCommand extends AbstractSnapshotCommand {
    public SnapshotImportCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder.required("file", StringParser.stringComponent(StringParser.StringMode.GREEDY)
                .suggestionProvider((context, input) -> CompletableFuture.supplyAsync(
                        () -> this.suggestions(input.remainingInput()), this.plugin().scheduler().async())))
                .handler(context -> this.finish(context, this.plugin().snapshotService().importFile(context.get("file")), result -> {
                    switch (result) {
                        case SnapshotImportResult.Imported imported -> this.feedback(context, "imported", imported.snapshotId(), "");
                        case SnapshotImportResult.InvalidFile ignored -> this.feedback(context, "invalid_file", null, "");
                        case SnapshotImportResult.Failed ignored -> this.feedback(context, "failed", null, "");
                    }
                }));
    }

    // 在命令异步补全任务中扫描合法后缀, 返回相对目录路径.
    List<Suggestion> suggestions(String prefix) {
        Path directory = this.plugin().snapshotService().files().output();
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".snapshot") || name.endsWith(".json");
                    })
                    .map(path -> directory.relativize(path).toString().replace('\\', '/'))
                    .filter(path -> path.regionMatches(true, 0, prefix, 0, prefix.length()))
                    .sorted().map(Suggestion::suggestion).toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    @Override
    public String getFeatureID() {
        return "snapshot_import";
    }
}

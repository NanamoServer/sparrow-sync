package net.momirealms.sparrow.sync.plugin.command.feature;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.sync.locale.MessageConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.BukkitCommandFeature;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.exception.ExceptionHeader;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.Suggestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

public final class ExceptionDeleteCommand extends BukkitCommandFeature {
    public ExceptionDeleteCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder.required("file", StringParser.stringComponent(StringParser.StringMode.GREEDY)
                .suggestionProvider((context, input) -> CompletableFuture.supplyAsync(
                        () -> this.suggestions(input.remainingInput()), this.plugin().scheduler().async())))
                .handler(context -> {
                    String file = context.get("file");
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            return this.plugin().snapshotService().files().deleteException(file);
                        } catch (IOException | IllegalArgumentException failure) {
                            throw new CompletionException(failure);
                        }
                    }, this.plugin().scheduler().async()).whenComplete((deleted, failure) -> {
                        if (failure != null) {
                            this.plugin().logger().warn(TranslationManager.console("log.command.snapshot_failed", "exception delete"), failure);
                            this.handleFeedback(context, MessageConstants.COMMAND_QUERY_FAILED);
                        } else {
                            this.handleFeedback(context, Component.translatable().key(deleted ? "command.exception.deleted" : "command.exception.not_found"), Component.text(file));
                        }
                    });
                });
    }

    // 在命令异步补全任务中扫描合法后缀, 返回相对目录路径.
    List<Suggestion> suggestions(String prefix) {
        Path directory = this.plugin().snapshotService().files().exceptions();
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> directory.relativize(path).toString().replace('\\', '/'))
                    .map(path -> path.endsWith(ExceptionHeader.SUFFIX) ? path.substring(0, path.length() - ExceptionHeader.SUFFIX.length()) : path)
                    .filter(path -> SnapshotFiles.supported(Path.of(path)))
                    .filter(path -> path.regionMatches(true, 0, prefix, 0, prefix.length()))
                    .distinct().sorted().map(Suggestion::suggestion).toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    @Override
    public String getFeatureID() {
        return "exception_delete";
    }
}

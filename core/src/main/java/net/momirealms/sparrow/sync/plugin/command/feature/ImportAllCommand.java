package net.momirealms.sparrow.sync.plugin.command.feature;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
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

public final class ImportAllCommand extends AbstractDumpCommand {
    public ImportAllCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder
                .required("file", StringParser.stringComponent(StringParser.StringMode.GREEDY)
                .suggestionProvider((context, input) -> CompletableFuture.supplyAsync(() -> this.suggestions(input.remainingInput()), this.plugin().scheduler().async())))
                .handler(context -> this.run(context, dump -> dump.importFile(context.get("file"))));
    }

    List<Suggestion> suggestions(String prefix) {
        Path directory = this.plugin().snapshotService().files().dump();
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".zip") && name.regionMatches(true, 0, prefix, 0, prefix.length()))
                    .sorted().map(Suggestion::suggestion).toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    @Override
    public String getFeatureID() {
        return "import_all";
    }
}

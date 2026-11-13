package net.momirealms.sparrow.sync.plugin.command.parser;

import net.momirealms.sparrow.sync.plugin.SparrowSync;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.bukkit.parser.PlayerParser;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class LocalPlayerParser<C extends CommandSender> implements ArgumentParser<C, Player>, SuggestionProvider<C> {

    @NotNull
    public static <C extends CommandSender> ParserDescriptor<C, Player> playerParser() {
        return ParserDescriptor.of(new LocalPlayerParser<C>(), Player.class);
    }

    @NotNull
    @Override
    public ArgumentParseResult<Player> parse(@NotNull CommandContext<C> context, @NotNull CommandInput input) {
        String name = input.readString();
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            return ArgumentParseResult.failure(new PlayerParser.PlayerParseException(name, context));
        }
        return ArgumentParseResult.success(player);
    }

    @NotNull
    @Override
    public CompletableFuture<? extends Iterable<? extends Suggestion>> suggestionsFuture(@NotNull CommandContext<C> context, @NotNull CommandInput input) {
        String prefix = input.peekString();
        CommandSender sender = context.sender();
        if (!(sender instanceof Player viewer)) {
            return CompletableFuture.completedFuture(this.suggestions(sender, prefix));
        }
        CompletableFuture<List<Suggestion>> result = new CompletableFuture<>();
        // 可见性 Map 在执行者所属线程读取, 补全结果异步返回.
        return result.completeAsync(
                () -> this.suggestions(viewer, prefix),
                task -> SparrowSync.instance().scheduler().platform().run(task, () -> result.complete(List.of()), viewer)
        );
    }

    @NotNull
    private List<Suggestion> suggestions(@NotNull CommandSender sender, @NotNull String prefix) {
        List<Suggestion> suggestions = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            String name = player.getName();
            if (!name.regionMatches(true, 0, prefix, 0, prefix.length())) continue;
            if (sender instanceof Player viewer && !viewer.canSee(player)) continue;
            suggestions.add(Suggestion.suggestion(name));
        }
        return suggestions;
    }
}
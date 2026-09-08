package net.momirealms.sparrow.sync.plugin.command.parser;

import net.momirealms.sparrow.sync.player.PlayerDirectory;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.suggestion.BlockingSuggestionProvider;
import org.incendo.cloud.suggestion.Suggestion;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 使用全服在线名字缓存补全, 执行时支持历史玩家名字. */
public final class NetworkPlayerParser<C> implements ArgumentParser<C, String>, BlockingSuggestionProvider<C> {
    private final PlayerDirectory directory;

    public NetworkPlayerParser(@NotNull PlayerDirectory directory) {
        this.directory = directory;
    }

    @NotNull
    public static <C> ParserDescriptor<C, String> playerParser(@NotNull PlayerDirectory directory) {
        return ParserDescriptor.of(new NetworkPlayerParser<C>(directory), String.class);
    }

    @Override
    @NotNull
    public ArgumentParseResult<String> parse(@NotNull CommandContext<C> context, @NotNull CommandInput input) {
        return ArgumentParseResult.success(input.readString());
    }

    @Override
    @NotNull
    public Iterable<? extends Suggestion> suggestions(@NotNull CommandContext<C> context, @NotNull CommandInput input) {
        try {
            return this.directory.suggestions(input.peekString());
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}

package net.momirealms.sparrow.sync.plugin.command;

import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 按玩家名字或 UUID 解析管理命令的目标. */
public final class CommandArguments {
    private CommandArguments() {
    }

    // todo 完整的缓存和跨服补全玩家名系统, 这个类未来删除
    @NotNull
    public static CompletableFuture<Optional<UUID>> player(@NotNull StorageProvider storage, @NotNull String input) {
        if (input.length() == 36) {
            try {
                return CompletableFuture.completedFuture(Optional.of(UUID.fromString(input)));
            } catch (IllegalArgumentException ignored) {
                // 名字映射允许完整保留服务器记录的名字.
            }
        }
        return storage.lookupUser(input);
    }
}

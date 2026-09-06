package net.momirealms.sparrow.sync.map;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** 共享地图缓存. 只有来源发布链可以写内容, 接收服仅查询和续期. */
public interface MapCache {
    @NotNull
    CompletableFuture<Optional<StoredMap>> find(int globalId);

    @NotNull
    CompletableFuture<Void> publish(@NotNull StoredMap map);

    @NotNull
    CompletableFuture<Boolean> touch(int globalId);
}

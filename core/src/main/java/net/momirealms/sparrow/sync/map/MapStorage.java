package net.momirealms.sparrow.sync.map;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@ApiStatus.Internal
public interface MapStorage {
    @NotNull
    CompletableFuture<Optional<StoredMap>> find(int globalId);

    // 登记来源与首份内容, 重复登记返回已经存在的记录.
    @NotNull
    CompletableFuture<StoredMap> register(@NotNull MapSource source, @NotNull MapData initial);

    // 更新已经登记的地图画面.
    @NotNull
    CompletableFuture<Void> update(@NotNull MapIdentity identity, @NotNull MapData data);
}

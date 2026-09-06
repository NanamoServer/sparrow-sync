package net.momirealms.sparrow.sync.map;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@ApiStatus.Internal
public interface MapStorage {
    @NotNull
    CompletableFuture<Optional<StoredMap>> find(@NotNull MapSource source);

    @NotNull
    CompletableFuture<Optional<StoredMap>> find(int globalId);

    /** 原子登记来源及初始内容, 已登记时返回原记录. <strong>仅本存储绑定的来源服可写入</strong>. */
    @NotNull
    CompletableFuture<StoredMap> register(@NotNull MapSource source, @NotNull MapData initial);

    /** 更新已登记地图的内容. <strong>调用方按地图身份串行等待完成, 包括重试</strong>. */
    @NotNull
    CompletableFuture<Void> update(@NotNull MapIdentity identity, @NotNull MapData data);
}

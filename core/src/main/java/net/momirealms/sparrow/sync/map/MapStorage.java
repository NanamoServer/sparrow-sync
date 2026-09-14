package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.List;
import net.momirealms.sparrow.sync.map.data.MapArchiveRecord;
import java.util.concurrent.CompletableFuture;

@ApiStatus.Internal
public interface MapStorage {

    // 按全局 ID 降序读取小于 beforeId 的记录, 最多 limit 条. 首次传 0, 后续传上一页末尾 ID.
    @NotNull
    CompletableFuture<List<MapArchiveRecord>> scan(int beforeId, int limit);

    // 读取 ID 分配计数; 递增后取负数作为下一个全局 ID.
    @NotNull
    CompletableFuture<Long> sequence();

    // 导入计数大于当前值时才更新, 避免重复分配已使用的 ID.
    @NotNull
    CompletableFuture<Void> importSequence(long sequence);

    // 按原全局 ID 写入或覆盖地图, 保留来源、版本、更新时间和原始数据, 同时推进 ID 分配计数.
    @NotNull
    CompletableFuture<Void> importMap(@NotNull MapArchiveRecord map);

    // 按照全局 ID 查找数据库保存的地图数据.
    @NotNull
    CompletableFuture<Optional<StoredMap>> find(int globalId);

    // 登记来源和初始内容, 已登记时返回现有记录.
    @NotNull
    CompletableFuture<StoredMap> register(@NotNull MapSource source, @NotNull MapData initial);

    // 更新已登记的地图内容.
    @NotNull
    CompletableFuture<Void> update(@NotNull MapIdentity identity, @NotNull MapData data);
}

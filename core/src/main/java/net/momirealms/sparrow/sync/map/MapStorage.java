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

    // 按全局 ID 降序读取小于 beforeId 的至多 limit 条原始地图记录, 首批传 0, 后续传上一批末尾 ID.
    @NotNull
    CompletableFuture<List<MapArchiveRecord>> scan(int beforeId, int limit);

    // 读取全局地图 ID 的分配计数, 计数递增后取负值作为下一张地图的 ID.
    @NotNull
    CompletableFuture<Long> sequence();

    // 将分配计数推进到当前值与导入值的较大者, 保留导出库已使用的 ID 范围.
    @NotNull
    CompletableFuture<Void> importSequence(long sequence);

    // 按原全局 ID 写入或覆盖地图, 保留来源、版本、更新时间和原始数据, 并推进分配计数.
    @NotNull
    CompletableFuture<Void> importMap(@NotNull MapArchiveRecord map);

    // 按照全局 ID 查找数据库保存的地图数据.
    @NotNull
    CompletableFuture<Optional<StoredMap>> find(int globalId);

    // 登记来源与首份内容, 重复登记返回已经存在的记录.
    @NotNull
    CompletableFuture<StoredMap> register(@NotNull MapSource source, @NotNull MapData initial);

    // 更新已经登记的地图画面.
    @NotNull
    CompletableFuture<Void> update(@NotNull MapIdentity identity, @NotNull MapData data);
}

package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.exception.FormatException;
import net.momirealms.sparrow.sync.session.operation.SnapshotDetailResult;
import net.momirealms.sparrow.sync.session.operation.SnapshotDetailResult.Preview;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.EnchantmentSeedDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.ExperienceDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.GameModeDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HungerDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.LocationDataType;
import net.momirealms.sparrow.sync.snapshot.exception.ExceptionArchives;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@ApiStatus.Internal
public final class SnapshotDetails {
    private final StorageProvider storage;
    private final SnapshotFiles files;
    private final ExceptionArchives archives;
    private final DataRegistry registry;
    private final Executor executor;

    public SnapshotDetails(@NotNull StorageProvider storage, @NotNull SnapshotFiles files, @NotNull ExceptionArchives archives, @NotNull DataRegistry registry, @NotNull Executor executor) {
        this.storage = storage;
        this.files = files;
        this.archives = archives;
        this.registry = registry;
        this.executor = executor;
    }

    // 按明确 ID 读取一份, 预览解码始终投递异步执行器, 不触发玩家应用管线.
    @NotNull
    public CompletableFuture<SnapshotDetailResult> load(@NotNull UUID id) {
        return this.storage.snapshot(id).handleAsync((snapshot, failure) -> {
            if (failure != null) return this.failure(failure);
            return snapshot.<SnapshotDetailResult>map(this::prepare).orElseGet(SnapshotDetailResult.NotFound::new);
        }, this.executor);
    }

    // 档案头随详情结果返回, 正文缺失或解码失败时仍可展示已确认的元信息.
    @NotNull
    public CompletableFuture<SnapshotDetailResult.Archive> loadException(@NotNull String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ExceptionArchives.Entry entry = this.archives.entry(path);
                SnapshotDetailResult result;
                try {
                    result = switch (this.files.readException(path)) {
                        case DecodedSnapshot.Valid valid -> this.prepare(valid.snapshot());
                        case DecodedSnapshot.Invalid invalid -> new SnapshotDetailResult.Invalid(invalid.reason(), invalid.detail());
                    };
                } catch (IOException failure) {
                    result = this.failure(failure);
                }
                return new SnapshotDetailResult.Archive(entry, result);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, this.executor);
    }

    // 每类预览独立解码, 一类损坏仍可查看其他内容; 未适配类型保留在原快照中.
    private SnapshotDetailResult.Ready prepare(Snapshot snapshot) {
        Map<DataKey, Preview> previews = new LinkedHashMap<>();
        for (var entry : snapshot.data().entrySet()) {
            PlayerDataType<?> type = this.registry.type(entry.getKey());
            if (!(type instanceof InventoryDataType || type instanceof EnderChestDataType
                    || type instanceof ExperienceDataType || type instanceof HealthDataType
                    || type instanceof HungerDataType || type instanceof GameModeDataType || type instanceof EnchantmentSeedDataType
                    || type instanceof LocationDataType)) {
                previews.put(entry.getKey(), new Preview.Unsupported(type != null));
                continue;
            }
            try {
                previews.put(entry.getKey(), new Preview.Ready(type.decode(entry.getValue(), snapshot.meta().mcDataVersion())));
            } catch (IOException | RuntimeException failure) {
                previews.put(entry.getKey(), new Preview.Failed(String.valueOf(failure.getMessage())));
            }
        }
        return new SnapshotDetailResult.Ready(snapshot, Collections.unmodifiableMap(previews));
    }

    private SnapshotDetailResult failure(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof NoSuchFileException) return new SnapshotDetailResult.NotFound();
        if (failure instanceof FormatException invalid) {
            return new SnapshotDetailResult.Invalid(invalid.reason(), invalid.getMessage());
        }
        return new SnapshotDetailResult.Failed(failure);
    }
}

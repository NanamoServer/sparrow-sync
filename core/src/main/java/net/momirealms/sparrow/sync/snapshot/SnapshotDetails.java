package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.compatibility.economy.VaultDataType;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.data.*;
import net.momirealms.sparrow.sync.snapshot.data.type.EnchantmentSeedDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.ExperienceDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.GameModeDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HungerDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.LocationDataType;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDetailResult.Preview;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDetailResult;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

// 读取快照并准备详情页预览
@ApiStatus.Internal
public final class SnapshotDetails {
    private final StorageProvider storage;
    private final SnapshotFiles files;
    private final DataRegistry registry;
    private final SnapshotDecoder decoder;
    private final Executor executor;

    public SnapshotDetails(@NotNull StorageProvider storage, @NotNull SnapshotFiles files, @NotNull DataRegistry registry, @NotNull Executor executor) {
        this.storage = storage;
        this.files = files;
        this.registry = registry;
        this.decoder = new SnapshotDecoder(registry);
        this.executor = executor;
    }

    /** 异步读取快照, 只解码详情页支持的类型. */
    @NotNull
    public CompletableFuture<SnapshotDetailResult> load(@NotNull UUID id) {
        return this.storage.snapshot(id).handleAsync((snapshot, failure) -> {
            if (failure != null) return this.failure(failure);
            return snapshot.<SnapshotDetailResult>map(this::prepare).orElse(SnapshotDetailResult.NOT_FOUND);
        }, this.executor);
    }

    /** 读取异常快照头文件, 获取快照信息、数据类型和大小, 不读取快照数据. */
    @NotNull
    public CompletableFuture<SnapshotDetailResult.Archive> loadException(@NotNull String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new SnapshotDetailResult.Archive(this.files.exceptionEntry(path), new SnapshotDetailResult.Overview());
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, this.executor);
    }

    /** 按需读取异常快照信息和数据索引, 各类型内容暂不解码. */
    @NotNull
    public CompletableFuture<SnapshotDetailResult.Archive> loadExceptionBody(@NotNull String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SnapshotDetailResult result;
                try {
                    result = switch (this.files.readException(path)) {
                        case DecodedSnapshot.Valid valid -> this.prepare(valid.snapshot(), type -> false);
                        case DecodedSnapshot.Invalid invalid -> new SnapshotDetailResult.Invalid(invalid.reason(), invalid.detail());
                    };
                } catch (IOException failure) {
                    result = this.failure(failure);
                }
                return new SnapshotDetailResult.Archive(this.files.exceptionEntry(path), result);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, this.executor);
    }

    /** 读取所选类型的预览, 保留其他预览并共享原快照. */
    @NotNull
    public CompletableFuture<SnapshotDetailResult.Ready> preview(@NotNull SnapshotDetailResult.Ready loaded, @NotNull DataKey key) {
        return CompletableFuture.supplyAsync(() -> {
            if (!(loaded.previews().get(key) instanceof Preview.Unloaded)) return loaded;
            SnapshotDetailResult.Ready selected = this.prepare(loaded.snapshot(), type -> type.key().equals(key));
            Map<DataKey, Preview> previews = new LinkedHashMap<>(loaded.previews());
            previews.put(key, selected.previews().get(key));
            return new SnapshotDetailResult.Ready(loaded.snapshot(), Collections.unmodifiableMap(previews));
        }, this.executor);
    }

    /** 按原顺序准备各类型预览, 不支持预览的类型只读取大小, 单个类型失败不影响其他类型. */
    @NotNull
    private SnapshotDetailResult.Ready prepare(@NotNull Snapshot snapshot) {
        return this.prepare(snapshot, SnapshotDetails::supportsPreview);
    }

    /** 为选中类型读取内容, 其余类型只读取块头大小, 返回按原顺序排列的只读预览表. */
    @NotNull
    private SnapshotDetailResult.Ready prepare(@NotNull Snapshot snapshot, @NotNull Predicate<PlayerDataType<?>> selected) {
        DecodedSnapshotData decoded = this.decoder.decodeSelected(snapshot, type -> supportsPreview(type) && selected.test(type));
        Map<DataKey, Preview> previews = new LinkedHashMap<>();
        for (DataKey key : snapshot.keys()) {
            PlayerDataType<?> type = this.registry.type(key);
            if (!supportsPreview(type)) {
                try {
                    previews.put(key, new Preview.Unsupported(type != null, snapshot.content().rawLength(key), this.registry.shouldDropUnknown(key)));
                } catch (UncheckedIOException exception) {
                    // 单个块头损坏只影响该类型的预览
                    previews.put(key, new Preview.Failed(String.valueOf(exception.getMessage())));
                }
                continue;
            }
            if (!selected.test(type)) {
                int rawLength;
                try {
                    rawLength = snapshot.content().rawLength(key);
                } catch (UncheckedIOException failure) {
                    rawLength = -1;
                }
                previews.put(key, new Preview.Unloaded(rawLength));
                continue;
            }
            Throwable failure = decoded.failure(key);
            previews.put(key, failure == null
                    ? new Preview.Ready(decoded.value(key))
                    : new Preview.Failed(String.valueOf(failure.getMessage())));
        }
        return new SnapshotDetailResult.Ready(snapshot, Collections.unmodifiableMap(previews));
    }

    // 判断详情页是否支持此数据类型
    private static boolean supportsPreview(@Nullable PlayerDataType<?> type) {
        return type instanceof InventoryDataType || type instanceof EnderChestDataType
                || type instanceof ExperienceDataType || type instanceof HealthDataType
                || type instanceof HungerDataType || type instanceof GameModeDataType
                || type instanceof EnchantmentSeedDataType || type instanceof LocationDataType
                || type instanceof VaultDataType;
    }

    // 将读取异常转换为详情页错误状态, 保留格式错误的原因
    @NotNull
    private SnapshotDetailResult failure(@NotNull Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof NoSuchFileException) return SnapshotDetailResult.NOT_FOUND;
        if (failure instanceof FormatException invalid) {
            return new SnapshotDetailResult.Invalid(invalid.reason(), invalid.getMessage());
        }
        return new SnapshotDetailResult.Failed(failure);
    }
}

package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.compatibility.economy.EmoneyDataType;
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
import java.nio.file.NoSuchFileException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

// 负责读取并整理一份快照详情用于GUI展示
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

    /**
     * 异步读取明确 ID 的快照, 并仅解码详情支持的类型.
     *
     * @param id 明确选定的快照 ID
     * @return 单份快照数据与预览状态
     */
    @NotNull
    public CompletableFuture<SnapshotDetailResult> load(@NotNull UUID id) {
        return this.storage.snapshot(id).handleAsync((snapshot, failure) -> {
            if (failure != null) return this.failure(failure);
            return snapshot.<SnapshotDetailResult>map(this::prepare).orElse(SnapshotDetailResult.NOT_FOUND);
        }, this.executor);
    }

    /**
     * 读取异常快照头文件和异常快照数据, 数据读取失败时仍返回头文件中的元信息.
     *
     * @param path 选定异常快照的相对路径
     * @return 包含异常快照元信息和异常快照数据读取状态的详情
     */
    @NotNull
    public CompletableFuture<SnapshotDetailResult.Archive> loadException(@NotNull String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SnapshotFiles.ExceptionEntry entry = this.files.exceptionEntry(path);
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

    /**
     * 为快照中的每个类型生成详情页预览结果, 并保留快照中的类型顺序.
     * 支持预览的类型会读取并转换为玩家数据对象, 读取失败则记录错误.
     * 其余类型只记录是否已注册以及索引中的未压缩 NBT 字节数, 不读取该类型的数据块.
     *
     * @param snapshot 要展示的快照
     * @return 原快照和只读的预览结果表, 每个类型对应可展示, 读取失败或不支持预览三种结果之一
     */
    @NotNull
    private SnapshotDetailResult.Ready prepare(@NotNull Snapshot snapshot) {
        DecodedSnapshotData decoded = this.decoder.decodeSelected(snapshot, SnapshotDetails::supportsPreview);
        Map<DataKey, Preview> previews = new LinkedHashMap<>();
        for (DataKey key : snapshot.keys()) {
            PlayerDataType<?> type = this.registry.type(key);
            if (!supportsPreview(type)) {
                previews.put(key, new Preview.Unsupported(type != null, snapshot.content().rawLength(key), snapshot.content().meta(key)));
                continue;
            }
            Throwable failure = decoded.failure(key);
            previews.put(key, failure == null
                    ? new Preview.Ready(decoded.value(key))
                    : new Preview.Failed(String.valueOf(failure.getMessage())));
        }
        return new SnapshotDetailResult.Ready(snapshot, Collections.unmodifiableMap(previews));
    }

    // 声明当前详情展示已经适配的类型, 本服未注册类型保持未适配状态.
    private static boolean supportsPreview(@Nullable PlayerDataType<?> type) {
        return type instanceof InventoryDataType || type instanceof EnderChestDataType
                || type instanceof ExperienceDataType || type instanceof HealthDataType
                || type instanceof HungerDataType || type instanceof GameModeDataType
                || type instanceof EnchantmentSeedDataType || type instanceof LocationDataType
                || type instanceof EmoneyDataType;
    }

    // 将读取错误转换为详情状态, 保留格式错误的结构化原因.
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

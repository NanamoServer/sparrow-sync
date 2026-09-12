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
     * 读取异常快照的头文件概览, 类型清单和体量来自头文件摘要.
     *
     * @param path 选定异常快照的相对路径
     * @return 包含身份与类型清单的概览, 正文保持未读取状态
     */
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

    /**
     * 按需读取异常正文的身份与索引.
     *
     * @param path 选定异常快照的相对路径
     * @return 原始快照和尚未读取的类型预览, 或具体的正文读取失败状态
     */
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

    /**
     * 在已读取的异常快照中展开一个类型, 保留当前窗口已经准备好的其他预览.
     *
     * @param loaded 当前窗口持有的快照及预览状态
     * @param key 用户本次选择的类型
     * @return 仅更新所选类型的预览结果, 与输入共享同一份原始快照
     */
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

    /**
     * 为快照中的每个类型生成详情页预览结果, 并保留快照中的类型顺序.
     * 支持预览的类型会读取并转换为玩家数据对象, 读取失败则记录错误.
     * 其余类型记录是否已注册, 并从块头读取未压缩 NBT 字节数, 不解码 payload.
     * 某个块头损坏时仅将该类型标为读取失败, 其余类型继续展示.
     *
     * @param snapshot 要展示的快照
     * @return 原快照和只读的预览结果表, 每个类型对应可展示, 读取失败或不支持预览三种结果之一
     */
    @NotNull
    private SnapshotDetailResult.Ready prepare(@NotNull Snapshot snapshot) {
        return this.prepare(snapshot, SnapshotDetails::supportsPreview);
    }

    /**
     * 为选中的类型准备内容预览, 其余类型只读取块头大小.
     *
     * @param snapshot 正文解码得到的快照, 可包含尚未校验的原始块
     * @param selected 本次需要读取内容的类型
     * @return 按来源顺序排列的只读预览表, 未注册类型的处理状态来自本服名单
     */
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
                    // 未适配类型也需要读取块头显示大小, 单块损坏只影响这一项预览.
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

    // 声明当前详情展示已经适配的类型, 本服未注册类型保持未适配状态.
    private static boolean supportsPreview(@Nullable PlayerDataType<?> type) {
        return type instanceof InventoryDataType || type instanceof EnderChestDataType
                || type instanceof ExperienceDataType || type instanceof HealthDataType
                || type instanceof HungerDataType || type instanceof GameModeDataType
                || type instanceof EnchantmentSeedDataType || type instanceof LocationDataType
                || type instanceof VaultDataType;
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

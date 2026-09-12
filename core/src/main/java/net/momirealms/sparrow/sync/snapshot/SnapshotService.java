package net.momirealms.sparrow.sync.snapshot;

import net.minecraft.nbt.CompoundTag;
import net.momirealms.sparrow.sync.player.PlayerIdentity;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotApplyResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotCaptureResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotDeleteResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotExportResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotImportResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotLoadResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotPinResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotRestoreResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotSaveResult;
import net.momirealms.sparrow.sync.snapshot.operation.SnapshotUnpinResult;
import net.momirealms.sparrow.sync.snapshot.trigger.SaveTriggerListener;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class SnapshotService {
    private final SparrowSync plugin; // 业务对象的生命周期与共享依赖
    private StorageProvider storage; // 简单元数据管理操作
    private SnapshotSaver saver; // 采集与保存交接
    private SnapshotApplier applier; // 正式加载和玩家应用
    private SnapshotRestorer restorer; // 在线、离线历史恢复
    private SnapshotTransfer transfer; // 单份导入与导出
    private SnapshotFiles files; // 本服普通快照、本地待重试的快照与异常快照的文件入口
    private SnapshotDetails details; // 单份快照的选择性预览

    public SnapshotService(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public void onLoad() {
        this.storage = this.plugin.storageProvider();
        this.files = new SnapshotFiles(this.plugin.dataFolderPath(), this.plugin.binaryCodec(), this.plugin.logger());
        this.details = new SnapshotDetails(this.storage, this.files, this.plugin.dataRegistry(), this.plugin.scheduler().async());
        this.saver = new SnapshotSaver(this.plugin);
        this.applier = new SnapshotApplier(this.plugin);
        this.restorer = new SnapshotRestorer(this.plugin, this.saver, this.applier);
        this.transfer = new SnapshotTransfer(this.storage, this.files, this.plugin.scheduler().async(), this.plugin.snapshotCache());
    }

    public void onDelayedEnable() {
        Bukkit.getPluginManager().registerEvents(new SaveTriggerListener(this.plugin, this.plugin.sessionManager()), this.plugin.javaPlugin());
    }

    /**
     * 读取玩家最新快照并完成地图准备与类型解码, 供登录流程消费.
     *
     * @param player 目标玩家 UUID
     * @param playerName 日志和快照文件名使用的玩家名
     * @return 异步加载结果, 没有历史快照时为 Empty
     */
    @NotNull
    @ApiStatus.Internal
    public CompletableFuture<SnapshotLoadResult> loadLatest(@NotNull UUID player, @NotNull String playerName) {
        return this.applier.loadLatest(player, playerName);
    }

    /**
     * 在登录 Gate 阶段将待应用值写入原版登录数据源.
     *
     * @param session 本次操作所属的玩家会话
     * @param localData 本地原版登录数据
     * @param loaded 当前请求已准备的快照和应用 Context
     * @return 供原版登录加载使用的数据, 保留空数据的新玩家语义
     */
    @NotNull
    @ApiStatus.Internal
    public Optional<CompoundTag> applyNative(@NotNull PlayerSession session, @NotNull Optional<CompoundTag> localData, @NotNull SnapshotLoadResult.Ready loaded) {
        return this.applier.applyNative(session, localData, loaded);
    }

    /**
     * 在玩家线程消费登录准备留下的待应用值和 Native 交接回调.
     *
     * @param player 当前操作绑定的玩家对象
     * @param loaded 当前请求已准备的快照和应用 Context
     * @return 本次 Join 的应用结果
     */
    @NotNull
    @ApiStatus.Internal
    public SnapshotApplyResult applyOnJoin(@NotNull Player player, @NotNull SnapshotLoadResult.Ready loaded) {
        return this.applier.applyOnJoin(player, loaded);
    }

    /**
     * 在玩家线程采集 ACTIVE 玩家的当前状态, 回执等待存储结果.
     *
     * @param player 当前操作绑定的玩家对象
     * @return 采集成功的快照 ID, 或取消、离线、保存失败结果
     */
    @NotNull
    public CompletableFuture<SnapshotCaptureResult> capture(@NotNull Player player) {
        return this.saver.capture(player);
    }

    /**
     * 在当前玩家线程采集全部数据, 随后由玩家串行线程完成编码与提交.
     *
     * @param player 当前操作绑定的玩家对象
     * @param cause 本次保存原因
     * @param retainedData 会话保留的未注册类型原数据
     * @return 本次保存的最终结果
     */
    @NotNull
    @ApiStatus.Internal
    public CompletableFuture<SnapshotSaveResult> captureNowAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull SnapshotData retainedData) {
        return this.saver.captureNowAndSave(player, cause, retainedData);
    }

    /**
     * 先在玩家线程采集必须同步采集的数据类型, 再由玩家串行线程采集可异步的数据类型并保存.
     *
     * @param player 当前操作绑定的玩家对象
     * @param cause 本次保存原因
     * @param retainedData 会话保留的未注册类型原数据
     * @return 两个采集阶段及后续保存的最终结果
     */
    @NotNull
    @ApiStatus.Internal
    public CompletableFuture<SnapshotSaveResult> captureLaterAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull SnapshotData retainedData) {
        return this.saver.captureLaterAndSave(player, cause, retainedData);
    }

    /**
     * 将已退出服务器的玩家交给串行线程直接采集, 任务会持有退出后的 Player 对象.
     *
     * @param player 当前操作绑定的玩家对象
     * @param cause 本次保存原因
     * @param retainedData 会话保留的未注册类型原数据
     * @return 离线采集及保存的最终结果
     */
    @NotNull
    @ApiStatus.Internal
    public CompletableFuture<SnapshotSaveResult> captureLogoutAndSave(@NotNull Player player, @NotNull SaveCause cause, @NotNull SnapshotData retainedData) {
        return this.saver.captureLogoutAndSave(player, cause, retainedData);
    }

    /**
     * 恢复选定历史内容, 在线应用完成后等待新的 RESTORE 记录保存.
     *
     * @param player 当前操作绑定的玩家对象
     * @param snapshotId 明确选定的快照 ID
     * @return 恢复、离线、归属错误、取消或失败结果
     */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> restore(@NotNull Player player, @NotNull UUID snapshotId) {
        return this.restorer.restore(player, snapshotId);
    }

    /**
     * 取得玩家会话锁后写入新的 RESTORE 记录, 留待下次登录加载.
     *
     * @param player 目标玩家的名字与 UUID
     * @param snapshotId 明确选定的快照 ID
     * @return 离线恢复结果, 写入结束并归还锁后完成
     */
    @NotNull
    public CompletableFuture<SnapshotRestoreResult> restoreOffline(@NotNull PlayerIdentity player, @NotNull UUID snapshotId) {
        return this.restorer.restoreOffline(player, snapshotId);
    }

    /**
     * 查询本服是否持有该玩家的在途离线恢复任务.
     *
     * @param player 目标玩家 UUID
     * @return 是否应在登录或交接时视为仍在保存
     */
    public boolean restoringOffline(@NotNull UUID player) {
        return this.restorer.restoringOffline(player);
    }

    /**
     * 将指定数据库快照导出为完整文件.
     *
     * @param snapshotId 明确选定的快照 ID
     * @param format 导出文件格式
     * @return 导出的快照 ID 与路径, 或不存在结果
     */
    @NotNull
    public CompletableFuture<SnapshotExportResult> export(@NotNull UUID snapshotId, @NotNull SnapshotFiles.Format format) {
        return this.transfer.export(snapshotId, format);
    }

    /**
     * 导入保留原身份与时间的完整快照, 同 ID 的已有记录直接覆盖.
     *
     * @param relative 选定文件在本服目录内的相对路径
     * @return 导入、无效文件或保存失败结果
     */
    @NotNull
    public CompletableFuture<SnapshotImportResult> importFile(@NotNull String relative) {
        return this.transfer.importFile(relative);
    }

    /**
     * 固定指定快照.
     *
     * @param snapshotId 明确选定的快照 ID
     * @return 固定操作结果
     */
    @NotNull
    public CompletableFuture<SnapshotPinResult> pin(@NotNull UUID snapshotId) {
        return this.storage.setPinned(snapshotId, true).thenCompose(changed -> {
            if (changed) return CompletableFuture.completedFuture(SnapshotPinResult.PINNED);
            return this.storage.snapshot(snapshotId).thenApply(current -> current.isEmpty() ? SnapshotPinResult.NOT_FOUND : SnapshotPinResult.UNCHANGED);
        });
    }

    /**
     * 取消指定快照的固定状态.
     *
     * @param snapshotId 明确选定的快照 ID
     * @return 取消固定操作结果
     */
    @NotNull
    public CompletableFuture<SnapshotUnpinResult> unpin(@NotNull UUID snapshotId) {
        return this.storage.setPinned(snapshotId, false).thenCompose(changed -> {
            if (changed) return CompletableFuture.completedFuture(SnapshotUnpinResult.UNPINNED);
            return this.storage.snapshot(snapshotId).thenApply(current -> current.isEmpty() ? SnapshotUnpinResult.NOT_FOUND : SnapshotUnpinResult.UNCHANGED);
        });
    }

    /**
     * 删除明确 ID 的数据库快照.
     *
     * @param snapshotId 明确选定的快照 ID
     * @return 删除成功或不存在结果
     */
    @NotNull
    public CompletableFuture<SnapshotDeleteResult> delete(@NotNull UUID snapshotId) {
        return this.storage.snapshotMeta(snapshotId).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture(SnapshotDeleteResult.NOT_FOUND);
            return this.storage.deleteSnapshot(snapshotId).thenCompose(deleted -> {
                if (!deleted) return CompletableFuture.completedFuture(SnapshotDeleteResult.NOT_FOUND);
                return this.plugin.snapshotCache().invalidate(found.get().player()).thenApply(ignored -> SnapshotDeleteResult.DELETED);
            });
        });
    }

    @NotNull
    public SnapshotFiles files() {
        return this.files;
    }

    /**
     * 异步查询本服异常快照列表, 由文件对象读取异常快照头文件并完成筛选和分页.
     *
     * @param player 筛选玩家 UUID, null 表示不筛选
     * @param category 筛选目录类别, null 表示不筛选
     * @param index 从零开始的请求页码
     * @param size 每页最大条数
     * @return 当前页条目与总数, 文件读取失败时 Future 异常完成
     * @throws IllegalArgumentException 每页条数不是正数
     */
    @NotNull
    public CompletableFuture<SnapshotFiles.ExceptionPage> listExceptions(@Nullable UUID player, @Nullable String category, int index, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("exception page size must be positive: " + size);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.files.listExceptions(player, category, index, size);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, this.plugin.scheduler().async());
    }

    @NotNull
    public SnapshotDetails details() {
        return this.details;
    }

    /**
     * 停止新的管理操作, 已接收的保存及会话最终 SHUTDOWN 保存继续完成.
     */
    public void stopOperations() {
        // 启动中途失败也会进入停服回调, 仅关闭已经装配的业务对象.
        if (this.saver != null) this.saver.stopOperations();
        if (this.restorer != null) this.restorer.stopOperations();
        if (this.applier != null) this.applier.stopOperations();
    }

    /**
     * 封闭保存入口并等待已接收请求的最终结果, 连续无进展达到期限时结束等待.
     * <p>等待期间已有请求继续写入和重试.
     *
     * @param timeout 连续无进展的最长等待时间, 非正数表示不等待
     * @param unit 等待时间的单位
     * @return 是否结束全部已接收保存, 单份异常结束也计为结束
     */
    public boolean sealAndAwaitSaves(long timeout, @NotNull TimeUnit unit) {
        return this.saver == null || this.saver.sealAndAwaitSaves(timeout, unit);
    }

    /**
     * 暂存 Writer 中已生成的完整快照数据, 尚未编码的在途请求以超时失败结束.
     */
    public void stashUnsettled() {
        if (this.saver != null) {
            this.saver.stashUnsettled();
        }
    }
}

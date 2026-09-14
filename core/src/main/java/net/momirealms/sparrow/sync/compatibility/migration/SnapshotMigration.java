package net.momirealms.sparrow.sync.compatibility.migration;

import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotDump;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.util.UUIDUtils;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@ApiStatus.Internal
public final class SnapshotMigration {
    private final SnapshotFiles files;
    private final BinarySnapshotCodec codec;
    private final SnapshotDump importer;
    private final String server;

    public SnapshotMigration(@NotNull SnapshotFiles files, @NotNull BinarySnapshotCodec codec, @NotNull SnapshotDump importer, @NotNull String server) {
        this.files = files;
        this.codec = codec;
        this.importer = importer;
        this.server = server;
    }

    /**
     * 将来源插件交付的玩家数据写成完整 ZIP, 再导入 Sparrow 存储.
     * 在文件 I/O worker 中生成完整迁移 ZIP, 发布后自动导入, 返回两个阶段的独立结果.
     *
     * @param name dump 目录内的 ZIP 文件名
     * @param source 已就绪可进行迁移的数据来源
     * @param startedAt 本批开始时的毫秒时间
     * @return failure 表示生成故障, 此时 imported 为 null; 发布成功后检查 imported.failure 判断入库结果,
     *         converted / failed 分别统计写入 ZIP 和生成阶段归档的玩家, elapsedMillis 包含两阶段耗时;
     *         生成失败时 file 只是目标路径, 该位置可能仍是此前的 ZIP
     */
    @NotNull
    public Result migrate(@NotNull String name, @NotNull MigrationSource source, long startedAt) {
        return this.migrate(name, source, startedAt, progress -> {});
    }

    // 进度回调由当前 worker 随记录处理触发, 不创建额外调度任务.
    @NotNull
    public Result migrate(@NotNull String name, @NotNull MigrationSource source, long startedAt, @NotNull Consumer<Result> listener) {
        Progress progress = new Progress();
        Path target = this.files.dump().resolve(name);
        Path temporary = null;
        Path users = null;
        try {
            target = this.files.dumpFile(name);
            Path file = target;
            progress.listener = () -> listener.accept(progress.result(file, null, null));
            progress.listener.run();
            Files.createDirectories(this.files.dump());
            // 两份半成品均放在 dump 目录, 生成失败时一并清理, 正式文件留到发布时替换.
            temporary = Files.createTempFile(this.files.dump(), ".migrate-", ".tmp");
            users = Files.createTempFile(this.files.dump(), ".migrate-users-", ".tmp");
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                DataOutputStream snapshots = new DataOutputStream(zip);
                zip.putNextEntry(new ZipEntry("snapshots.bin"));
                // ZIP 同时只能写一个成员, 先将名字暂存磁盘, 玩家正文逐份写入 snapshots.bin.
                try (DataOutputStream userOutput = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(users)))) {
                    source.read(new MigrationSource.Sink() {

                        @Override
                        public void accept(@NotNull MigrationSource.PlayerData data) throws IOException {
                            progress.current = "player " + data.player();
                            // 身份在生成时固定, 后续导入直接读取包内元数据, 重跑沿用同一个 ID.
                            long timestamp = data.timestamp() == null ? startedAt : data.timestamp();
                            SnapshotMeta meta = new SnapshotMeta(UUIDUtils.timeOrdered(), data.player(), timestamp, SaveCause.MIGRATION, false, SnapshotMigration.this.server, VersionHelper.WORLD_VERSION);
                            Snapshot snapshot = new Snapshot(meta, data.data());
                            byte[] encoded;
                            try {
                                encoded = SnapshotMigration.this.codec.encode(snapshot);
                            } catch (IOException failure) {
                                // 编码没有生成完整字节, 归档诊断头和原因; 归档失败继续向外终止生成.
                                SnapshotMigration.this.files.archiveMigration(meta, data.user() == null ? null : data.user().name(), source.id(), "encode", failure, null);
                                progress.failed++;
                                progress.report();
                                return;
                            }
                            // 两类记录写入完成后再计成功, I/O 故障会使整包停留在临时状态.
                            SnapshotDump.writeSnapshot(snapshots, encoded);
                            if (data.user() != null) {
                                SnapshotDump.writeUser(userOutput, data.user());
                                progress.users++;
                            }
                            progress.converted++;
                            progress.report();
                        }

                        @Override
                        public void reject(@NotNull UUID player, @Nullable String playerName, @NotNull String stage, @NotNull Throwable failure, byte @Nullable [] raw) throws IOException {
                            progress.current = "player " + player + " " + stage;
                            // 诊断头提供列表所需的玩家身份和迁移服务器信息.
                            SnapshotMeta meta = new SnapshotMeta(UUIDUtils.timeOrdered(), player, startedAt, SaveCause.MIGRATION, false, SnapshotMigration.this.server, VersionHelper.WORLD_VERSION);
                            SnapshotMigration.this.files.archiveMigration(meta, playerName, source.id(), stage, failure, raw);
                            progress.failed++;
                            progress.report();
                        }
                    });
                    // 每个成员各有自己的 false 结束标记, 导入端据此结束连续记录读取.
                    userOutput.writeBoolean(false);
                }
                snapshots.writeBoolean(false);
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("users.bin"));
                // 名字记录从临时文件流式复制, 内存占用随复制缓冲区大小保持固定.
                Files.copy(users, zip);
                zip.closeEntry();
            }
            progress.current = "ZIP";
            // 暂存名字已进入 ZIP, 清理成功后才发布, 此前故障都仍归入生成阶段.
            Files.delete(users);
            users = null;
            SnapshotDump.publish(temporary, target);
            temporary = null;
        } catch (Exception | LinkageError failure) {
            // 外部插件接口变动也可能抛出 LinkageError, 和读取故障一样结束生成并清理半成品.
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            cleanup(users, failure);
            cleanup(temporary, failure);
            return progress.result(target, null, failure);
        }
        // 发布是两阶段边界, 后续失败保留 ZIP 与已落库记录, 重试直接读取同一文件.
        Path file = target;
        SnapshotDump.Result imported = this.importer.importFile(name, status -> listener.accept(progress.result(file, status, null)));
        return progress.result(target, imported, null);
    }

    // 清理本次生成留下的临时文件, 清理故障附加到最初的失败原因.
    private static void cleanup(@Nullable Path path, @NotNull Throwable failure) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    /** 一次同步迁移调用的结果. */
    public record Result(@NotNull Path file, long users, long converted, long failed, long elapsedMillis, @NotNull String current, @Nullable SnapshotDump.Result imported, @Nullable Throwable failure) {
    }

    /** 一次同步迁移调用的计数, 消费回调返回后才推进对应数量. */
    private static final class Progress {
        private final long started = System.nanoTime();
        private long nextReport = this.started + 1_000_000_000L;
        private Runnable listener;
        private long users;                            // 已写入暂存文件的名字记录数
        private long converted;                        // 已写入 ZIP 的完整玩家记录数
        private long failed;                           // 生成阶段已成功归档的玩家数
        private String current = "source";             // 最近处理的阶段或玩家, 供生成失败时定位

        private void report() {
            long now = System.nanoTime();
            if (now < this.nextReport) return;
            this.nextReport = now + 1_000_000_000L;
            this.listener.run();
        }

        private Result result(Path file, @Nullable SnapshotDump.Result imported, @Nullable Throwable failure) {
            return new Result(file, this.users, this.converted, this.failed, (System.nanoTime() - this.started) / 1_000_000, this.current, imported, failure);
        }
    }
}

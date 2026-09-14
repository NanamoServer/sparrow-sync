package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.cluster.cache.SnapshotCache;
import net.momirealms.sparrow.sync.map.data.MapArchiveRecord;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.local.SnapshotFiles;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.StoredUser;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@ApiStatus.Internal
public final class SnapshotDump {
    private static final int BATCH_SIZE = 16; // 每批读取的快照上限, 写完后释放引用

    private final StorageProvider storage;
    private final SnapshotFiles files;
    private final BinarySnapshotCodec codec;
    private final SnapshotCache cache;
    private final Function<MapArchiveRecord, CompletableFuture<Void>> mapImported; // 地图保存后更新缓存, 完成后再导入下一条

    public SnapshotDump(@NotNull StorageProvider storage, @NotNull SnapshotFiles files, @NotNull BinarySnapshotCodec codec, @NotNull Function<MapArchiveRecord, CompletableFuture<Void>> mapImported, @NotNull SnapshotCache cache) {
        this.storage = storage;
        this.files = files;
        this.codec = codec;
        this.mapImported = mapImported;
        this.cache = cache;
    }

    /**
     * 在文件 I/O 线程导出快照及关联记录, 完整 ZIP 写入后返回结果.
     * @param name dump 目录内的 ZIP 文件名
     * @param before 仅导出早于此 Unix 毫秒时间的快照
     * @return 文件路径、处理数量和错误, 失败时保留原有 ZIP
     */
    @NotNull
    public Result dump(@NotNull String name, long before) {
        Progress progress = new Progress();
        Path temporary = null;
        Path target = this.files.dump().resolve(name);
        try {
            target = this.files.dumpFile(name);
            Files.createDirectories(this.files.dump());
            // 先写同目录临时文件, 全部完成后再替换目标 ZIP
            temporary = Files.createTempFile(this.files.dump(), ".dump-", ".tmp");
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                DataOutputStream output = new DataOutputStream(zip);
                // 同类记录连续写入固定的 ZIP 条目, 目录大小不随记录数量增长
                zip.putNextEntry(new ZipEntry("users.bin"));
                this.writeUsers(output, progress);
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("maps.bin"));
                this.writeMaps(output, progress);
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("snapshots.bin"));
                this.writeSnapshots(output, before, progress);
                zip.closeEntry();
                // 保留地图 ID 分配进度, 包括已删除地图占用的 ID
                progress.current = "map-sequence";
                zip.putNextEntry(new ZipEntry("sequence.bin"));
                output.writeLong(this.storage.maps().sequence().join());
                zip.closeEntry();
                progress.current = "ZIP";
            }
            // ZIP 关闭成功后再替换正式文件
            publish(temporary, target);
            return progress.result(target, null);
        } catch (IOException | RuntimeException failure) {
            // 导出失败时清理临时文件, 清理错误附加到原异常
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            return progress.result(target, failure);
        }
    }

    /** 按玩家 UUID 分批写入用户名记录, 最后写入结束标记. */
    private void writeUsers(DataOutputStream output, Progress progress) throws IOException {
        UUID after = null;
        while (true) {
            progress.current = "users after " + after;
            List<StoredUser> batch = this.storage.scanUsers(after, BATCH_SIZE).join();
            if (batch.isEmpty()) break;
            for (int i = 0; i < batch.size(); i++) {
                StoredUser user = batch.get(i);
                progress.current = "user " + user.player();
                writeUser(output, user);
                after = user.player();
                progress.users++;
            }
        }
        output.writeBoolean(false);
    }

    // 从 0 向更小的全局 ID 分批导出地图, 保留原始数据和来源信息
    private void writeMaps(DataOutputStream output, Progress progress) throws IOException {
        int after = 0;
        while (true) {
            progress.current = "maps after " + after;
            List<MapArchiveRecord> batch = this.storage.maps().scan(after, BATCH_SIZE).join();
            if (batch.isEmpty()) break;
            for (int i = 0; i < batch.size(); i++) {
                MapArchiveRecord map = batch.get(i);
                progress.current = "map " + map.identity().globalId();
                output.writeBoolean(true);
                output.writeInt(map.identity().globalId());
                output.writeUTF(map.identity().source().ownerId());
                output.writeInt(map.identity().source().id());
                output.writeInt(map.dataVersion());
                output.writeLong(map.updatedAt());
                writeBytes(output, map.data());
                after = map.identity().globalId();
                progress.maps++;
            }
        }
        output.writeBoolean(false);
    }

    /** 分批写入截止时间之前的快照, 每批写完后释放数据引用. */
    private void writeSnapshots(DataOutputStream output, long before, Progress progress) throws IOException {
        UUID after = null;
        while (true) {
            progress.current = "snapshots after " + after;
            List<Snapshot> batch = this.storage.scanSnapshots(before, after, BATCH_SIZE).join();
            if (batch.isEmpty()) break;
            for (int i = 0; i < batch.size(); i++) {
                Snapshot snapshot = batch.get(i);
                progress.current = "snapshot " + snapshot.meta().id();
                // 每份快照前写入长度, 导入时据此逐条读取
                byte[] data = this.codec.encode(snapshot);
                writeSnapshot(output, data);
                after = snapshot.meta().id();
                progress.snapshots++;
            }
        }
        output.writeBoolean(false);
    }

    // 逐条导入 ZIP, 返回成功、跳过和中断情况, 保留源文件和已写入的数据
    @NotNull
    public Result importFile(@NotNull String name) {
        return this.importFile(name, progress -> {});
    }

    // 在导入线程报告进度, 成功数量只计算已完成的写入
    @NotNull
    public Result importFile(@NotNull String name, @NotNull Consumer<Result> listener) {
        Progress progress = new Progress();
        Path source = this.files.dump().resolve(name);
        try {
            source = this.files.dumpFile(name);
            Path file = source;
            progress.listener = () -> listener.accept(progress.result(file, null));
            progress.listener.run();
            if (!source.toRealPath().startsWith(this.files.dump().toRealPath())) throw new IOException("ZIP is outside the dump directory");
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
                DataInputStream input = new DataInputStream(zip);
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) throw new IOException("ZIP contains no readable members");
                do {
                    progress.current = entry.getName();
                    // 按 ZIP 条目顺序读取, 每条记录写入完成后再继续
                    switch (entry.getName()) {
                        case "users.bin" -> this.readUsers(input, progress);
                        case "maps.bin" -> this.readMaps(input, progress);
                        case "snapshots.bin" -> this.readSnapshots(input, progress);
                        case "sequence.bin" -> this.storage.maps().importSequence(input.readLong()).join();
                        default -> throw new IOException("Unknown ZIP member: " + entry.getName());
                    }
                    zip.closeEntry();
                } while ((entry = zip.getNextEntry()) != null);
            }
            return progress.result(source, null);
        } catch (IOException | RuntimeException failure) {
            return progress.result(source, failure);
        }
    }

    // 逐条写入用户名记录, 覆盖同 UUID 的记录
    private void readUsers(DataInputStream input, Progress progress) throws IOException {
        while (input.readBoolean()) {
            StoredUser user = new StoredUser(UUID.fromString(input.readUTF()), input.readUTF(), input.readLong());
            progress.current = "user " + user.player();
            this.storage.importUser(user).join();
            progress.users++;
            progress.report();
        }
    }

    // 保留原地图 ID 导入, 每条保存后等待缓存更新完成
    private void readMaps(DataInputStream input, Progress progress) throws IOException {
        while (input.readBoolean()) {
            int id = input.readInt();
            progress.current = "map " + id;
            MapSource source = new MapSource(input.readUTF(), input.readInt());
            MapArchiveRecord map = new MapArchiveRecord(new MapIdentity(source, id), input.readInt(), input.readLong(), readBytes(input));
            this.storage.maps().importMap(map).join();
            // 地图已写入数据库, 后续缓存更新失败也计为已保存
            progress.maps++;
            progress.report();
            this.mapImported.apply(map).join();
        }
    }

    // 单份快照损坏时保存到异常目录并继续, 数据库或异常文件写入失败时中断
    private void readSnapshots(DataInputStream input, Progress progress) throws IOException {
        while (input.readBoolean()) {
            progress.current = "snapshot record " + (progress.snapshots + progress.failed + 1);
            // 先读完整条记录, 损坏数据也能原样保存, 下一条由独立长度定位
            byte[] data = readBytes(input);
            DecodedSnapshot decoded = this.codec.decode(data);
            if (decoded instanceof DecodedSnapshot.Invalid invalid) {
                // 保存损坏字节和原因后, 才计入跳过数量
                this.files.archiveImport(data, null, "corrupted", invalid.reason() + ": " + invalid.detail(), null);
                progress.failed++;
                progress.report();
                continue;
            }
            Snapshot snapshot = ((DecodedSnapshot.Valid) decoded).snapshot();
            progress.current = "snapshot " + snapshot.meta().id();
            // 写库前逐块完成校验、解压和 NBT 解析, 任一块损坏则保留为异常快照
            try {
                for (var key : snapshot.keys()) {
                    snapshot.data(key);
                }
            } catch (UncheckedIOException failure) {
                this.files.archiveImport(data, snapshot.meta(), "malformed", failure.getCause().toString(), failure.getCause());
                progress.failed++;
                progress.report();
                continue;
            }
            StorageProvider.SaveOutcome saved = this.storage.importSnapshot(snapshot).join();
            if (saved.result().stored()) {
                // 写入数据库并完成缓存删除尝试后, 才计入成功数量
                this.cache.invalidate(snapshot.meta().player()).join();
                progress.snapshots++;
                progress.report();
            } else if (saved.result().retriable()) {
                // 数据库暂不可用时停止导入, 恢复后可用原 ZIP 重试
                throw new IOException("Database write failed for snapshot " + snapshot.meta().id(), saved.failure());
            } else {
                // 按存储拒绝原因选择异常目录, 同时保留快照信息
                String category = saved.result() == StorageProvider.SaveResult.REJECTED_OVERSIZED ? "oversized" : "malformed";
                String reason = saved.failure() == null ? saved.result().name() : saved.failure().toString();
                this.files.archiveImport(data, snapshot.meta(), category, reason, saved.failure());
                progress.failed++;
                progress.report();
            }
        }
    }

    /**
     * 写入一条用户名记录, 导出和迁移共用此格式.
     * @param output 记录流, <strong>调用方在整组记录结束后写入 false</strong>
     */
    public static void writeUser(@NotNull DataOutputStream output, @NotNull StoredUser user) throws IOException {
        output.writeBoolean(true);
        output.writeUTF(user.player().toString());
        output.writeUTF(user.name());
        output.writeLong(user.lastSeen());
    }

    /**
     * 写入长度和完整快照字节, 供导入时逐条读取.
     * @param output 记录流, <strong>调用方在整组记录结束后写入 false</strong>
     */
    public static void writeSnapshot(@NotNull DataOutputStream output, byte @NotNull [] data) throws IOException {
        output.writeBoolean(true);
        writeBytes(output, data);
    }

    /**
     * 用已写完的临时 ZIP 替换同目录的目标文件.
     * @throws IOException 文件替换失败时, 由调用方清理临时文件
     */
    public static void publish(@NotNull Path temporary, @NotNull Path target) throws IOException {
        // 优先原子替换, 文件系统不支持时使用普通替换
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // 四字节长度后紧跟完整记录
    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    // 一次只读取一条记录, 字节不足时按 ZIP 截断处理
    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        byte[] data = input.readNBytes(length);
        if (data.length != length) throw new EOFException("Incomplete binary record");
        return data;
    }

    public record Result(@NotNull Path file, long users, long maps, long snapshots, long failed, long elapsedMillis, @NotNull String current, @Nullable Throwable failure) {
    }

    private static final class Progress {
        private final long started = System.nanoTime();
        private long nextReport = this.started + 5_000_000_000L;
        private Runnable listener;
        private long users;
        private long maps;
        private long snapshots;
        private long failed; // 已成功写入异常目录并跳过的快照数
        private String current = "ZIP"; // 当前阶段或记录 ID, 用于定位错误

        private void report() {
            long now = System.nanoTime();
            if (now < this.nextReport) return;
            this.nextReport = now + 5_000_000_000L;
            this.listener.run();
        }

        private Result result(Path file, @Nullable Throwable failure) {
            return new Result(file, this.users, this.maps, this.snapshots, this.failed, (System.nanoTime() - this.started) / 1_000_000, this.current, failure);
        }
    }
}

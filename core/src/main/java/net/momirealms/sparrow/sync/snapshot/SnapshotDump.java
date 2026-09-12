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
    private static final int BATCH_SIZE = 4; // 每批正文读取上限, 写完后释放.

    private final StorageProvider storage;
    private final SnapshotFiles files;
    private final BinarySnapshotCodec codec;
    private final SnapshotCache cache;
    private final Function<MapArchiveRecord, CompletableFuture<Void>> mapImported; // 地图落库后的缓存发布回调, 完成后再导入下一条

    public SnapshotDump(@NotNull StorageProvider storage, @NotNull SnapshotFiles files, @NotNull BinarySnapshotCodec codec, @NotNull Function<MapArchiveRecord, CompletableFuture<Void>> mapImported, @NotNull SnapshotCache cache) {
        this.storage = storage;
        this.files = files;
        this.codec = codec;
        this.mapImported = mapImported;
        this.cache = cache;
    }

    /**
     * 在文件 I/O worker 中导出指定时间之前的快照及配套记录, 完整 ZIP 发布后返回结果.
     *
     * @param name dump 目录内的 ZIP 文件名
     * @param before 快照时间的排他上限, 使用命令开始时的 Unix 毫秒时间
     * @return 文件位置、已处理数量及失败信息; 生成失败时此前的正式 ZIP 保留
     */
    @NotNull
    public Result dump(@NotNull String name, long before) {
        Progress progress = new Progress();
        Path temporary = null;
        Path target = this.files.dump().resolve(name);
        try {
            target = this.files.dumpFile(name);
            Files.createDirectories(this.files.dump());
            // 临时文件与正式 ZIP 位于同一目录, 整份归档写完后再替换目标.
            temporary = Files.createTempFile(this.files.dump(), ".dump-", ".tmp");
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                DataOutputStream output = new DataOutputStream(zip);
                // 固定数量的 ZIP 成员承载连续记录, ZIP 目录也保持固定内存占用.
                zip.putNextEntry(new ZipEntry("users.bin"));
                this.writeUsers(output, progress);
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("maps.bin"));
                this.writeMaps(output, progress);
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("snapshots.bin"));
                this.writeSnapshots(output, before, progress);
                zip.closeEntry();
                // 保存分配计数, 已删除地图曾占用的 ID 也包含在导入后的分配进度中.
                progress.current = "map-sequence";
                zip.putNextEntry(new ZipEntry("sequence.bin"));
                output.writeLong(this.storage.maps().sequence().join());
                zip.closeEntry();
                progress.current = "ZIP";
            }
            // ZIP 完整关闭后才发布正式文件, 之前的成功归档保留到此时.
            publish(temporary, target);
            return progress.result(target, null);
        } catch (IOException | RuntimeException failure) {
            // 任一阶段失败即终止导出并清理半成品, 清理故障附加到原始失败中.
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

    /**
     * 按玩家 UUID 分批写入名字映射, 并结束当前记录流.
     *
     * @param output 已打开的 users.bin 成员
     * @param progress 导出数量与当前记录位置
     * @throws IOException 记录写入失败
     */
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

    // 从 0 向更小的全局 ID 分批导出地图, 保留原始数据及来源、版本和更新时间.
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

    /**
     * 写入截止时间之前的完整快照, 每批处理完后释放正文引用.
     *
     * @param output 已打开的 snapshots.bin 成员
     * @param before 快照时间的排他上限, 整次导出使用同一个值
     * @param progress 导出数量与当前记录位置
     */
    private void writeSnapshots(DataOutputStream output, long before, Progress progress) throws IOException {
        UUID after = null;
        while (true) {
            progress.current = "snapshots after " + after;
            List<Snapshot> batch = this.storage.scanSnapshots(before, after, BATCH_SIZE).join();
            if (batch.isEmpty()) break;
            for (int i = 0; i < batch.size(); i++) {
                Snapshot snapshot = batch.get(i);
                progress.current = "snapshot " + snapshot.meta().id();
                // 导出时直接复制未修改的数据块; 每份快照前写入长度, 供导入时从 ZIP 中逐条读取.
                byte[] data = this.codec.encode(snapshot);
                writeSnapshot(output, data);
                after = snapshot.meta().id();
                progress.snapshots++;
            }
        }
        output.writeBoolean(false);
    }

    // 在文件 I/O worker 中逐条导入, 返回成功、归档跳过和中断情况, 源 ZIP 与已落库记录保留.
    @NotNull
    public Result importFile(@NotNull String name) {
        return this.importFile(name, progress -> {});
    }

    // 进度在当前导入 worker 中报告, 成功数量只包含已经完成的写入.
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
                    // 按 ZIP 成员顺序消费记录, 每项数据库写入完成后才继续读取.
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

    // 逐条覆盖玩家名字映射, 每次写入成功后累计用户数.
    private void readUsers(DataInputStream input, Progress progress) throws IOException {
        while (input.readBoolean()) {
            StoredUser user = new StoredUser(UUID.fromString(input.readUTF()), input.readUTF(), input.readLong());
            progress.current = "user " + user.player();
            this.storage.importUser(user).join();
            progress.users++;
            progress.report();
        }
    }

    // 按原身份导入地图, 每条落库后等待缓存发布回调完成.
    private void readMaps(DataInputStream input, Progress progress) throws IOException {
        while (input.readBoolean()) {
            int id = input.readInt();
            progress.current = "map " + id;
            MapSource source = new MapSource(input.readUTF(), input.readInt());
            MapArchiveRecord map = new MapArchiveRecord(new MapIdentity(source, id), input.readInt(), input.readLong(), readBytes(input));
            this.storage.maps().importMap(map).join();
            // 地图计数表示已落库数量, 后续缓存发布失败时仍保留这条成功记录.
            progress.maps++;
            progress.report();
            this.mapImported.apply(map).join();
        }
    }

    // 单份快照的数据故障归档后继续, 数据库故障和异常文件写入失败交给整次导入处理.
    private void readSnapshots(DataInputStream input, Progress progress) throws IOException {
        while (input.readBoolean()) {
            progress.current = "snapshot record " + (progress.snapshots + progress.failed + 1);
            // 先读完当前记录, 损坏的快照正文仍可原样归档, 下一条从独立的长度前缀开始.
            byte[] data = readBytes(input);
            DecodedSnapshot decoded = this.codec.decode(data);
            if (decoded instanceof DecodedSnapshot.Invalid invalid) {
                // 解码失败时保留原始字节和原因, 归档成功后才计入跳过数量.
                this.files.archiveImport(data, null, "corrupted", invalid.reason() + ": " + invalid.detail(), null);
                progress.failed++;
                progress.report();
                continue;
            }
            Snapshot snapshot = ((DecodedSnapshot.Valid) decoded).snapshot();
            progress.current = "snapshot " + snapshot.meta().id();
            // 逐个读取所有类型, 完成 CRC 校验, 解压和 NBT 解析后才写入数据库; 任一块损坏则归档这份快照.
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
                // 每条记录落库后结束缓存删除尝试, 再计入本次导入的成功数量.
                this.cache.invalidate(snapshot.meta().player()).join();
                progress.snapshots++;
                progress.report();
            } else if (saved.result().retriable()) {
                // 数据库暂时不可用时中断导入, 后续可使用保留的源 ZIP 重新执行.
                throw new IOException("Database write failed for snapshot " + snapshot.meta().id(), saved.failure());
            } else {
                // 存储拒绝的数据按原因归入异常目录, 保留快照元数据供后续查看.
                String category = saved.result() == StorageProvider.SaveResult.REJECTED_OVERSIZED ? "oversized" : "malformed";
                String reason = saved.failure() == null ? saved.result().name() : saved.failure().toString();
                this.files.archiveImport(data, snapshot.meta(), category, reason, saved.failure());
                progress.failed++;
                progress.report();
            }
        }
    }

    /**
     * 写入一条名字映射, 供数据库导出和来源迁移使用同一记录格式.
     *
     * @param output 当前名字记录流, 调用方在整组结束时写入 false
     * @param user 原始玩家 UUID、名字与最后上线时间
     * @throws IOException 记录写入失败
     */
    public static void writeUser(@NotNull DataOutputStream output, @NotNull StoredUser user) throws IOException {
        output.writeBoolean(true);
        output.writeUTF(user.player().toString());
        output.writeUTF(user.name());
        output.writeLong(user.lastSeen());
    }

    /**
     * 写入一份已编码快照, 长度前缀供导入端定位下一条记录.
     *
     * @param output 当前快照记录流, 调用方在整组结束时写入 false
     * @param data 保留身份与元数据的完整快照字节
     * @throws IOException 记录写入失败
     */
    public static void writeSnapshot(@NotNull DataOutputStream output, byte @NotNull [] data) throws IOException {
        output.writeBoolean(true);
        writeBytes(output, data);
    }

    /**
     * 将完整关闭的临时 ZIP 发布到正式路径, 替换此前的同名文件.
     *
     * @param temporary 与目标位于同一目录的完整临时 ZIP
     * @param target 正式 ZIP 路径
     * @throws IOException 文件替换失败, 由调用方清理临时文件
     */
    public static void publish(@NotNull Path temporary, @NotNull Path target) throws IOException {
        // 优先原子替换; 文件系统不支持时使用普通替换, 此时 ZIP 正文仍已完整写入.
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // 记录长度使用四字节整数, 随后紧跟该记录的完整字节.
    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    // 内存中只读取当前一条二进制记录, 长度不足表示归档截断并终止导入.
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
        private String current = "ZIP"; // 当前阶段或记录身份, 失败时作为控制台定位信息

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

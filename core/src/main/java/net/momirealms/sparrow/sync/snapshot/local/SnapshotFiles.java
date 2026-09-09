package net.momirealms.sparrow.sync.snapshot.local;

import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.JsonSnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.exception.ExceptionHeader;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

@ApiStatus.Internal
public final class SnapshotFiles {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneId.systemDefault());

    private final Path directory; // 普通快照的导入与导出根目录
    private final Path pending;   // 本地待重试的快照目录
    private final Path exceptions; // 本服异常快照目录
    private final BinarySnapshotCodec binaryCodec; // 共享插件配置的二进制帧编解码器

    public SnapshotFiles(@NotNull Path dataFolder, @NotNull BinarySnapshotCodec binaryCodec) {
        this.directory = dataFolder.resolve("snapshot").toAbsolutePath().normalize();
        this.pending = dataFolder.resolve("pending").toAbsolutePath().normalize();
        this.exceptions = dataFolder.resolve("exception").toAbsolutePath().normalize();
        this.binaryCodec = binaryCodec;
    }

    /**
     * 导出完整快照, 文件写入完成后返回相对于插件目录的路径.
     *
     * @param snapshot 原始元数据与类型 Tag
     * @param format 输出文件格式
     * @param playerSender 是否使用玩家输出目录
     * @return 导出文件相对于插件目录的路径
     * @throws IOException 编码或文件写入失败
     */
    @NotNull
    public String export(@NotNull Snapshot snapshot, @NotNull Format format, boolean playerSender) throws IOException {
        Path parent = (playerSender ? this.directory.resolve("output") : this.directory).resolve(snapshot.meta().player().toString());
        Files.createDirectories(parent);
        Path target = parent.resolve(snapshot.meta().id().toString() + format.suffix);
        Path temporary = Files.createTempFile(parent, ".export-", ".tmp");
        try {
            // JSON 的原生解析器只在实际转存 JSON 时初始化, 二进制暂存不依赖它.
            if (format == Format.JSON) {
                Files.writeString(temporary, new JsonSnapshotCodec().encode(snapshot));
            } else {
                Files.write(temporary, this.binaryCodec.encode(snapshot));
            }
            // 完整文件就绪后再覆盖, 写入失败时旧导出仍然可用.
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return this.directory.getParent().relativize(target).toString().replace('\\', '/');
    }

    /**
     * 读取普通快照目录内的二进制或 JSON 文件, 解码失败时保留具体原因.
     *
     * @param relative 普通快照目录内的相对路径
     * @return 快照数据解码结果, 不执行玩家类型解码
     * @throws IOException 路径不合法或文件无法读取
     */
    @NotNull
    public DecodedSnapshot read(@NotNull String relative) throws IOException {
        Path file = this.directory.resolve(relative).normalize();
        if (!file.startsWith(this.directory) || !supported(file) || !Files.isRegularFile(file)) {
            throw new IOException("Invalid snapshot file: " + relative);
        }
        if (!file.toRealPath().startsWith(this.directory.toRealPath())) {
            throw new IOException("Snapshot file is outside the snapshot directory");
        }
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")
                ? new JsonSnapshotCodec().decode(Files.readString(file)) : this.binaryCodec.decode(Files.readAllBytes(file));
    }

    /**
     * 将快照数据及快照头文件写入本地待重试的快照目录或异常快照目录.
     *
     * @param snapshot 待保存的完整数据
     * @param playerName 采集时的玩家名
     * @param category 异常类别; null 表示保存为本地待重试的快照
     * @return 写入完成的快照数据文件路径
     * @throws IOException 编码或文件写入失败
     */
    @NotNull
    public Path write(@NotNull Snapshot snapshot, @NotNull String playerName, @Nullable String category) throws IOException {
        Path directory = category == null ? this.pending : this.exceptions.resolve(category);
        Files.createDirectories(directory);
        Path body = directory.resolve(fileName(snapshot.meta(), playerName));
        Path temporary = body.resolveSibling(body.getFileName() + ".tmp");
        Files.write(temporary, this.binaryCodec.encode(snapshot));
        // 先删除旧快照头文件, 再写入快照数据和新的快照头文件.
        Files.deleteIfExists(ExceptionHeader.path(body));
        atomicMove(temporary, body);
        new ExceptionHeader(snapshot.meta(), playerName).write(body);
        return body;
    }

    /**
     * 列出本地待重试的快照目录中的文件, 包含快照数据、快照头文件和临时文件.
     *
     * @return 目录中的直接子项, 目录不存在时返回空列表
     * @throws IOException 无法列出目录
     */
    @NotNull
    public List<Path> pendingEntries() throws IOException {
        if (!Files.isDirectory(this.pending)) return List.of();
        try (Stream<Path> entries = Files.list(this.pending)) {
            return entries.toList();
        }
    }

    /**
     * 读取一份本地待重试的快照数据, 用于将本地快照重新插入数据库.
     *
     * @param body 本地待重试的快照数据文件路径
     * @return 解码成功的快照, 或包含失败原因的无效结果
     * @throws IOException 文件读取失败
     */
    @NotNull
    public DecodedSnapshot readPending(@NotNull Path body) throws IOException {
        return this.binaryCodec.decode(Files.readAllBytes(body));
    }

    /**
     * 读取指定快照对应的快照头文件.
     *
     * @param body 经本类路径校验或目录枚举得到的快照数据文件路径
     * @return 快照头文件中的元信息
     * @throws IOException 快照头文件不存在、无法读取或格式无效
     */
    @NotNull
    public ExceptionHeader header(@NotNull Path body) throws IOException {
        return ExceptionHeader.read(body);
    }

    /**
     * 读取选定的异常快照数据.
     *
     * @param relative exception 内的相对路径
     * @return 解码成功的快照, 或包含失败原因的无效结果
     * @throws IOException 路径不合法或文件读取失败
     */
    @NotNull
    public DecodedSnapshot readException(@NotNull String relative) throws IOException {
        Path body = this.exceptionFile(relative);
        return body.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")
                ? new JsonSnapshotCodec().decode(Files.readString(body)) : this.binaryCodec.decode(Files.readAllBytes(body));
    }

    /**
     * 将本地待重试的快照移入指定异常类别目录, 同时处理快照数据和快照头文件.
     *
     * @param body 本地待重试的快照数据文件路径
     * @param category 保存失败原因对应的异常类别
     * @param meta 快照数据解码得到的元信息; 无法解码时为 null
     * @throws IOException 快照数据移动失败, 或快照头文件写入、移动、删除失败
     */
    public void moveToException(@NotNull Path body, @NotNull String category, @Nullable SnapshotMeta meta) throws IOException {
        Path directory = this.exceptions.resolve(category);
        Files.createDirectories(directory);
        Path target = directory.resolve(body.getFileName());
        Files.deleteIfExists(ExceptionHeader.path(target));
        Files.move(body, target, StandardCopyOption.REPLACE_EXISTING);
        Path sourceHeader = ExceptionHeader.path(body);
        if (meta != null) {
            String playerName = null;
            try {
                playerName = this.header(body).playerName();
            } catch (IOException ignored) {
                // 使用快照数据中的元信息重建异常快照头文件, 原快照头文件无法读取时保留未知玩家名.
            }
            new ExceptionHeader(meta, playerName).write(target);
            Files.deleteIfExists(sourceHeader);
        } else if (Files.exists(sourceHeader)) {
            Files.move(sourceHeader, ExceptionHeader.path(target), StandardCopyOption.REPLACE_EXISTING);
        } else {
            new ExceptionHeader(null, null).write(target);
        }
    }

    /**
     * 删除已重新插入数据库的本地快照数据及快照头文件, 也用于清理该目录中的临时文件.
     *
     * @param body 本地待重试的快照目录中枚举得到的文件
     * @throws IOException 快照数据文件或快照头文件删除失败
     */
    public void deletePending(@NotNull Path body) throws IOException {
        Files.delete(body);
        Files.deleteIfExists(ExceptionHeader.path(body));
    }

    /**
     * 删除选定的异常快照数据及异常快照头文件, 数据文件不存在时仍删除对应的头文件.
     *
     * @param relative exception 内的相对路径
     * @return 是否删除了异常快照数据或异常快照头文件
     * @throws IOException 路径校验或文件删除失败
     */
    public boolean deleteException(@NotNull String relative) throws IOException {
        Path body = this.exceptionFile(relative);
        boolean deleted = Files.deleteIfExists(body);
        return Files.deleteIfExists(ExceptionHeader.path(body)) || deleted;
    }

    /**
     * 检查异常快照路径是否位于本服目录内, 数据文件不存在时仍允许查询或删除异常快照头文件.
     *
     * @param relative exception 目录内的相对路径
     * @return 经过目录和符号链接检查的异常快照数据文件绝对路径
     * @throws IOException 路径越出异常快照目录, 或目标文件类型不符合要求
     */
    @NotNull
    public Path exceptionFile(@NotNull String relative) throws IOException {
        Path file = this.exceptions.resolve(relative).normalize();
        if (!file.startsWith(this.exceptions) || !SnapshotFiles.supported(file)) {
            throw new IOException("Invalid exception file");
        }
        if (Files.exists(file.getParent()) && !file.getParent().toRealPath().startsWith(this.exceptions.toRealPath())) {
            throw new IOException("Exception file is outside the exception directory");
        }
        if (Files.isSymbolicLink(file) || (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Exception body is not a regular file");
        }
        return file;
    }

    /**
     * 读取异常快照头文件并返回指定页, 页码超出范围时取最近的有效页.
     *
     * @param player 筛选玩家 UUID, null 表示不筛选
     * @param category 筛选目录类别, null 表示不筛选
     * @param index 从零开始的请求页码
     * @param size 每页最大条数
     * @return 当前页条目、总数和实际页码
     * @throws IllegalArgumentException 每页条数不是正数
     * @throws IOException 目录扫描或路径校验失败
     */
    @NotNull
    public ExceptionPage listExceptions(@Nullable UUID player, @Nullable String category, int index, int size) throws IOException {
        if (size <= 0) {
            throw new IllegalArgumentException("exception page size must be positive: " + size);
        }
        List<ExceptionEntry> entries = this.scanExceptions(player, category);
        int count = entries.isEmpty() ? 1 : (entries.size() - 1) / size + 1;
        int actual = Math.clamp(index, 0, count - 1);
        int start = actual * size;
        int end = start + Math.min(size, entries.size() - start);
        return new ExceptionPage(actual, size, entries.size(), count, List.copyOf(entries.subList(start, end)));
    }

    /**
     * 列出符合玩家和类别条件的异常快照, 按时间和相对路径倒序排列.
     *
     * @param player 筛选玩家 UUID, null 表示不筛选
     * @param category 筛选目录类别, null 表示不筛选
     * @return 符合条件的异常快照列表, 只读取异常快照头文件和文件状态
     * @throws IOException 目录扫描或路径校验失败
     */
    @NotNull
    private List<ExceptionEntry> scanExceptions(@Nullable UUID player, @Nullable String category) throws IOException {
        Path directory = this.exceptions;
        if (!Files.exists(directory)) return List.of();
        Set<String> paths = new HashSet<>();
        // 分别查找异常快照数据和异常快照头文件, 只有头文件的快照也列出并标记数据缺失.
        try (Stream<Path> found = Files.walk(directory)) {
            List<Path> foundFiles = found.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).toList();
            int count = foundFiles.size();
            for (int i = 0; i < count; i++) {
                Path path = foundFiles.get(i);
                String relative = directory.relativize(path).toString().replace('\\', '/');
                if (relative.endsWith(ExceptionHeader.SUFFIX)) {
                    relative = relative.substring(0, relative.length() - ExceptionHeader.SUFFIX.length());
                }
                if (SnapshotFiles.supported(Path.of(relative))) {
                    paths.add(relative);
                }
            }
        }
        List<ExceptionEntry> entries = new ArrayList<>();
        for (String path : paths) {
            String entryCategory = path.contains("/") ? path.substring(0, path.indexOf('/')) : "";
            if (category != null && !category.equals(entryCategory)) {
                continue;
            }
            ExceptionEntry entry = this.exceptionEntry(path);
            if (player != null && (entry.header() == null || entry.header().meta() == null || !player.equals(entry.header().meta().player()))) {
                continue;
            }
            entries.add(entry);
        }
        entries.sort(Comparator.comparingLong(ExceptionEntry::timestamp).thenComparing(ExceptionEntry::path).reversed());
        return entries;
    }

    /**
     * 读取一份异常快照头文件, 区分不存在与读取失败, 并检查异常快照数据是否存在.
     *
     * @param relative 选定文件在本服目录内的相对路径
     * @return 异常快照的路径、类别、异常快照头文件读取状态及数据文件存在状态
     * @throws IOException 文件读取或路径校验失败
     */
    @NotNull
    public ExceptionEntry exceptionEntry(@NotNull String relative) throws IOException {
        Path body = this.exceptionFile(relative);
        ExceptionHeader header = null;
        HeadStatus status;
        try {
            header = this.header(body);
            status = HeadStatus.AVAILABLE;
        } catch (NoSuchFileException ignored) {
            status = HeadStatus.MISSING;
        } catch (IOException failure) {
            status = HeadStatus.UNREADABLE;
        }
        String path = this.exceptions.relativize(body).toString().replace('\\', '/');
        String category = path.contains("/") ? path.substring(0, path.indexOf('/')) : "";
        return new ExceptionEntry(path, category, header, status, Files.isRegularFile(body, LinkOption.NOFOLLOW_LINKS));
    }

    /**
     * 将写入完成的二进制快照数据移到目标路径, 文件系统不支持原子移动时使用普通替换.
     *
     * @param source 当前目录中的临时快照数据文件
     * @param target 快照数据文件的目标路径
     * @throws IOException 移动失败
     */
    private static void atomicMove(@NotNull Path source, @NotNull Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 生成保持历史命名规则的文件名, UUID 区分同时间的不同快照.
     *
     * @param meta 快照身份与时间
     * @param playerName 采集时玩家名
     * @return 快照数据文件名
     */
    @NotNull
    private static String fileName(@NotNull SnapshotMeta meta, @NotNull String playerName) {
        return playerName + "-" + meta.player()
                + "-" + meta.cause().name()
                + "-" + TIME_FORMAT.format(Instant.ofEpochMilli(meta.timestamp()))
                + "-" + meta.id() + ".snapshot";
    }

    /**
     * 检查文件是否使用支持的快照数据文件后缀.
     *
     * @param path 待检查的快照数据文件路径
     * @return 是否具有二进制或 JSON 后缀
     */
    public static boolean supported(@NotNull Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".snapshot") || name.endsWith(".json");
    }

    @NotNull
    public Path directory() {
        return this.directory;
    }

    @NotNull
    public Path pending() {
        return this.pending;
    }

    @NotNull
    public Path exceptions() {
        return this.exceptions;
    }

    /** 普通快照文件的两种转存格式. */
    public enum Format {
        BINARY(".snapshot"),
        JSON(".json");

        private final String suffix; // 导出文件使用的固定后缀

        Format(@NotNull String suffix) {
            this.suffix = suffix;
        }
    }

    /** 异常快照头文件的读取状态 */
    public enum HeadStatus {
        AVAILABLE,
        MISSING,
        UNREADABLE
    }

    /**
     * 一份异常快照的列表信息, 分别记录异常快照数据与异常快照头文件的状态.
     *
     * @param path 异常快照数据文件相对于本服异常快照目录的路径
     * @param category 路径首层类别
     * @param header 异常快照头文件中的元信息, 文件缺失或无法读取时为 null
     * @param headStatus 异常快照头文件的读取状态
     * @param bodyPresent 异常快照数据文件是否存在
     */
    public record ExceptionEntry(@NotNull String path, @NotNull String category, @Nullable ExceptionHeader header, @NotNull HeadStatus headStatus, boolean bodyPresent) {

        public boolean informationAvailable() {
            return this.header != null && this.header.meta() != null;
        }

        public long timestamp() {
            return this.informationAvailable() ? this.header.meta().timestamp() : Long.MIN_VALUE;
        }
    }

    /**
     * 本次查询得到的一页异常快照, 刷新时重新扫描目录.
     *
     * @param index 实际页码, 从零开始
     * @param size 每页最大条数
     * @param total 本次筛选后的总数
     * @param count 总页数, 空列表为一页
     * @param content 当前页的条目
     */
    public record ExceptionPage(int index, int size, int total, int count, @NotNull List<ExceptionEntry> content) {
    }
}

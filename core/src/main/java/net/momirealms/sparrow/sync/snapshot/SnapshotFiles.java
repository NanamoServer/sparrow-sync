package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.JsonSnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.exception.ExceptionHeader;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class SnapshotFiles {
    private final Path directory;
    private final Path exceptions;
    private final BinarySnapshotCodec binary;
    private final JsonSnapshotCodec json = new JsonSnapshotCodec();

    public SnapshotFiles(@NotNull Path dataFolder, @NotNull BinarySnapshotCodec binary) {
        this.directory = dataFolder.resolve("snapshot").toAbsolutePath().normalize();
        this.exceptions = dataFolder.resolve("exception").toAbsolutePath().normalize();
        this.binary = binary;
    }

    /** 写出完整快照, 返回相对于插件目录的路径. */
    @NotNull
    public String export(@NotNull Snapshot snapshot, @NotNull Format format, boolean playerSender) throws IOException {
        Path parent = (playerSender ? this.directory.resolve("output") : this.directory).resolve(snapshot.meta().player().toString());
        Files.createDirectories(parent);
        Path target = parent.resolve(snapshot.meta().id().toString() + format.suffix);
        Path temporary = Files.createTempFile(parent, ".export-", ".tmp");
        try {
            if (format == Format.JSON) {
                Files.writeString(temporary, this.json.encode(snapshot));
            } else {
                Files.write(temporary, this.binary.encode(snapshot));
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

    /** 读取 snapshot 目录内的二进制或 JSON 文件. */
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
                ? this.json.decode(Files.readString(file)) : this.binary.decode(Files.readAllBytes(file));
    }

    @NotNull
    public Path directory() {
        return this.directory;
    }

    @NotNull
    public Path exceptions() {
        return this.exceptions;
    }

    /** 按本服档案路径删除正文与伴随头, 只有头的残留档案也可清理. */
    public boolean deleteException(@NotNull String relative) throws IOException {
        Path file = this.exceptionFile(relative);
        boolean deleted = Files.deleteIfExists(file);
        return Files.deleteIfExists(ExceptionHeader.path(file)) || deleted;
    }

    /** 只在选中单份异常档案后读取正文, 头文件不参与正文解码. */
    @NotNull
    public DecodedSnapshot readException(@NotNull String relative) throws IOException {
        Path file = this.exceptionFile(relative);
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")
                ? this.json.decode(Files.readString(file)) : this.binary.decode(Files.readAllBytes(file));
    }

    /** 将档案标识限定在本服 exception 目录内, 同时允许正文已缺失的路径. */
    @NotNull
    public Path exceptionFile(@NotNull String relative) throws IOException {
        Path file = this.exceptions.resolve(relative).normalize();
        if (!file.startsWith(this.exceptions) || !supported(file)) {
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

    /** 判断正文是否使用已支持的二进制或 JSON 后缀. */
    public static boolean supported(@NotNull Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".snapshot") || name.endsWith(".json");
    }

    public enum Format {
        BINARY(".snapshot"), JSON(".json");
        private final String suffix;

        Format(String suffix) {
            this.suffix = suffix;
        }
    }
}

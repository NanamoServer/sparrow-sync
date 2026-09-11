package net.momirealms.sparrow.sync.snapshot.exception;

import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@ApiStatus.Internal
public record ExceptionHeader(@Nullable SnapshotMeta meta, @Nullable String playerName) {
    public static final String SUFFIX = ".head";
    private static final int MAGIC = 0x53534801; // SSH, 头格式版本 1

    @NotNull
    public static Path path(@NotNull Path body) {
        return body.resolveSibling(body.getFileName() + SUFFIX);
    }

    // 快照头文件单独写入, 写入失败时已保存的快照数据仍然可用.
    public void write(@NotNull Path body) throws IOException {
        Path target = path(body);
        Path temporary = Files.createTempFile(body.getParent(), ".header-", ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporary))) {
                output.writeInt(MAGIC);
                output.writeBoolean(this.meta != null);
                if (this.meta != null) {
                    output.writeUTF(this.meta.id().toString());
                    output.writeUTF(this.meta.player().toString());
                    output.writeLong(this.meta.timestamp());
                    output.writeUTF(this.meta.cause().name());
                    output.writeBoolean(this.meta.pinned());
                    output.writeUTF(this.meta.server());
                    output.writeInt(this.meta.mcDataVersion());
                }
                output.writeBoolean(this.playerName != null);
                if (this.playerName != null) {
                    output.writeUTF(this.playerName);
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    // 只解析定长字段和有长度上限的 UTF 字段, 不读取快照数据.
    @NotNull
    public static ExceptionHeader read(@NotNull Path body) throws IOException {
        Path header = path(body);
        if (Files.isSymbolicLink(header)) {
            throw new IOException("Exception header is a symbolic link");
        }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(header))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid or unsupported exception header");
            }
            SnapshotMeta meta = null;
            if (input.readBoolean()) {
                meta = new SnapshotMeta(UUID.fromString(input.readUTF()), UUID.fromString(input.readUTF()), input.readLong(),
                        SaveCause.valueOf(input.readUTF()), input.readBoolean(), input.readUTF(), input.readInt());
            }
            String name = input.readBoolean() ? input.readUTF() : null;
            if (input.read() != -1) {
                throw new IOException("Unexpected trailing exception header data");
            }
            return new ExceptionHeader(meta, name);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid exception header metadata", failure);
        }
    }
}

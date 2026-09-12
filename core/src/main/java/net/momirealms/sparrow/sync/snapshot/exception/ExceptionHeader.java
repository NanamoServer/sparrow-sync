package net.momirealms.sparrow.sync.snapshot.exception;

import net.momirealms.sparrow.sync.snapshot.data.DataKey;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@ApiStatus.Internal
public record ExceptionHeader(@Nullable SnapshotMeta meta, @Nullable String playerName, @Nullable Map<DataKey, Integer> summary) {
    public static final String SUFFIX = ".head"; // 追加在正文文件名之后, 正文缺失时仍可定位诊断信息
    private static final int VERSION = 1; // 本地头文件的版本, 独立于快照格式版本

    public ExceptionHeader {
        if (summary != null) {
            summary = Collections.unmodifiableMap(new LinkedHashMap<>(summary));
        }
    }

    public ExceptionHeader(@Nullable SnapshotMeta meta, @Nullable String playerName) {
        this(meta, playerName, null);
    }

    /**
     * 定位与正文同名的头文件.
     *
     * @param body 正文的目标路径
     * @return 在正文文件名后追加 .head 的路径
     */
    @NotNull
    public static Path path(@NotNull Path body) {
        return body.resolveSibling(body.getFileName() + SUFFIX);
    }

    /**
     * 先写临时头文件再替换目标, 写入失败时保留已保存的快照正文.
     *
     * @param body 头文件对应的正文路径
     * @throws IOException 当头文件写入或替换失败时
     */
    public void write(@NotNull Path body) throws IOException {
        Path target = path(body);
        Path temporary = Files.createTempFile(body.getParent(), ".header-", ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporary))) {
                output.writeByte(VERSION);
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
                // -1 与 0 分别表示清单不可得和有效的空清单.
                output.writeInt(this.summary == null ? -1 : this.summary.size());
                if (this.summary != null) {
                    for (var entry : this.summary.entrySet()) {
                        output.writeUTF(entry.getKey().asString());
                        output.writeInt(entry.getValue());
                    }
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

    // 读取头文件中的身份与体量摘要, 保留摘要的三种状态和类型顺序.
    @NotNull
    public static ExceptionHeader read(@NotNull Path body) throws IOException {
        Path header = path(body);
        if (Files.isSymbolicLink(header)) {
            throw new IOException("Exception header is a symbolic link");
        }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(header))) {
            int version = input.readUnsignedByte();
            if (version != VERSION) {
                throw new IOException("Unsupported exception header version: " + version);
            }
            SnapshotMeta meta = null;
            if (input.readBoolean()) {
                meta = new SnapshotMeta(UUID.fromString(input.readUTF()), UUID.fromString(input.readUTF()), input.readLong(),
                        SaveCause.valueOf(input.readUTF()), input.readBoolean(), input.readUTF(), input.readInt());
            }
            String name = input.readBoolean() ? input.readUTF() : null;
            int count = input.readInt();
            if (count < -1) {
                throw new IOException("Invalid exception summary count: " + count);
            }
            Map<DataKey, Integer> summary = null;
            if (count >= 0) {
                // 文件中的数量尚未可信, 容器随成功读取的条目增长, 截断交给流报告.
                summary = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    DataKey key = DataKey.parse(input.readUTF());
                    int rawLength = input.readInt();
                    if (rawLength < -1 || summary.putIfAbsent(key, rawLength) != null) {
                        throw new IOException("Invalid exception summary entry: " + key.asString());
                    }
                }
            }
            if (input.read() != -1) {
                throw new IOException("Unexpected trailing exception header data");
            }
            return new ExceptionHeader(meta, name, summary);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid exception header fields", failure);
        }
    }
}

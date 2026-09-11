package net.momirealms.sparrow.sync.storage.postgresql;

import net.momirealms.sparrow.sync.storage.SnapshotRow;

import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotData;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotCodec;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

final class PostgresRowSnapshotCodec implements SnapshotCodec<SnapshotRow> {
    private final BinarySnapshotCodec binary;

    PostgresRowSnapshotCodec(@NotNull BinarySnapshotCodec binary) {
        this.binary = binary;
    }

    @NotNull
    @Override
    public SnapshotRow encode(@NotNull Snapshot snapshot) throws IOException {
        return new SnapshotRow(snapshot.meta(), CURRENT_VERSION, this.binary.frameData(snapshot.allData()));
    }

    @NotNull
    @Override
    public DecodedSnapshot decode(@NotNull SnapshotRow row) {
        // 行格式支持版本, 先判定列版本再读取数据帧.
        if (row.format() != CURRENT_VERSION) {
            return new DecodedSnapshot.Invalid(InvalidReason.UNSUPPORTED_FORMAT, "row snapshot format " + row.format() + ", supported: " + CURRENT_VERSION);
        }
        try {
            SnapshotData data = this.binary.deframeData(row.data());
            // deframeData 成功后帧头长度已校验, 列版本还需与帧头中的无符号版本字节一致.
            if ((row.data()[2] & 0xFF) != row.format()) {
                return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, "row and data frame formats differ");
            }
            return new DecodedSnapshot.Valid(new Snapshot(row.meta(), data));
        } catch (FormatException exception) {
            // 帧解码给出的具体错误保留给上层, 其他解析异常归为内容损坏.
            return new DecodedSnapshot.Invalid(exception.reason(), String.valueOf(exception.getMessage()));
        } catch (Exception exception) {
            return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, String.valueOf(exception.getMessage()));
        }
    }
}

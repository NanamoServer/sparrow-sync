package net.momirealms.sparrow.sync.storage.mysql;

import net.momirealms.sparrow.sync.storage.SnapshotRow;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotCodec;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class RowSnapshotCodec implements SnapshotCodec<SnapshotRow> {
    private final BinarySnapshotCodec binary;

    RowSnapshotCodec(@NotNull BinarySnapshotCodec binary) {
        this.binary = binary;
    }

    // 将快照拆成可独立查询的元信息与带帧头的数据字节.
    @NotNull
    @Override
    public SnapshotRow encode(@NotNull Snapshot snapshot) throws IOException {
        // 完整 DataKey 文本作为标签名, 未注册的数据类型也能原样保存.
        CompoundTag data = NBT.createCompound();
        for (Map.Entry<DataKey, Tag> entry : snapshot.allData().entrySet()) {
            data.put(entry.getKey().asString(), entry.getValue());
        }
        return new SnapshotRow(snapshot.meta(), CURRENT_VERSION, this.binary.frame(data));
    }

    // 将数据库行恢复为快照, 数据格式或内容错误以无效结果返回.
    @NotNull
    @Override
    public DecodedSnapshot decode(@NotNull SnapshotRow row) {
        // 行格式支持版本, 先判定列版本再读取数据帧.
        if (row.format() != CURRENT_VERSION) {
            return new DecodedSnapshot.Invalid(InvalidReason.UNSUPPORTED_FORMAT, "row snapshot format " + row.format() + ", supported: " + CURRENT_VERSION);
        }
        try {
            // 二进制编码器先校验帧头和压缩格式, 行数据的根标签应当是 CompoundTag.
            if (!(this.binary.deframe(row.data()) instanceof CompoundTag values)) {
                return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, "data tag is not a compound");
            }
            // deframe 成功后帧头长度已校验, 列版本还需与帧头中的无符号版本字节一致.
            if ((row.data()[2] & 0xFF) != row.format()) {
                return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, "row and data frame formats differ");
            }
            // 按持久化标签名恢复 DataKey, 保留尚未注册类型的数据.
            Map<DataKey, Tag> data = new LinkedHashMap<>();
            for (Map.Entry<String, Tag> entry : values.entrySet()) {
                data.put(DataKey.parse(entry.getKey()), entry.getValue());
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

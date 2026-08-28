package net.momirealms.sparrow.sync.codec;

import net.momirealms.sparrow.nbt.ByteArrayTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.codec.ops.BsonOps;
import net.momirealms.sparrow.sync.exception.FormatException;
import net.momirealms.sparrow.sync.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataDeclaration;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bson.Document;
import org.bson.types.Binary;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MongoDB 文档形态的快照编解码, 元数据与 STRUCTURED 数据排布为原生 BSON 字段, BINARY 数据交给 {@link BinarySnapshotCodec} 封为自带版本与压缩标识的字节帧.
 * 文档含原生 UUID 字段, <strong>读写两侧必须以 UuidRepresentation.STANDARD 配置 Mongo 驱动</strong>.
 */
public final class DocumentSnapshotCodec implements SnapshotCodec<Document> {
    private static final String FIELD_PLAYER = "player";
    private static final String FIELD_VERSION = "version";
    private static final String FIELD_TIMESTAMP = "ts";
    private static final String FIELD_CAUSE = "cause";
    private static final String FIELD_PINNED = "pinned";
    private static final String FIELD_SERVER = "server";
    private static final String FIELD_FORMAT = "format";
    private static final String FIELD_MC_DATA = "mcData";
    private static final String FIELD_DATA = "data";

    private final DataRegistry registry;
    private final BinarySnapshotCodec binary;

    public DocumentSnapshotCodec(@NotNull DataRegistry registry, @NotNull BinarySnapshotCodec binary) {
        this.registry = registry;
        this.binary = binary;
    }

    @Override
    @NotNull
    public Document encode(@NotNull Snapshot snapshot) throws IOException {
        SnapshotMeta meta = snapshot.meta();
        Document document = new Document();
        document.append(FIELD_PLAYER, meta.player());
        document.append(FIELD_VERSION, meta.version());
        document.append(FIELD_TIMESTAMP, new Date(meta.timestamp()));
        document.append(FIELD_CAUSE, meta.cause().name());
        document.append(FIELD_PINNED, meta.pinned());
        document.append(FIELD_SERVER, meta.server());
        document.append(FIELD_FORMAT, CURRENT_VERSION);
        document.append(FIELD_MC_DATA, meta.mcDataVersion());
        Document data = new Document();
        for (Map.Entry<DataKey, Tag> entry : snapshot.data().entrySet()) {
            data.append(entry.getKey().asString(), this.toDocumentValue(entry.getKey(), entry.getValue()));
        }
        document.append(FIELD_DATA, data);
        return document;
    }

    @Override
    @NotNull
    public DecodedSnapshot decode(@NotNull Document encoded) {
        try {
            // 元数据宽容读取, 数值字段接受任何 Number 形态, 只有缺失才算损坏
            if (!(encoded.get(FIELD_FORMAT) instanceof Number formatNumber)) {
                return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, "missing or non-numeric format field");
            }
            int version = formatNumber.intValue();
            if (version < 1 || version > CURRENT_VERSION) {
                return new DecodedSnapshot.Invalid(InvalidReason.UNSUPPORTED_FORMAT, "snapshot format " + version + ", supported up to " + CURRENT_VERSION);
            }
            UUID player = encoded.get(FIELD_PLAYER, UUID.class);
            if (player == null) {
                return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, "missing player field");
            }
            SnapshotMeta meta = new SnapshotMeta(
                    player,
                    encoded.get(FIELD_VERSION) instanceof Number snapshotVersion ? snapshotVersion.longValue() : 0L,
                    readTimestamp(encoded.get(FIELD_TIMESTAMP)),
                    SaveCause.byName(readString(encoded.get(FIELD_CAUSE))),
                    readBoolean(encoded.get(FIELD_PINNED)),
                    readString(encoded.get(FIELD_SERVER)),
                    encoded.get(FIELD_MC_DATA) instanceof Number mcData ? mcData.intValue() : 0
            );
            Map<DataKey, Tag> data = new LinkedHashMap<>();
            Document values = encoded.get(FIELD_DATA, Document.class);
            if (values != null) {
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    // null 字段视为缺失, EndTag 进入快照会截断二进制帧
                    if (entry.getValue() == null) continue;
                    DataKey key = DataKey.parse(entry.getKey());
                    data.put(key, this.fromDocumentValue(key, entry.getValue()));
                }
            }
            return new DecodedSnapshot.Valid(new Snapshot(meta, data));
        } catch (FormatException exception) {
            return new DecodedSnapshot.Invalid(exception.reason(), String.valueOf(exception.getMessage()));
        } catch (Exception exception) {
            return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, String.valueOf(exception.getMessage()));
        }
    }

    private Object toDocumentValue(DataKey key, Tag tag) throws IOException {
        DataDeclaration declaration = this.registry.declaration(key);
        if (declaration != null && declaration.storage() == StorageFormat.BINARY) {
            return new Binary(this.binary.frame(tag));
        }
        // 未注册的二进制字段原样透传, 内容不解释
        if (declaration == null && tag instanceof ByteArrayTag bytes) {
            return new Binary(bytes.value());
        }
        return NBTOps.INSTANCE.convertTo(BsonOps.INSTANCE, tag);
    }

    // 以值的实际类型为准还原, 写读两侧注册形态不一致时字段仍可读, 不拖垮整份快照
    private Tag fromDocumentValue(DataKey key, Object value) throws IOException {
        if (value instanceof Binary || value instanceof byte[]) {
            DataDeclaration declaration = this.registry.declaration(key);
            if (declaration != null && declaration.storage() == StorageFormat.BINARY) {
                try {
                    return this.binary.deframe(binaryBytes(value));
                } catch (FormatException exception) {
                    throw new FormatException(exception.reason(), exception.getMessage() + " (field " + key.asString() + ")");
                }
            }
            return NBT.createByteArray(binaryBytes(value));
        }
        return BsonOps.INSTANCE.convertTo(NBTOps.INSTANCE, value);
    }

    private static byte[] binaryBytes(Object value) {
        return value instanceof Binary binary ? binary.getData() : (byte[]) value;
    }

    private static long readTimestamp(Object value) {
        if (value instanceof Date date) return date.getTime();
        if (value instanceof Number number) return number.longValue();
        return 0L;
    }

    private static String readString(Object value) {
        return value instanceof String string ? string : "";
    }

    private static boolean readBoolean(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return false;
    }
}

package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.sync.snapshot.codec.upgrade.SnapshotUpgradePipeline;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import org.bson.Document;
import org.bson.types.Binary;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Date;
import java.util.UUID;

/**
 * MongoDB 文档形态的快照编解码, 元数据保留为 BSON 字段, 整份 data 保存为一个 NBT 二进制帧.
 * 文档含 BSON UUID 字段, <strong>读写两侧必须以 UuidRepresentation.STANDARD 配置 Mongo 驱动</strong>.
 */
public final class DocumentSnapshotCodec implements SnapshotCodec<Document> {
    public static final String FIELD_ID = "_id";
    public static final String FIELD_PLAYER = "player";
    public static final String FIELD_TIMESTAMP = "ts";
    public static final String FIELD_CAUSE = "cause";
    public static final String FIELD_PINNED = "pinned";
    public static final String FIELD_SERVER = "server";
    public static final String FIELD_FORMAT = "format";
    public static final String FIELD_MC_DATA = "mcData";
    public static final String FIELD_DATA = "data";

    private SparrowSync plugin;
    private BinarySnapshotCodec binary;

    public DocumentSnapshotCodec(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public DocumentSnapshotCodec(@NotNull BinarySnapshotCodec binary) {
        this.binary = binary;
    }

    /** 绑定启动期创建完成的二进制 codec. */
    public void onLoad() {
        this.binary = this.plugin.binaryCodec();
    }

    @Override
    @NotNull
    public Document encode(@NotNull Snapshot snapshot) throws IOException {
        SnapshotMeta meta = snapshot.meta();
        Document document = new Document();
        document.append(FIELD_ID, meta.id());
        document.append(FIELD_PLAYER, meta.player());
        document.append(FIELD_TIMESTAMP, new Date(meta.timestamp()));
        document.append(FIELD_CAUSE, meta.cause().name());
        document.append(FIELD_PINNED, meta.pinned());
        document.append(FIELD_SERVER, meta.server());
        document.append(FIELD_FORMAT, CURRENT_VERSION);
        document.append(FIELD_MC_DATA, meta.mcDataVersion());
        // 数据体独立封帧, 元数据保留在文档外层供列表和索引查询.
        document.append(FIELD_DATA, new Binary(this.binary.frameData(snapshot.allData())));
        return document;
    }

    @Override
    @NotNull
    public DecodedSnapshot decode(@NotNull Document encoded) {
        try {
            if (!(encoded.get(FIELD_FORMAT) instanceof Number formatNumber)) {
                return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, "missing or non-numeric format field");
            }
            int format = formatNumber.intValue();
            // 仅在支持范围内进入读取流程, 历史开发格式与未来版本均拒绝.
            if (format < MINIMUM_SUPPORTED_VERSION || format > CURRENT_VERSION) {
                return new DecodedSnapshot.Invalid(InvalidReason.UNSUPPORTED_FORMAT, "snapshot format " + format + ", supported range " + MINIMUM_SUPPORTED_VERSION + ".." + CURRENT_VERSION);
            }
            Document document = SnapshotUpgradePipeline.upgrade(encoded, format);
            SnapshotMeta meta = decodeMeta(document);
            byte[] bytes = switch (document.get(FIELD_DATA)) {
                case Binary binary -> binary.getData();
                case byte[] payload -> payload;
                case null, default -> throw new IOException("missing or non-binary data field");
            };
            return new DecodedSnapshot.Valid(new Snapshot(meta, this.binary.deframeData(bytes)));
        } catch (FormatException exception) {
            return new DecodedSnapshot.Invalid(exception.reason(), String.valueOf(exception.getMessage()));
        } catch (Exception exception) {
            return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, String.valueOf(exception.getMessage()));
        }
    }

    /**
     * 解码文档的元数据部分, 供存储层用排除 data 的投影查询快照列表.
     *
     * @throws IllegalArgumentException 当文档缺少 player 或快照 id 字段时
     */
    @NotNull
    public static SnapshotMeta decodeMeta(@NotNull Document document) {
        UUID player = document.get(FIELD_PLAYER, UUID.class);
        if (player == null) throw new IllegalArgumentException("missing player field");
        UUID id = document.get(FIELD_ID, UUID.class);
        if (id == null) throw new IllegalArgumentException("missing snapshot id field");
        // 数值字段接受任何 Number 形态
        return new SnapshotMeta(
                id,
                player,
                readTimestamp(document.get(FIELD_TIMESTAMP)),
                SaveCause.byName(readString(document.get(FIELD_CAUSE))),
                readBoolean(document.get(FIELD_PINNED)),
                readString(document.get(FIELD_SERVER)),
                document.get(FIELD_MC_DATA) instanceof Number mcData ? mcData.intValue() : 0
        );
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

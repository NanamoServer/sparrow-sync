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
 * MongoDB 快照格式, 元数据使用 BSON 字段, data 保存二进制数据帧.
 * <strong>MongoDB 驱动的 UUID 读写必须使用 UuidRepresentation.STANDARD</strong>.
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
    private SnapshotDataCodec dataCodec;

    public DocumentSnapshotCodec(@NotNull SparrowSync plugin) {
        this.plugin = plugin;
    }

    public DocumentSnapshotCodec(@NotNull SnapshotDataCodec dataCodec) {
        this.dataCodec = dataCodec;
    }

    public void onLoad() {
        this.dataCodec = this.plugin.dataCodec();
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
        // 元数据与 data 分开存储, 查询列表时可以排除 data
        document.append(FIELD_DATA, new Binary(this.dataCodec.encode(snapshot.content())));
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
            // 只读取支持范围内的格式版本
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
            return new DecodedSnapshot.Valid(new Snapshot(meta, this.dataCodec.decode(bytes)));
        } catch (FormatException exception) {
            return new DecodedSnapshot.Invalid(exception.reason(), String.valueOf(exception.getMessage()));
        } catch (Exception exception) {
            return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, String.valueOf(exception.getMessage()));
        }
    }

    /**
     * 只读取文档元数据, 供排除 data 的列表查询使用.
     * @throws IllegalArgumentException 缺少 player 或 id 时
     */
    @NotNull
    public static SnapshotMeta decodeMeta(@NotNull Document document) {
        UUID player = document.get(FIELD_PLAYER, UUID.class);
        if (player == null) throw new IllegalArgumentException("missing player field");
        UUID id = document.get(FIELD_ID, UUID.class);
        if (id == null) throw new IllegalArgumentException("missing snapshot id field");
        // 数值字段按 Number 读取
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

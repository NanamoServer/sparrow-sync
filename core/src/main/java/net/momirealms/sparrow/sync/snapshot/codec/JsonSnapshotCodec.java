package net.momirealms.sparrow.sync.snapshot.codec;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.TagParser;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.nbt.visitor.CompactStringTagVisitor;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.proxy.minecraft.nbt.TagParserProxy;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.codec.upgrade.SnapshotUpgradePipeline;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bson.Document;
import org.bson.json.JsonWriterSettings;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * 人工可读的 JSON 形态快照编解码, 用于调试导出与手工修订, 不承担持久化.
 * 元数据为 JSON 字段, 数据体每个类型一个 SNBT 字符串, 类型后缀与数组标记保真,手改数值后可原样解码回快照.
 * 字段名与二进制形态的树排布一致, 解码把 JSON 还原成同构的树后进入升级管线与树读取.
 */
public final class JsonSnapshotCodec implements SnapshotCodec<String> {
    static final String FIELD_FORMAT = "format";    // 树形态的版本在帧头字节里, JSON 形态以顶层字段自述

    private static final Object SNBT_PARSER = VersionHelper.isOrAbove1_21_5() ? TagParserProxy.INSTANCE.create(NBTOps.INSTANCE) : null;
    private static final JsonWriterSettings JSON_WRITER = JsonWriterSettings.builder().indent(true).build();

    @Override
    @NotNull
    public String encode(@NotNull Snapshot snapshot) {
        SnapshotMeta meta = snapshot.meta();
        Document document = new Document();
        document.append(BinarySnapshotCodec.FIELD_ID, meta.id().toString());
        document.append(BinarySnapshotCodec.FIELD_PLAYER, meta.player().toString());
        document.append(BinarySnapshotCodec.FIELD_TIMESTAMP, meta.timestamp());
        document.append(BinarySnapshotCodec.FIELD_CAUSE, meta.cause().name());
        document.append(BinarySnapshotCodec.FIELD_PINNED, meta.pinned());
        document.append(BinarySnapshotCodec.FIELD_SERVER, meta.server());
        document.append(BinarySnapshotCodec.FIELD_MC_DATA, meta.mcDataVersion());
        document.append(FIELD_FORMAT, CURRENT_VERSION);
        Document data = new Document();
        for (Map.Entry<DataKey, Tag> entry : snapshot.data().entrySet()) {
            data.append(entry.getKey().asString(), new CompactStringTagVisitor().visit(entry.getValue()));
        }
        document.append(BinarySnapshotCodec.FIELD_DATA, data);
        return document.toJson(JSON_WRITER);
    }

    @Override
    @NotNull
    public DecodedSnapshot decode(@NotNull String encoded) {
        try {
            Document document = Document.parse(encoded);
            if (!(document.get(FIELD_FORMAT) instanceof Number formatNumber)) {
                return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, "missing or non-numeric format field");
            }
            int format = formatNumber.intValue();
            // 低版本经升级管线读入, 高版本一律拒绝.
            if (format < 1 || format > CURRENT_VERSION) {
                return new DecodedSnapshot.Invalid(InvalidReason.UNSUPPORTED_FORMAT, "snapshot format " + format + ", supported up to " + CURRENT_VERSION);
            }
            CompoundTag tree = toTagTree(document);
            return new DecodedSnapshot.Valid(BinarySnapshotCodec.fromTagTree(SnapshotUpgradePipeline.upgrade(tree, format)));
        } catch (FormatException exception) {
            return new DecodedSnapshot.Invalid(exception.reason(), String.valueOf(exception.getMessage()));
        } catch (Exception exception) {
            return new DecodedSnapshot.Invalid(InvalidReason.CORRUPTED, String.valueOf(exception.getMessage()));
        }
    }

    // JSON 字段还原成与二进制形态同构的树. id 与逻辑时间戳是身份与定序依据必须在场,
    // cause 等其余元数据沿用树读取的宽容缺省; 手改坏的文件报错直接点名问题字段
    private static CompoundTag toTagTree(Document document) throws IOException {
        CompoundTag root = NBT.createCompound();
        root.putUUID(BinarySnapshotCodec.FIELD_ID, UUID.fromString(requireString(document, BinarySnapshotCodec.FIELD_ID)));
        root.putUUID(BinarySnapshotCodec.FIELD_PLAYER, UUID.fromString(requireString(document, BinarySnapshotCodec.FIELD_PLAYER)));
        root.putLong(BinarySnapshotCodec.FIELD_TIMESTAMP, requireNumber(document, BinarySnapshotCodec.FIELD_TIMESTAMP).longValue());
        root.putInt(BinarySnapshotCodec.FIELD_MC_DATA, requireNumber(document, BinarySnapshotCodec.FIELD_MC_DATA).intValue());
        if (document.get(BinarySnapshotCodec.FIELD_CAUSE) instanceof String cause) root.putString(BinarySnapshotCodec.FIELD_CAUSE, cause);
        if (document.get(BinarySnapshotCodec.FIELD_SERVER) instanceof String server) root.putString(BinarySnapshotCodec.FIELD_SERVER, server);
        if (Boolean.TRUE.equals(document.getBoolean(BinarySnapshotCodec.FIELD_PINNED))) root.putBoolean(BinarySnapshotCodec.FIELD_PINNED, true);
        CompoundTag data = NBT.createCompound();
        Document values = document.get(BinarySnapshotCodec.FIELD_DATA, Document.class);
        if (values != null) {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (entry.getValue() == null) continue;
                if (!(entry.getValue() instanceof String snbt)) {
                    throw new IOException("data field '" + entry.getKey() + "' must be an SNBT string");
                }
                try {
                    data.put(entry.getKey(), parseSnbt(snbt));
                } catch (CommandSyntaxException exception) {
                    throw new IOException("data field '" + entry.getKey() + "': " + exception.getMessage());
                }
            }
        }
        root.put(BinarySnapshotCodec.FIELD_DATA, data);
        return root;
    }

    private static Tag parseSnbt(String input) throws CommandSyntaxException {
        if (SNBT_PARSER != null) return (Tag) TagParserProxy.INSTANCE.parseFully(SNBT_PARSER, input);
        StringReader reader = new StringReader(input);
        TagParserProxy parser = TagParserProxy.INSTANCE;
        net.minecraft.nbt.Tag nativeTag = (net.minecraft.nbt.Tag) parser.readValue(parser.newInstance(reader));
        reader.skipWhitespace();
        if (reader.canRead()) throw TagParser.ERROR_TRAILING_DATA.createWithContext(reader);
        return (Tag) net.minecraft.nbt.NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, nativeTag);
    }

    private static String requireString(Document document, String field) {
        if (document.get(field) instanceof String value) return value;
        throw new IllegalArgumentException("missing or non-string field '" + field + "'");
    }

    private static Number requireNumber(Document document, String field) {
        if (document.get(field) instanceof Number value) return value;
        throw new IllegalArgumentException("missing or non-numeric field '" + field + "'");
    }
}

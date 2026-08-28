package net.momirealms.sparrow.sync.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.codec.compressor.Compressors;
import net.momirealms.sparrow.sync.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 升级管线: 低版本经管线升到当前布局, 高版本按当前布局尽力读, 读不动才报 UNSUPPORTED_FORMAT
class SnapshotUpgradeTest {
    private static final UUID PLAYER = UUID.fromString("7f2b3c1d-0a9e-4b8c-9d6f-112233445566");
    private static final long TIMESTAMP = 1_756_300_000_000L;

    private final DocumentSnapshotCodec documentCodec = new DocumentSnapshotCodec(SnapshotFixtures.registry(), new BinarySnapshotCodec(Compressors.DEFLATE));
    private final BinarySnapshotCodec binaryCodec = new BinarySnapshotCodec(Compressors.DEFLATE);

    @Test
    void v1DocumentGainsDerivedIdAndStaysReadable() {
        DecodedSnapshot decoded = this.documentCodec.decode(v1Document(7L));

        SnapshotMeta meta = assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot().meta();
        assertEquals(PLAYER, meta.player());
        assertEquals(TIMESTAMP, meta.timestamp());
    }

    @Test
    void derivedIdIsStableAcrossReadsAndDistinctPerVersion() {
        // 同一份 v1 数据反复读出的 id 必须一致, 否则插回时会产生副本
        UUID first = metaOf(this.documentCodec.decode(v1Document(7L))).id();
        UUID again = metaOf(this.documentCodec.decode(v1Document(7L))).id();
        UUID other = metaOf(this.documentCodec.decode(v1Document(8L))).id();

        assertEquals(first, again);
        assertNotEquals(first, other);
    }

    @Test
    void v1BinaryFrameGainsDerivedId() throws IOException {
        CompoundTag root = NBT.createCompound();
        root.putUUID("player", PLAYER);
        root.putLong("version", 3L);
        root.putLong("ts", TIMESTAMP);
        root.putString("cause", "DISCONNECT");
        root.put("data", NBT.createCompound());

        DecodedSnapshot decoded = this.binaryCodec.decode(frameAs(root, 1));

        assertEquals(PLAYER, assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot().meta().player());
    }

    @Test
    void futureFormatIsRejectedEvenWhenItWouldStillParse() {
        // 布局看着能读也不读: 未来版本的字段语义无从预知, 猜着读会把错误数据当好数据用
        Document document = currentDocument();
        document.put("format", SnapshotCodec.CURRENT_VERSION + 1);

        DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, this.documentCodec.decode(document));

        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, invalid.reason());
        assertTrue(invalid.detail().contains("supported up to " + SnapshotCodec.CURRENT_VERSION));
    }

    @Test
    void futureFormatStillListsInMetadata() {
        // 门控只在 decode 上: 只读元数据不会写到玩家身上, 管理员仍应能看见这份快照
        Document document = currentDocument();
        document.put("format", SnapshotCodec.CURRENT_VERSION + 1);

        assertEquals(PLAYER, DocumentSnapshotCodec.decodeMeta(document).player());
    }

    private static SnapshotMeta metaOf(DecodedSnapshot decoded) {
        return assertInstanceOf(DecodedSnapshot.Valid.class, decoded).snapshot().meta();
    }

    // v1 布局: 有 version 没有 id, _id 是驱动生成的 ObjectId 形态 (这里用一个非 UUID 值代表)
    private static Document v1Document(long version) {
        return new Document()
                .append("_id", "legacy-object-id")
                .append("player", PLAYER)
                .append("version", version)
                .append("ts", new Date(TIMESTAMP))
                .append("cause", "DISCONNECT")
                .append("pinned", false)
                .append("server", "lobby-1")
                .append("format", 1)
                .append("mcData", 4189)
                .append("data", new Document());
    }

    private static Document currentDocument() {
        return new Document()
                .append("_id", UUID.randomUUID())
                .append("player", PLAYER)
                .append("ts", new Date(TIMESTAMP))
                .append("cause", "DISCONNECT")
                .append("pinned", false)
                .append("server", "lobby-1")
                .append("format", SnapshotCodec.CURRENT_VERSION)
                .append("mcData", 4189)
                .append("data", new Document());
    }

    // 按指定 format 手工封帧, frame() 只会写当前版本
    private byte[] frameAs(CompoundTag root, int format) throws IOException {
        byte[] framed = this.binaryCodec.frame(root);
        framed[2] = (byte) format;
        return framed;
    }
}

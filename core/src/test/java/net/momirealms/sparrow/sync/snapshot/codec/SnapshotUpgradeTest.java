package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.codec.upgrade.SnapshotUpgradePipeline;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** 版本门控与元数据浏览分别验收, 开发期格式不会进入升级管线. */
class SnapshotUpgradeTest {
    private final BinarySnapshotCodec binary = new BinarySnapshotCodec(CompressorRegistry.DEFLATE); // 测试用二进制载体
    private final DocumentSnapshotCodec document = new DocumentSnapshotCodec(new SnapshotDataCodec(CompressorRegistry.NONE)); // 文档数据使用独立数据帧

    /**
     * 分块格式重新从 1 起步, 范围外版本明确拒绝.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
    @Test
    void legacyBinaryFormatsAreRejected() throws IOException {
        for (int version : new int[]{0, 2, 3, 255}) {
            byte[] bytes = this.binary.encode(SnapshotFixtures.snapshot());
            bytes[0] = (byte) version;
            DecodedSnapshot.Invalid invalid = assertInstanceOf(DecodedSnapshot.Invalid.class, this.binary.decode(bytes));
            assertEquals(InvalidReason.UNSUPPORTED_FORMAT, invalid.reason());
            assertTrue(invalid.detail().contains("supported range"));
        }
    }

    /**
     * 旧文档的数据拒绝读取, 具有有效 UUID 的元数据仍可供列表显示.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
    @Test
    void legacyDocumentDataIsRejectedButMetadataRemainsReadable() throws IOException {
        for (int version : new int[]{0, 2, 3}) {
            Document encoded = this.document.encode(SnapshotFixtures.snapshot());
            encoded.put("format", version);
            assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(DecodedSnapshot.Invalid.class, this.document.decode(encoded)).reason());
            assertEquals(SnapshotFixtures.meta(), DocumentSnapshotCodec.decodeMeta(encoded));
        }
    }

    /**
     * 旧元数据若使用非 UUID 身份, 按 BSON 字段契约抛错.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
    @Test
    void legacyNonUuidIdIsNotSynthesized() throws IOException {
        Document encoded = this.document.encode(SnapshotFixtures.snapshot());
        encoded.put("format", 1);
        encoded.put("_id", "legacy-object-id");
        assertThrows(ClassCastException.class, () -> DocumentSnapshotCodec.decodeMeta(encoded));
    }

    /**
     * 未来版本即使字段可解析也不能作为当前快照读取.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
    @Test
    void futureFormatIsRejectedEvenWhenItWouldStillParse() throws IOException {
        Document encoded = this.document.encode(SnapshotFixtures.snapshot());
        encoded.put("format", SnapshotCodec.CURRENT_VERSION + 1);
        assertEquals(InvalidReason.UNSUPPORTED_FORMAT, assertInstanceOf(DecodedSnapshot.Invalid.class, this.document.decode(encoded)).reason());
    }

    /**
     * 版本门控不影响管理员查看未来版本的元数据.
     *
     * @throws Exception 当测试帧构造, 编解码或并发任务失败时
     */
    @Test
    void futureFormatStillListsInMetadata() throws IOException {
        Document encoded = this.document.encode(SnapshotFixtures.snapshot());
        encoded.put("format", SnapshotCodec.CURRENT_VERSION + 1);
        assertEquals(SnapshotFixtures.meta(), DocumentSnapshotCodec.decodeMeta(encoded));
    }

    /** 当前版本和高版本在升级入口原样返回, 不要求不存在的历史升级步. */
    @Test
    void emptyUpgradePipelineReturnsOriginalObjects() {
        var tree = NBT.createCompound();
        Document document = new Document();
        assertSame(tree, SnapshotUpgradePipeline.upgrade(tree, SnapshotCodec.CURRENT_VERSION));
        assertSame(tree, SnapshotUpgradePipeline.upgrade(tree, SnapshotCodec.CURRENT_VERSION + 1));
        assertSame(document, SnapshotUpgradePipeline.upgrade(document, SnapshotCodec.CURRENT_VERSION));
        assertSame(document, SnapshotUpgradePipeline.upgrade(document, SnapshotCodec.CURRENT_VERSION + 1));
    }
}

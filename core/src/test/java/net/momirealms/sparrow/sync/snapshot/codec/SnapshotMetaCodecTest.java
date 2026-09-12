package net.momirealms.sparrow.sync.snapshot.codec;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** 验证快照元数据独立读写, 身份缺失或 NBT 段不完整时由读取入口拒绝. */
class SnapshotMetaCodecTest {
    /**
     * 身份与保存信息可以独立往返, 树中恰好包含七个元数据字段.
     *
     * @throws IOException 当元数据编解码失败时
     */
    @Test
    void metadataRoundTripsIndependently() throws IOException {
        SnapshotMeta meta = SnapshotFixtures.meta().withPinned(true);
        byte[] bytes = SnapshotMetaCodec.encode(meta);
        assertEquals(meta, SnapshotMetaCodec.decode(bytes));
        CompoundTag tree = SnapshotMetaCodec.toCompoundTag(meta);
        assertEquals(7, tree.size());
        assertFalse(tree.containsKey("data"));
    }

    /**
     * Meta 段必须恰好容纳一个 compound, 错误根节点和尾随内容均不可接受.
     *
     * @throws IOException 当测试 NBT 序列化失败时
     */
    @Test
    void metadataRequiresExactlyOneCompound() throws IOException {
        byte[] bytes = SnapshotMetaCodec.encode(SnapshotFixtures.meta());
        assertThrows(IOException.class, () -> SnapshotMetaCodec.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        assertThrows(IOException.class, () -> SnapshotMetaCodec.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IOException.class, () -> SnapshotMetaCodec.decode(NBT.toBytes(NBT.createInt(1), false)));
    }

    /**
     * 身份字段是构造快照的依据, 缺少任意一个都应明确报告.
     *
     * @throws IOException 当测试 NBT 序列化失败时
     */
    @Test
    void metadataRequiresBothIdentities() throws IOException {
        for (String field : new String[]{"id", "player"}) {
            CompoundTag tree = SnapshotMetaCodec.toCompoundTag(SnapshotFixtures.meta());
            tree.remove(field);
            IOException failure = assertThrows(IOException.class, () -> SnapshotMetaCodec.decode(NBT.toBytes(tree, false)));
            assertTrue(failure.getMessage().contains(field));
        }
    }
}

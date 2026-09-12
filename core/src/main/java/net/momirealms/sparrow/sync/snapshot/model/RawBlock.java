package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.sync.snapshot.codec.block.BlockIndex;
import org.jetbrains.annotations.NotNull;

/**
 * 标出一个类型的数据块在原始帧字节数组中的位置, 供编码器直接复制.
 * 该块从 bytes[offset] 开始, 共占 9 + entry.length() 字节: 前 9 字节是块头,
 * 随后是该类型编码后的 NBT 数据, 是否压缩由索引中的压缩方式决定.
 * 此记录只引用原数组, 创建时不复制或校验字节; 复制时保留块头中的压缩方式, 原始长度和 CRC.
 *
 * @param bytes 包含此块的完整帧字节数组, <strong>调用方不得修改</strong>
 * @param offset 此块块头在 bytes 数组中的起始下标
 * @param entry 此块的长度, 压缩方式等索引信息; 其中的 offset 从原帧的第一个数据块起计算, 与数组下标不同
 */
public record RawBlock(byte @NotNull [] bytes, long offset, @NotNull BlockIndex.Entry entry) {
}

package net.momirealms.sparrow.sync.snapshot.model;

import org.jetbrains.annotations.NotNull;

/**
 * 引用一个类型的数据块在来源数组中占用的区间, 创建时不复制或检查块字节.
 * 区间从当前索引偏移开始, 到下一块的偏移结束; 最后一块以数据帧末尾为界.
 * 读取或逐块复制时由 BlockCodec 检查块头, 确认 13 字节块头加 payload 长度恰好占满此区间.
 *
 * @param bytes 包含此块的完整帧字节数组, <strong>调用方不得修改</strong>
 * @param offset 此块块头在 bytes 数组中的起始下标
 * @param end 此块区间的结束下标, 不包含该位置; 与 offset 一样使用 long 保留尚未校验的大偏移
 */
public record RawBlock(byte @NotNull [] bytes, long offset, long end) {
}

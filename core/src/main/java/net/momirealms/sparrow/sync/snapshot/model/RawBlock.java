package net.momirealms.sparrow.sync.snapshot.model;

import org.jetbrains.annotations.NotNull;

/**
 * 一个数据块在来源数组中的区间.
 * @param bytes 来源数组, <strong>不得修改</strong>
 * @param offset 块头起点, 尚未校验
 * @param end 不包含在内的结束位置, 尚未校验
 */
public record RawBlock(byte @NotNull [] bytes, long offset, long end) {
}

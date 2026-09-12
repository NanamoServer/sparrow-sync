package net.momirealms.sparrow.sync.snapshot.model;

import net.momirealms.sparrow.nbt.Tag;
import org.jetbrains.annotations.NotNull;

/**
 * 已展开的单类型数据块, 元信息和数据在采集, 覆盖与载体转换时一起传递.
 * 原始二进制块由 SnapshotData.raw 提供, 读取本对象的数据可能触发一次惰性解析.
 *
 * @param meta 独立于存储载体的块元信息
 * @param data 类型自身的 NBT 值, <strong>交付后不得修改</strong>
 */
public record SnapshotBlock(@NotNull BlockMeta meta, @NotNull Tag data) {
}

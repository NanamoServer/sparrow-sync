package net.momirealms.sparrow.sync.snapshot.model;

/**
 * 数据块的索引偏移, 压缩算法和长度等信息另存于块头.
 * @param offset 块头相对块区起点的字节偏移
 */
public record BlockIndex(int offset) {
}

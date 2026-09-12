package net.momirealms.sparrow.sync.snapshot.model;

/**
 * 一个类型在二进制数据帧中的位置, 用于定位块头和确定相邻块的边界.
 * 算法, 长度和校验值均由块头提供, 读取索引时无需访问块内容.
 *
 * @param offset 块头相对整个块区起点的偏移, 单位为字节
 */
public record BlockIndex(int offset) {
}

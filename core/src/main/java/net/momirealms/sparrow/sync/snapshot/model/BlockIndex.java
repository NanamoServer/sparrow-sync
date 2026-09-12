package net.momirealms.sparrow.sync.snapshot.model;

/**
 * 一个类型在二进制帧中的位置和长度, 供定位, 预览及原始块复制使用.
 * 压缩算法由该块的块头提供, 解压和校验在读取类型数据时进行.
 *
 * @param offset 块头相对整个块区起点的偏移, 单位为字节
 * @param length payload 的字节数, 不含 9 字节块头
 * @param rawLength 解压后单键 compound 的字节数
 */
public record BlockIndex(int offset, int length, int rawLength) {
}

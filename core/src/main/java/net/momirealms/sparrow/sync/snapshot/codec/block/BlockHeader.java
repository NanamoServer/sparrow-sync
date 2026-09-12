package net.momirealms.sparrow.sync.snapshot.codec.block;

/**
 * 单块二进制头中的四个编码字段, 仅供块读取, 复制和大小预览使用.
 * 字段按算法, payload 长度, 原始长度, CRC32 的顺序写入, 总计 13 字节.
 *
 * @param compressorId payload 使用的压缩算法标识, 只有解码内容时才要求本服支持
 * @param payloadLength 实际存储的 payload 字节数, 不包含块头
 * @param rawLength 解压后单键 compound 的完整 NBT 字节数, 包含类型名
 * @param checksum payload 的 CRC32 位模式, 覆盖实际存储的字节
 */
public record BlockHeader(byte compressorId, int payloadLength, int rawLength, int checksum) {
}

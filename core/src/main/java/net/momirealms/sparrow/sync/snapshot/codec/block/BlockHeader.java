package net.momirealms.sparrow.sync.snapshot.codec.block;

/**
 * 13 字节块头, 依次保存压缩算法、存储长度、原始长度和 CRC32.
 * @param compressorId 解码块内容时才检查算法是否受支持
 * @param payloadLength 不含块头的存储字节数
 * @param rawLength 解压后的完整 NBT 长度, 包含类型名
 * @param checksum 实际存储字节的 CRC32
 */
public record BlockHeader(byte compressorId, int payloadLength, int rawLength, int checksum) {
}

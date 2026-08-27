package net.momirealms.sparrow.sync.codec.compressor;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * 快照字节的压缩算法. id 写入字节头, 读方按 id 选择算法解压, 与写方配置无关.
 * 实现须经 {@link Compressors#register(Compressor)} 注册后才能被解码方识别.
 */
public interface Compressor {

    /** 写入字节头的算法标识, 全局唯一且一经使用不得变更. */
    byte id();

    byte @NotNull [] compress(byte @NotNull [] data) throws IOException;

    /**
     * 解压 data 中从 offset 起 length 字节的载荷.
     *
     * @param sizeLimit 解压结果的字节数上限, 防解压炸弹
     * @throws IOException 当数据损坏或解压结果超过 sizeLimit 时
     */
    byte @NotNull [] decompress(byte @NotNull [] data, int offset, int length, int sizeLimit) throws IOException;
}

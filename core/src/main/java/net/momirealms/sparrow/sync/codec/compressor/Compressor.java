package net.momirealms.sparrow.sync.codec.compressor;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public interface Compressor {

    /**
     * 将字节流进行压缩.
     */
    byte @NotNull [] compress(byte @NotNull [] data) throws IOException;

    /**
     * 解压 data 中从 offset 起 length 字节的载荷.
     *
     * @param sizeLimit 解压结果的字节数上限, 防解压炸弹
     * @throws IOException 当数据损坏或解压结果超过 sizeLimit 时
     */
    byte @NotNull [] decompress(byte @NotNull [] data, int offset, int length, int sizeLimit) throws IOException;
}

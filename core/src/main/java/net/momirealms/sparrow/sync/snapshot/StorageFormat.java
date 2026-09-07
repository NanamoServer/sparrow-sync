package net.momirealms.sparrow.sync.snapshot;

public enum StorageFormat {
    BINARY,     // 序列化为压缩 NBT 字节, 以二进制字段存储, 内容对存储不透明
    STRUCTURED  // 转换为文档结构存储, 字段可读可查询
}

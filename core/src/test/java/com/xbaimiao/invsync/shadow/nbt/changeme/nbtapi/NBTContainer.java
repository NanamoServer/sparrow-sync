package com.xbaimiao.invsync.shadow.nbt.changeme.nbtapi;

// 仅替代来源 NBTContainer 的文本交付, 实际解析由被测代码的 TagParser 完成.
public final class NBTContainer {
    private final String snbt;

    public NBTContainer(String snbt) {
        this.snbt = snbt;
    }

    @Override
    public String toString() {
        return this.snbt;
    }
}

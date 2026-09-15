package com.xbaimiao.invsync.shadow.nbt.changeme.nbtapi;

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

package net.momirealms.sparrow.sync.snapshot.model;

public record BlockMeta(int version, boolean keepUnknown) {
    public static final int CURRENT_VERSION = 1; // 当前元信息的版本
    public static final BlockMeta DEFAULT = new BlockMeta(true);
    public static final BlockMeta DISCARD_UNKNOWN = new BlockMeta(false);

    /**
     * 使用当前元信息格式声明未知数据的处理方式.
     *
     * @param keepUnknown 接收服务器未注册此类型时是否保留数据
     */
    public BlockMeta(boolean keepUnknown) {
        this(CURRENT_VERSION, keepUnknown);
    }
}

package net.momirealms.sparrow.sync.snapshot.codec.upgrade;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException.InvalidReason;
import net.momirealms.sparrow.sync.snapshot.exception.FormatException;
import net.momirealms.sparrow.sync.snapshot.model.BlockMeta;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public final class BlockMetaUpgradePipeline {
    private static final BlockMetaUpgrade[] BY_TARGET = indexByTarget(); // 下标对应目标版本, 当前版本 1 尚无升级步骤

    private BlockMetaUpgradePipeline() {
    }

    /**
     * 将已读取的块元信息转换到当前布局, 不读取类型数据或压缩载荷.
     *
     * @param meta 此次读取独占的元信息树
     * @param fromVersion 树中记录的元信息版本
     * @return 可由当前 BlockMetaCodec 解释的树
     * @throws IOException 当版本不受支持或升级步骤失败时
     */
    @NotNull
    public static CompoundTag upgrade(@NotNull CompoundTag meta, int fromVersion) throws IOException {
        if (fromVersion < 1 || fromVersion > BlockMeta.CURRENT_VERSION) {
            throw new FormatException(InvalidReason.UNSUPPORTED_FORMAT, "block metadata version " + fromVersion
                    + ", supported range 1.." + BlockMeta.CURRENT_VERSION);
        }
        CompoundTag current = meta;
        for (int target = fromVersion + 1; target <= BlockMeta.CURRENT_VERSION; target++) {
            current = BY_TARGET[target].upgrade(current);
        }
        return current;
    }

    /**
     * 将 JSON 块元信息转换到当前布局, 与二进制读取使用相同的升级步骤表.
     *
     * @param meta 此次读取独占的元信息对象
     * @param fromVersion 对象记录的元信息版本
     * @return 可由当前 BlockMetaCodec 解释的对象
     * @throws IOException 当版本不受支持或升级步骤失败时
     */
    @NotNull
    public static Document upgrade(@NotNull Document meta, int fromVersion) throws IOException {
        if (fromVersion < 1 || fromVersion > BlockMeta.CURRENT_VERSION) {
            throw new FormatException(InvalidReason.UNSUPPORTED_FORMAT, "block metadata version " + fromVersion
                    + ", supported range 1.." + BlockMeta.CURRENT_VERSION);
        }
        Document current = meta;
        for (int target = fromVersion + 1; target <= BlockMeta.CURRENT_VERSION; target++) {
            current = BY_TARGET[target].upgrade(current);
        }
        return current;
    }

    /**
     * 为插件自有的升级步骤建立目标版本索引, 启动时检查链条完整性.
     *
     * @param registered 随代码注册的升级步骤
     * @return 下标 0 和 1 留空的步骤表
     * @throws IllegalStateException 当目标版本越界, 重复或缺少中间步骤时
     */
    @NotNull
    private static BlockMetaUpgrade[] indexByTarget(BlockMetaUpgrade... registered) {
        BlockMetaUpgrade[] byTarget = new BlockMetaUpgrade[BlockMeta.CURRENT_VERSION + 1];
        for (BlockMetaUpgrade upgrade : registered) {
            int target = upgrade.targetVersion();
            if (target < 2 || target > BlockMeta.CURRENT_VERSION) {
                throw new IllegalStateException("block metadata upgrade target outside supported range: " + target);
            }
            if (byTarget[target] != null) {
                throw new IllegalStateException("duplicate block metadata upgrade target: " + target);
            }
            byTarget[target] = upgrade;
        }
        for (int target = 2; target <= BlockMeta.CURRENT_VERSION; target++) {
            if (byTarget[target] == null) {
                throw new IllegalStateException("missing block metadata upgrade target: " + target);
            }
        }
        return byTarget;
    }
}

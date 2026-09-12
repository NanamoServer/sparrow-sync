package net.momirealms.sparrow.sync.snapshot.codec.upgrade;

import net.momirealms.sparrow.nbt.CompoundTag;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public interface BlockMetaUpgrade {
    // 从 2 起的目标版本, 输入版本为此值 -1
    int targetVersion();

    /**
     * 转换插件自有的块元信息, 类型数据 Tag 不参与此步骤.
     *
     * @param meta 前一版本的元信息树, 由此次读取独占
     * @return 目标版本的元信息树
     * @throws IOException 当旧元信息不能转换时
     */
    @NotNull
    CompoundTag upgrade(@NotNull CompoundTag meta) throws IOException;

    /**
     * 转换 JSON 元信息布局, 保留此步骤不需要修改的其他字段.
     *
     * @param meta 前一版本的 JSON 元信息对象, 由此次读取独占
     * @return 目标版本的元信息对象
     * @throws IOException 当旧元信息不能转换时
     */
    @NotNull
    Document upgrade(@NotNull Document meta) throws IOException;
}

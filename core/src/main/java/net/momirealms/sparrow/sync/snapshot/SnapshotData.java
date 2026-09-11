package net.momirealms.sparrow.sync.snapshot;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;

public interface SnapshotData {

    /**
     * 数据体中全部类型的标识, <strong>不触发任何解析</strong>.
     */
    @NotNull
    Set<DataKey> keys();

    /**
     * 取一个类型的值, 数据体中没有这个类型时返回 null.
     *
     * @throws UncheckedIOException 当该类型的数据损坏, 无法还原时
     */
    @Nullable
    Tag get(@NotNull DataKey key);

    /**
     * 全部类型的值, <strong>会还原数据体中的每一个类型</strong>, 用于确实需要完整内容的路径.
     *
     * @throws UncheckedIOException 当任一类型的数据损坏, 无法还原时
     */
    @NotNull
    Map<DataKey, Tag> all();
}

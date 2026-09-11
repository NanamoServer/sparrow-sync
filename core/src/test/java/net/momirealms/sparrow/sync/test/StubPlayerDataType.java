package net.momirealms.sparrow.sync.test;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/** 只提供注册元数据的测试类型, 任何行为调用都代表测试越界. */
public record StubPlayerDataType(@NotNull DataKey key, boolean critical, @NotNull Set<DataKey> dependencies) implements PlayerDataType<Tag> {

    public StubPlayerDataType {
        dependencies = Set.copyOf(dependencies);
    }

    public StubPlayerDataType(@NotNull DataKey key) {
        this(key, false, Set.of());
    }

    @Override
    @NotNull
    public Tag capture(@NotNull Player player, @NotNull CaptureMode mode) {
        throw new AssertionError("stub capture must not be called");
    }

    @Override
    @NotNull
    public Tag encode(@NotNull Tag value) {
        throw new AssertionError("stub encode must not be called");
    }

    @Override
    @NotNull
    public Tag decode(@NotNull Tag data, int mcDataVersion) {
        throw new AssertionError("stub decode must not be called");
    }

    @Override
    public void apply(@NotNull Player player, @NotNull Tag value) {
        throw new AssertionError("stub apply must not be called");
    }
}

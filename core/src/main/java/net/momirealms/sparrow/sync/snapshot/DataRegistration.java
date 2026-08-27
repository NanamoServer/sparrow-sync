package net.momirealms.sparrow.sync.snapshot;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * 一类同步数据的注册信息, 描述它的存储形态, 应用失败等级与应用顺序依赖.
 *
 * @param key          数据标识
 * @param storage      文档存储中的排布形态
 * @param critical     应用失败时是否视为关键失败, 关键失败会中止整次应用并保持玩家锁定
 * @param dependencies 应用顺序上先于本类型的其他数据, 未注册的依赖在排序时被忽略
 */
public record DataRegistration(@NotNull DataKey key, @NotNull StorageFormat storage, boolean critical, @NotNull Set<DataKey> dependencies) {

    public DataRegistration {
        dependencies = Set.copyOf(dependencies);
    }

    @NotNull
    public static DataRegistration of(@NotNull DataKey key, @NotNull StorageFormat storage) {
        return new DataRegistration(key, storage, false, Set.of());
    }

    @NotNull
    public static DataRegistration of(@NotNull DataKey key, @NotNull StorageFormat storage, boolean critical, @NotNull Set<DataKey> dependencies) {
        return new DataRegistration(key, storage, critical, dependencies);
    }
}

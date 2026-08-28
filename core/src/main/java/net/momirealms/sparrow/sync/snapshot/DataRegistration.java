package net.momirealms.sparrow.sync.snapshot;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * 无行为的纯声明实现, 供编解码测试与"只声明不装配"的场景使用;
 * 带行为的数据类型直接实现 {@link DataDeclaration}, 不经过本类.
 */
public record DataRegistration(@NotNull DataKey key, @NotNull StorageFormat storage, boolean critical, @NotNull Set<DataKey> dependencies) implements DataDeclaration {

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

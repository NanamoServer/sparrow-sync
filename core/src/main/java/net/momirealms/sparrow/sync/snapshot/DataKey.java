package net.momirealms.sparrow.sync.snapshot;

import org.jetbrains.annotations.NotNull;

/**
 * 一类同步数据的标识, 由命名空间与名称组成, 规范形式为 {@code namespace:value}.
 */
public record DataKey(@NotNull String namespace, @NotNull String value) implements Comparable<DataKey> {
    public static final String DEFAULT_NAMESPACE = "sparrow_sync";

    @NotNull
    public static DataKey of(@NotNull String namespace, @NotNull String value) {
        return new DataKey(namespace, value);
    }

    @NotNull
    public static DataKey sparrow(@NotNull String value) {
        return new DataKey(DEFAULT_NAMESPACE, value);
    }

    @NotNull
    public static DataKey parse(@NotNull String key) {
        int separator = key.indexOf(':');
        if (separator < 0) return new DataKey(DEFAULT_NAMESPACE, key);
        return new DataKey(key.substring(0, separator), key.substring(separator + 1));
    }

    @NotNull
    public String asString() {
        return this.namespace + ":" + this.value;
    }

    @Override
    public int compareTo(@NotNull DataKey other) {
        int result = this.namespace.compareTo(other.namespace);
        if (result != 0) return result;
        return this.value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return this.asString();
    }
}

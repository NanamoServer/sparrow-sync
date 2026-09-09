package net.momirealms.sparrow.sync.snapshot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 一类同步数据的标识, 由命名空间与名称组成, 规范形式为 {@code namespace:value}.
 */
public final class DataKey implements Comparable<DataKey> {
    public static final String DEFAULT_NAMESPACE = "sparrow_sync";

    private final String namespace;
    private final String value;

    public DataKey(@NotNull String namespace, @NotNull String value) {
        this.namespace = namespace;
        this.value = value;
    }

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

    @NotNull
    public String namespace() {
        return this.namespace;
    }

    @NotNull
    public String value() {
        return this.value;
    }

    @Override
    public boolean equals(@Nullable Object object) {
        if (this == object) return true;
        if (!(object instanceof DataKey)) return false;
        DataKey other = (DataKey) object;
        return this.namespace.equals(other.namespace) && this.value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return 31 * this.namespace.hashCode() + this.value.hashCode();
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

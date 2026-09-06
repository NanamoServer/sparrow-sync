package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.nbt.CompoundTag;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;

@ApiStatus.Internal
public enum MapType implements MapHandler {
    HIDE {
        @Override
        @NotNull
        public CompoundTag compile(@NotNull CompoundTag components, @NotNull MapOrigin origin) {
            CompoundTag hidden = new CompoundTag(new HashMap<>(components.tags));
            hidden.remove("minecraft:map_id");
            return hidden;
        }

        @Override
        @NotNull
        public CompoundTag decode(@NotNull CompoundTag components, @NotNull MapOrigin origin, @NotNull String ownerId) {
            if (!ownerId.equals(origin.ownerId())) return components;
            CompoundTag restored = new CompoundTag(new HashMap<>(components.tags));
            restored.putInt("minecraft:map_id", origin.id());
            return restored;
        }
    };

    @Override
    @NotNull
    public MapType type() {
        return this;
    }
}

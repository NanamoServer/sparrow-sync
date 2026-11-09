package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemCooldowns;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.item.ItemCooldownsProxy;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public final class ItemCooldownsDataType extends CodecDataType<Map<Object, ItemCooldownsDataType.Cooldown>> {
    public static final DataKey ITEM_COOLDOWNS = DataKey.sparrow("item_cooldowns");

    public ItemCooldownsDataType() {
        super(ITEM_COOLDOWNS, Codec.unboundedMap(IdentifierProxy.INSTANCE.getCodec(), Cooldown.CODEC));
    }

    @NotNull
    @Override
    protected Map<Object, Cooldown> captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        ItemCooldowns cooldowns = ((CraftPlayer) player).getHandle().getCooldowns();
        Map<Object, Cooldown> result = new HashMap<>();
        for (Map.Entry<Object, ItemCooldowns.CooldownInstance> entry : ItemCooldownsProxy.INSTANCE.getCooldowns(cooldowns).entrySet()) {
            ItemCooldowns.CooldownInstance value = entry.getValue();
            int remaining = value.endTime() - cooldowns.tickCount;
            if (remaining > 0) {
                result.put(entry.getKey(), new Cooldown(value.endTime() - value.startTime(), remaining));
            }
        }
        return Map.copyOf(result);
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Map<Object, Cooldown> value) {
        ItemCooldowns cooldowns = ((CraftPlayer) player).getHandle().getCooldowns();
        Map<Object, ItemCooldowns.CooldownInstance> current = ItemCooldownsProxy.INSTANCE.getCooldowns(cooldowns);
        for (Object group : current.keySet()) {
            if (!value.containsKey(group)) {
                ItemCooldownsProxy.INSTANCE.onCooldownEnded(cooldowns, group);
            }
        }
        current.clear();
        // 相对目标玩家的 tick 重建起止时间, 离线时间不消耗冷却.
        for (Map.Entry<Object, Cooldown> entry : value.entrySet()) {
            Cooldown saved = entry.getValue();
            int end = cooldowns.tickCount + saved.remaining();
            current.put(entry.getKey(), new ItemCooldowns.CooldownInstance(end - saved.duration(), end));
            // 直接通知客户端, 恢复快照不触发新增冷却事件.
            ItemCooldownsProxy.INSTANCE.onCooldownStarted(cooldowns, entry.getKey(), saved.remaining());
        }
    }

    public record Cooldown(int duration, int remaining) {
        public static final Codec<Cooldown> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("duration").forGetter(Cooldown::duration),
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("remaining").forGetter(Cooldown::remaining)
        ).apply(instance, Cooldown::new));
    }
}

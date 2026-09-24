package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.proxy.minecraft.core.RegistryProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.codec.ops.MinecraftRegistryOps;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PotionEffectsDataType implements NativePlayerDataType<List<MobEffectInstance>> {
    public static final DataKey POTION_EFFECTS = DataKey.sparrow("potion_effects");
    private static final Codec<List<MobEffectInstance>> CODEC = MobEffectInstance.CODEC.listOf();
    private static final String EFFECTS_KEY = "active_effects";

    @Override
    @NotNull
    public DataKey key() {
        return POTION_EFFECTS;
    }

    @Override
    @NotNull
    public List<MobEffectInstance> capture(@NotNull Player player, @NotNull CaptureMode mode) {
        List<MobEffectInstance> effects = new ArrayList<>();
        for (MobEffectInstance instance : handle(player).getActiveEffects()) {
            if (instance.isAmbient()) continue;
            effects.add(mode == CaptureMode.OFFLINE ? instance : copyOf(instance));
        }
        return effects;
    }

    @Override
    @NotNull
    public Tag encode(@NotNull List<MobEffectInstance> value) {
        CompoundTag root = NBT.createCompound();
        root.putInt("DataVersion", VersionHelper.WORLD_VERSION);
        Tag effectTag = CODEC.encodeStart(MinecraftRegistryOps.sparrowNbt(), value).getOrThrow(message -> new IllegalStateException("failed to encode " + POTION_EFFECTS + ": " + message));
        root.put(EFFECTS_KEY, effectTag);
        return root;
    }

    @Override
    @NotNull
    public List<MobEffectInstance> decode(@NotNull Tag data) throws IOException {
        // 旧快照列表没有版本信息, 按当前效果格式读取
        Tag effects = data;
        if (data instanceof CompoundTag root) {
            int dataVersion = root.getInt("DataVersion");
            int current = VersionHelper.WORLD_VERSION;
            if (dataVersion > current) {
                throw new IOException("potion effects data version " + dataVersion + " is newer than this server (" + current + ")");
            }
            if (dataVersion > 0 && dataVersion < current) {
                // 效果的版本升级规则属于 PLAYER, 包含字段改名和隐藏效果链
                root = (CompoundTag) DataFixers.getDataFixer().update(References.PLAYER, new Dynamic<>(NBTOps.INSTANCE, root), dataVersion, current).getValue();
            }
            effects = root.get(EFFECTS_KEY);
            if (effects == null) {
                throw new IOException("potion effects data is missing " + EFFECTS_KEY);
            }
        }
        return CODEC.parse(MinecraftRegistryOps.sparrowNbt(), effects)
                .getOrThrow(message -> new IOException("failed to decode " + POTION_EFFECTS + ": " + message));
    }

    @Override
    public void apply(@NotNull Player player, @NotNull List<MobEffectInstance> value) {
        ServerPlayer handle = handle(player);
        // 先清空旧效果再写入, 保留隐藏效果链
        handle.removeAllEffects();
        int size = value.size();
        for (int i = 0; i < size; i++) {
            handle.addEffect(value.get(i));
        }
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull List<MobEffectInstance> value) {
        Tag effects = value.isEmpty()
                ? NBT.createList()
                : CODEC.encodeStart(MinecraftRegistryOps.sparrowNbt(), value).getOrThrow(message -> new IllegalStateException("failed to encode " + POTION_EFFECTS + ": " + message));
        Tag attributes = mergeNativeAttributes(playerData.get("attributes"), value);
        playerData.put("attributes", attributes);
        playerData.put(EFFECTS_KEY, effects);
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    private static ServerPlayer handle(Player player) {
        return ((CraftPlayer) player).getHandle();
    }

    // 递归复制隐藏效果, 玩家身上的剩余时长会在采集后继续变化
    private static MobEffectInstance copyOf(MobEffectInstance instance) {
        MobEffectInstance hidden = instance.hiddenEffect;
        return new MobEffectInstance(
                instance.getEffect(),
                instance.getDuration(),
                instance.getAmplifier(),
                instance.isAmbient(),
                instance.isVisible(),
                instance.showIcon(),
                hidden == null ? null : copyOf(hidden)
        );
    }

    /** 复制玩家属性, 清理旧药水加成并按当前效果及等级重建. */
    @NotNull
    public static ListTag mergeNativeAttributes(@Nullable Tag current, @NotNull List<MobEffectInstance> effects) {
        ListTag attributes = current instanceof ListTag list ? list.deepClone() : NBT.createList();
        Set<String> effectModifiers = new HashSet<>();
        // 根据注册表识别药水属性, 已经没有对应效果的残留加成也会被清理
        for (MobEffect effect : BuiltInRegistries.MOB_EFFECT) {
            effect.createModifiers(0, (attribute, modifier) -> effectModifiers.add(encodeModifier(modifier).getString("id")));
        }
        for (int i = 0; i < attributes.size(); i++) {
            CompoundTag attribute = attributes.getCompound(i);
            ListTag modifiers = attribute.getList("modifiers", null);
            if (modifiers == null) continue;
            for (int j = modifiers.size() - 1; j >= 0; j--) {
                if (effectModifiers.contains(modifiers.getCompound(j).getString("id"))) modifiers.remove(j);
            }
        }
        // 原版从存档加载效果时不会补建属性, 此处写入与效果等级一致的数值
        AttributeSupplier defaults = DefaultAttributes.getSupplier(EntityType.PLAYER);
        for (int i = 0; i < effects.size(); i++) {
            MobEffectInstance effect = effects.get(i);
            effect.getEffect().value().createModifiers(effect.getAmplifier(), (attribute, modifier) -> {
                if (!defaults.hasAttribute(attribute)) return;
                CompoundTag target = findOrCreateAttribute(attributes, attribute, defaults);
                ListTag modifiers = target.getList("modifiers", null);
                if (modifiers == null) {
                    modifiers = NBT.createList();
                    target.put("modifiers", modifiers);
                }
                modifiers.add(encodeModifier(modifier));
            });
        }
        return attributes;
    }

    private static CompoundTag findOrCreateAttribute(ListTag attributes, Holder<Attribute> attribute, AttributeSupplier defaults) {
        String id = RegistryProxy.INSTANCE.getKey(BuiltInRegistries.ATTRIBUTE, attribute.value()).toString();
        for (int i = 0; i < attributes.size(); i++) {
            CompoundTag entry = attributes.getCompound(i);
            if (id.equals(entry.getString("id"))) return entry;
        }
        CompoundTag entry = NBT.createCompound();
        entry.putString("id", id);
        entry.putDouble("base", defaults.getBaseValue(attribute));
        attributes.add(entry);
        return entry;
    }

    private static CompoundTag encodeModifier(AttributeModifier modifier) {
        return (CompoundTag) AttributeModifier.CODEC.encodeStart(NBTOps.INSTANCE, modifier)
                .getOrThrow(message -> new IllegalStateException("failed to encode potion attribute modifier: " + message));
    }
}

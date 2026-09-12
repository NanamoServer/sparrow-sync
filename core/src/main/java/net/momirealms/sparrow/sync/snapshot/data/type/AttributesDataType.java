package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.AttributeOptions;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.proxy.minecraft.core.RegistryProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.entity.ai.attributes.AttributeInstanceProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 同步白名单内属性的基础值与可跨服 modifier, 服务器本地 modifier 由配置黑名单保留.
 */
public final class AttributesDataType extends CodecDataType<AttributesDataType.Attributes> implements NativePlayerDataType<AttributesDataType.Attributes> {
    public static final DataKey ATTRIBUTES = DataKey.sparrow("attributes");

    private static final ModifierValue[] NO_MODIFIERS = new ModifierValue[0];
    private static final Operation[] OPERATIONS = Operation.values();
    private static final Codec<NamespacedKey> KEY_CODEC = IdentifierProxy.INSTANCE.getCodec().xmap(
            identifier -> new NamespacedKey(IdentifierProxy.INSTANCE.getNamespace(identifier), IdentifierProxy.INSTANCE.getPath(identifier)),
            key -> IdentifierProxy.INSTANCE.newInstance(key.getNamespace(), key.getKey())
    );
    private static final Codec<Operation> OPERATION_CODEC = net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.CODEC.xmap(
            operation -> Operation.values()[operation.id()],
            operation -> net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.BY_ID.apply(operation.ordinal())
    );
    private static final Codec<EquipmentSlotGroup> SLOT_GROUP_CODEC = Codec.STRING.comapFlatMap(AttributesDataType::parseSlotGroup, EquipmentSlotGroup::toString);
    private static final Codec<ModifierValue> MODIFIER_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            KEY_CODEC.fieldOf("key").forGetter(ModifierValue::key),
            Codec.DOUBLE.fieldOf("amount").forGetter(ModifierValue::amount),
            OPERATION_CODEC.fieldOf("operation").forGetter(ModifierValue::operation),
            SLOT_GROUP_CODEC.fieldOf("slot").forGetter(ModifierValue::slotGroup)
    ).apply(instance, ModifierValue::new));
    private static final Codec<StoredAttribute> STORED_ATTRIBUTE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            KEY_CODEC.fieldOf("key").forGetter(StoredAttribute::key),
            Codec.DOUBLE.fieldOf("base").forGetter(StoredAttribute::base),
            MODIFIER_CODEC.listOf().fieldOf("modifiers").forGetter(StoredAttribute::modifiers)
    ).apply(instance, StoredAttribute::new));
    static final Codec<Attributes> CODEC = STORED_ATTRIBUTE_CODEC.listOf().xmap(AttributesDataType::fromStored, AttributesDataType::toStored);

    private volatile CaptureTargets captureTargets;

    public AttributesDataType() {
        super(ATTRIBUTES, CODEC);
    }

    @Override
    @NotNull
    public Set<DataKey> dependencies() {
        return Set.of(InventoryDataType.INVENTORY, PotionEffectsDataType.POTION_EFFECTS);
    }

    /**
     * 安装属性变更回调, 安装失败的实例保留普通采集路径.
     */
    public void injectTracker(@NotNull Player player) {
        CaptureTarget[] targets = this.captureTargets(PluginConfig.synchronization$attributes()).attributes();
        if (targets.length == 0) return;
        net.minecraft.world.entity.ai.attributes.AttributeMap attributes = ((CraftPlayer) player).getHandle().getAttributes();
        for (int i = 0; i < targets.length; i++) {
            CaptureTarget target = targets[i];
            try {
                net.minecraft.world.entity.ai.attributes.AttributeInstance instance = attributes.getInstance(target.holder());
                if (instance == null) continue;
                Consumer<net.minecraft.world.entity.ai.attributes.AttributeInstance> callback = AttributeInstanceProxy.INSTANCE.getOnDirty(instance);
                if (callback instanceof CaptureCache) continue;
                if (callback == null) {
                    throw new IllegalStateException("AttributeInstance.onDirty callback is unavailable");
                }
                // 回调随属性实例存活, 首次采集时才构建独立采集数据.
                AttributeInstanceProxy.INSTANCE.setOnDirty(instance, new CaptureCache(callback));
            } catch (RuntimeException | LinkageError exception) {
                // 当前项保留普通采集, 其余属性继续安装.
                SparrowSync.instance().logger().warn("Could not attach attribute cache for " + player.getName() + " (" + target.key() + "); this attribute will be captured without caching", exception);
            }
        }
    }

    @Override
    @NotNull
    protected Attributes captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        AttributeOptions options = PluginConfig.synchronization$attributes();
        CaptureTarget[] targets = this.captureTargets(options).attributes();
        AttributeValue[] values = new AttributeValue[targets.length];
        if (targets.length == 0) return new Attributes(values);
        net.minecraft.world.entity.ai.attributes.AttributeMap attributes = ((CraftPlayer) player).getHandle().getAttributes();
        int count = 0;
        for (int i = 0; i < targets.length; i++) {
            CaptureTarget target = targets[i];
            net.minecraft.world.entity.ai.attributes.AttributeInstance instance = attributes.getInstance(target.holder());
            if (instance == null) continue;
            // 动态替换的实例和配置新增项可能尚未注入, 按当前实例选择采集路径.
            Consumer<net.minecraft.world.entity.ai.attributes.AttributeInstance> callback = AttributeInstanceProxy.INSTANCE.getOnDirty(instance);
            values[count++] = callback instanceof CaptureCache cache ? cache.capture(instance, target.key(), options) : captureAttribute(instance, target.key(), options);
        }
        return new Attributes(count == values.length ? values : Arrays.copyOf(values, count));
    }

    @NotNull
    private static AttributeValue captureAttribute(net.minecraft.world.entity.ai.attributes.AttributeInstance instance, NamespacedKey attributeKey, AttributeOptions options) {
        Map<Object, net.minecraft.world.entity.ai.attributes.AttributeModifier> currentModifiers = AttributeInstanceProxy.INSTANCE.getModifierById(instance);
        ModifierValue[] modifiers = currentModifiers.isEmpty() ? NO_MODIFIERS : new ModifierValue[currentModifiers.size()];
        boolean filterModifiers = !options.modifierBlacklist().isEmpty();
        int count = 0;
        for (Map.Entry<Object, net.minecraft.world.entity.ai.attributes.AttributeModifier> entry : currentModifiers.entrySet()) {
            Object id = entry.getKey();
            if (filterModifiers && options.modifierBlacklisted(id.toString())) continue;
            NamespacedKey key = new NamespacedKey(IdentifierProxy.INSTANCE.getNamespace(id), IdentifierProxy.INSTANCE.getPath(id));
            net.minecraft.world.entity.ai.attributes.AttributeModifier modifier = entry.getValue();
            // NMS 实例上的 modifier 没有装备槽位; Craft 的转换同样固定为 ANY.
            modifiers[count++] = new ModifierValue(key, modifier.amount(), OPERATIONS[modifier.operation().id()], EquipmentSlotGroup.ANY);
        }
        if (count < modifiers.length) modifiers = Arrays.copyOf(modifiers, count);
        return new AttributeValue(attributeKey, instance.getBaseValue(), modifiers);
    }

    private CaptureTargets captureTargets(AttributeOptions options) {
        CaptureTargets targets = this.captureTargets;
        if (targets != null && targets.options() == options) return targets;
        // 配置在 bootstrap 阶段加载, 属性注册表在首次注入或采集时解析. reload 发布的新配置会重建目标数组.
        List<CaptureTarget> attributes = new ArrayList<>();
        Iterator<Holder.Reference<net.minecraft.world.entity.ai.attributes.Attribute>> holders = BuiltInRegistries.ATTRIBUTE.listElements().iterator();
        while (holders.hasNext()) {
            Holder<net.minecraft.world.entity.ai.attributes.Attribute> holder = holders.next();
            Object id = RegistryProxy.INSTANCE.getKey(BuiltInRegistries.ATTRIBUTE, holder.value());
            if (options.attributeAllowed(id.toString())) {
                NamespacedKey key = new NamespacedKey(IdentifierProxy.INSTANCE.getNamespace(id), IdentifierProxy.INSTANCE.getPath(id));
                attributes.add(new CaptureTarget(key, holder));
            }
        }
        targets = new CaptureTargets(options, attributes.toArray(CaptureTarget[]::new));
        this.captureTargets = targets;
        return targets;
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Attributes attributes) {
        AttributeOptions options = PluginConfig.synchronization$attributes();
        AttributeValue[] values = attributes.values();
        for (int i = 0; i < values.length; i++) {
            AttributeValue value = values[i];
            if (!options.attributeAllowed(value.key().toString())) continue;
            Attribute attribute = Registry.ATTRIBUTE.get(value.key());
            if (attribute == null) continue;
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) continue;

            for (AttributeModifier modifier : instance.getModifiers()) {
                if (!options.modifierBlacklisted(modifier.getKey().toString())) {
                    instance.removeModifier(modifier);
                }
            }
            instance.setBaseValue(value.base());
            ModifierValue[] modifiers = value.modifiers();
            for (int modifierIndex = 0; modifierIndex < modifiers.length; modifierIndex++) {
                ModifierValue modifier = modifiers[modifierIndex];
                if (options.modifierBlacklisted(modifier.key().toString())) continue;
                instance.addModifier(new AttributeModifier(modifier.key(), modifier.amount(), modifier.operation(), modifier.slotGroup()));
            }
        }
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull Attributes attributes) {
        Tag merged = mergeNative(playerData.get("attributes"), attributes, PluginConfig.synchronization$attributes());
        playerData.put("attributes", merged);
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    @NotNull
    static Tag mergeNative(Tag current, @NotNull Attributes attributes, @NotNull AttributeOptions options) {
        ListTag merged = nativeAttributes(current);
        AttributeValue[] values = attributes.values();
        for (int i = 0; i < values.length; i++) {
            AttributeValue value = values[i];
            String attributeId = value.key().toString();
            if (!options.attributeAllowed(attributeId)) continue;
            mergeAttribute(merged, value, options);
        }
        return merged;
    }

    @NotNull
    private static ListTag nativeAttributes(Tag current) {
        return current instanceof ListTag list ? list.deepClone() : NBT.createList();
    }

    private static void mergeAttribute(ListTag attributes, AttributeValue value, AttributeOptions options) {
        String attributeId = value.key().toString();
        int index = findAttribute(attributes, attributeId);
        CompoundTag merged = index < 0 ? NBT.createCompound() : attributes.getCompound(index).deepClone();
        merged.putString("id", attributeId);
        merged.putDouble("base", value.base());

        ListTag modifiers = NBT.createList();
        ListTag localModifiers = merged.getList("modifiers", null);
        if (localModifiers != null) {
            for (int i = 0; i < localModifiers.size(); i++) {
                CompoundTag modifier = localModifiers.getCompound(i, null);
                if (modifier == null) continue;
                String modifierId = modifier.getString("id", null);
                // 无法识别的本地条目按未知数据保留; 明确命中黑名单的 modifier 也只属于目标服.
                if (modifierId == null || options.modifierBlacklisted(modifierId)) modifiers.add(modifier.deepClone());
            }
        }
        ModifierValue[] remoteModifiers = value.modifiers();
        for (int i = 0; i < remoteModifiers.length; i++) {
            ModifierValue modifier = remoteModifiers[i];
            if (options.modifierBlacklisted(modifier.key().toString())) continue;
            modifiers.add(nativeModifier(modifier));
        }
        merged.put("modifiers", modifiers);

        if (index < 0) {
            attributes.add(merged);
        } else {
            attributes.set(index, merged);
            for (int i = attributes.size() - 1; i > index; i--) {
                CompoundTag duplicate = attributes.getCompound(i, null);
                if (duplicate != null && attributeId.equals(duplicate.getString("id", null))) attributes.remove(i);
            }
        }
    }

    private static int findAttribute(ListTag attributes, String id) {
        for (int i = 0; i < attributes.size(); i++) {
            CompoundTag attribute = attributes.getCompound(i, null);
            if (attribute != null && id.equals(attribute.getString("id", null))) return i;
        }
        return -1;
    }

    @NotNull
    private static CompoundTag nativeModifier(ModifierValue value) {
        CompoundTag modifier = NBT.createCompound();
        modifier.putString("id", value.key().toString());
        modifier.putDouble("amount", value.amount());
        modifier.putString("operation", switch (value.operation()) {
            case ADD_NUMBER -> "add_value";
            case ADD_SCALAR -> "add_multiplied_base";
            case MULTIPLY_SCALAR_1 -> "add_multiplied_total";
        });
        return modifier;
    }

    private static Attributes fromStored(List<StoredAttribute> stored) {
        AttributeValue[] values = new AttributeValue[stored.size()];
        for (int i = 0; i < values.length; i++) {
            StoredAttribute value = stored.get(i);
            values[i] = new AttributeValue(value.key(), value.base(), value.modifiers().toArray(ModifierValue[]::new));
        }
        return new Attributes(values);
    }

    private static List<StoredAttribute> toStored(Attributes attributes) {
        AttributeValue[] values = attributes.values();
        List<StoredAttribute> stored = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            AttributeValue value = values[i];
            ModifierValue[] modifiers = value.modifiers();
            List<ModifierValue> storedModifiers = new ArrayList<>(modifiers.length);
            for (int modifierIndex = 0; modifierIndex < modifiers.length; modifierIndex++) {
                storedModifiers.add(modifiers[modifierIndex]);
            }
            storedModifiers.sort(Comparator.comparing(modifier -> modifier.key().toString()));
            stored.add(new StoredAttribute(value.key(), value.base(), storedModifiers));
        }
        stored.sort(Comparator.comparing(value -> value.key().toString()));
        return stored;
    }

    private static DataResult<EquipmentSlotGroup> parseSlotGroup(String value) {
        EquipmentSlotGroup group = EquipmentSlotGroup.getByName(value);
        return group == null ? DataResult.error(() -> "unknown equipment slot group: " + value) : DataResult.success(group);
    }

    public record Attributes(@NotNull AttributeValue @NotNull [] values) {
    }

    /** 缓存与在途采集共享此独立采集数据, 消费方须只读访问 modifiers 数组. */
    public record AttributeValue(@NotNull NamespacedKey key, double base, @NotNull ModifierValue @NotNull [] modifiers) {
    }

    public record ModifierValue(@NotNull NamespacedKey key, double amount, @NotNull Operation operation, @NotNull EquipmentSlotGroup slotGroup) {
    }

    private record StoredAttribute(@NotNull NamespacedKey key, double base, @NotNull List<ModifierValue> modifiers) {
    }

    private record CaptureTarget(NamespacedKey key, Holder<net.minecraft.world.entity.ai.attributes.Attribute> holder) {
    }

    private record CaptureTargets(AttributeOptions options, CaptureTarget[] attributes) {
    }

    /** 单实例缓存, 变更时只丢弃引用, 下一次采集才重建独立采集数据. */
    private static final class CaptureCache implements Consumer<net.minecraft.world.entity.ai.attributes.AttributeInstance> {
        private final Consumer<net.minecraft.world.entity.ai.attributes.AttributeInstance> delegate;
        private AttributeOptions capturedOptions;
        @Nullable private AttributeValue value;

        private CaptureCache(Consumer<net.minecraft.world.entity.ai.attributes.AttributeInstance> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void accept(net.minecraft.world.entity.ai.attributes.AttributeInstance instance) {
            // 先失效, 保证原回调重入采集或抛异常时都无法读到旧值.
            // 部分 NMS 修改还会在回调返回后收尾, 此处只使属性采集缓存失效, 不采集或编码.
            this.value = null;
            this.delegate.accept(instance);
        }

        @NotNull
        private AttributeValue capture(net.minecraft.world.entity.ai.attributes.AttributeInstance instance, NamespacedKey key, AttributeOptions options) {
            AttributeValue captured = this.value;
            if (captured != null && this.capturedOptions == options) return captured;
            // 配置 reload 同样使缓存失效; 完整采集成功后才发布, 失败可在下一轮重试.
            captured = captureAttribute(instance, key, options);
            this.capturedOptions = options;
            this.value = captured;
            return captured;
        }
    }
}

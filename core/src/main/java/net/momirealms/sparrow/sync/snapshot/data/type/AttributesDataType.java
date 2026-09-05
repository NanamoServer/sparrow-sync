package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.NbtOps;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.AttributeOptions;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 同步白名单内属性的基础值与可跨服 modifier, 服务器本地 modifier 由配置黑名单保留.
 * todo 性能存在问题
 */
public final class AttributesDataType extends CodecDataType<AttributesDataType.Attributes> implements NativePlayerDataType<AttributesDataType.Attributes> {
    public static final DataKey ATTRIBUTES = DataKey.sparrow("attributes");

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

    public AttributesDataType() {
        super(ATTRIBUTES, StorageFormat.STRUCTURED, CODEC);
    }

    @Override
    @NotNull
    public Set<DataKey> dependencies() {
        return Set.of(InventoryDataType.INVENTORY, PotionEffectsDataType.POTION_EFFECTS);
    }

    @Override
    @NotNull
    protected Attributes captureValue(@NotNull Player player) {
        AttributeOptions options = PluginConfig.synchronization$attributes();
        AttributeValue[] values = new AttributeValue[16];
        int count = 0;
        for (Attribute attribute : Registry.ATTRIBUTE) {
            NamespacedKey key = attribute.getKey();
            if (!options.attributeAllowed(key.toString())) continue;
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) continue;

            Collection<AttributeModifier> currentModifiers = instance.getModifiers();
            ModifierValue[] modifiers = new ModifierValue[currentModifiers.size()];
            int modifierCount = 0;
            for (AttributeModifier modifier : currentModifiers) {
                if (options.modifierBlacklisted(modifier.getKey().toString())) continue;
                modifiers[modifierCount++] = new ModifierValue(modifier.getKey(), modifier.getAmount(), modifier.getOperation(), modifier.getSlotGroup());
            }
            if (modifierCount < modifiers.length) modifiers = Arrays.copyOf(modifiers, modifierCount);
            if (count == values.length) values = Arrays.copyOf(values, values.length << 1);
            values[count++] = new AttributeValue(key, instance.getBaseValue(), modifiers);
        }
        return new Attributes(Arrays.copyOf(values, count));
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
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull net.minecraft.nbt.CompoundTag playerData, @NotNull Attributes attributes) {
        net.minecraft.nbt.Tag merged = mergeNative(playerData.get("attributes"), attributes, PluginConfig.synchronization$attributes());
        playerData.put("attributes", merged);
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    @NotNull
    static net.minecraft.nbt.Tag mergeNative(net.minecraft.nbt.Tag current, @NotNull Attributes attributes, @NotNull AttributeOptions options) {
        ListTag merged = nativeAttributes(current);
        AttributeValue[] values = attributes.values();
        for (int i = 0; i < values.length; i++) {
            AttributeValue value = values[i];
            String attributeId = value.key().toString();
            if (!options.attributeAllowed(attributeId)) continue;
            mergeAttribute(merged, value, options);
        }
        return NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, merged);
    }

    @NotNull
    private static ListTag nativeAttributes(net.minecraft.nbt.Tag current) {
        if (current == null) return NBT.createList();
        Tag converted = NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, current);
        return converted instanceof ListTag list ? list.deepClone() : NBT.createList();
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

    public record AttributeValue(@NotNull NamespacedKey key, double base, @NotNull ModifierValue @NotNull [] modifiers) {
    }

    public record ModifierValue(@NotNull NamespacedKey key, double amount, @NotNull Operation operation, @NotNull EquipmentSlotGroup slotGroup) {
    }

    private record StoredAttribute(@NotNull NamespacedKey key, double base, @NotNull List<ModifierValue> modifiers) {
    }
}

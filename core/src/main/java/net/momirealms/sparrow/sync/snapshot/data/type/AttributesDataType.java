package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.AttributeOptions;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
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
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 同步白名单内属性的基础值与可跨服 modifier, 服务器本地 modifier 由配置黑名单保留.
 * // todo 可能有问题
 */
public final class AttributesDataType extends CodecDataType<List<AttributesDataType.AttributeValue>> {
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
    private static final Codec<AttributeValue> ATTRIBUTE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            KEY_CODEC.fieldOf("key").forGetter(AttributeValue::key),
            Codec.DOUBLE.fieldOf("base").forGetter(AttributeValue::base),
            MODIFIER_CODEC.listOf().fieldOf("modifiers").forGetter(AttributeValue::modifiers)
    ).apply(instance, AttributeValue::new));
    static final Codec<List<AttributeValue>> CODEC = ATTRIBUTE_CODEC.listOf();

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
    protected List<AttributeValue> captureValue(@NotNull Player player) {
        AttributeOptions options = PluginConfig.synchronization$attributes();
        List<AttributeValue> values = new ArrayList<>();
        for (Attribute attribute : Registry.ATTRIBUTE) {
            NamespacedKey key = attribute.getKey();
            if (!options.attributeAllowed(key.toString())) {
                continue;
            }
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }

            List<ModifierValue> modifiers = new ArrayList<>();
            for (AttributeModifier modifier : instance.getModifiers()) {
                if (options.modifierBlacklisted(modifier.getKey().toString())) {
                    continue;
                }
                modifiers.add(new ModifierValue(modifier.getKey(), modifier.getAmount(), modifier.getOperation(), modifier.getSlotGroup()));
            }
            modifiers.sort(Comparator.comparing(value -> value.key().toString()));
            values.add(new AttributeValue(key, instance.getBaseValue(), modifiers));
        }
        values.sort(Comparator.comparing(value -> value.key().toString()));
        return values;
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull List<AttributeValue> values) {
        AttributeOptions options = PluginConfig.synchronization$attributes();
        int size = values.size();
        for (int i = 0; i < size; i++) {
            AttributeValue value = values.get(i);
            if (!options.attributeAllowed(value.key().toString())) {
                continue;
            }
            Attribute attribute = Registry.ATTRIBUTE.get(value.key());
            if (attribute == null) {
                continue;
            }
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }

            for (AttributeModifier modifier : instance.getModifiers()) {
                if (!options.modifierBlacklisted(modifier.getKey().toString())) {
                    instance.removeModifier(modifier);
                }
            }
            instance.setBaseValue(value.base());
            int modifierCount = value.modifiers().size();
            for (int modifierIndex = 0; modifierIndex < modifierCount; modifierIndex++) {
                ModifierValue modifier = value.modifiers().get(modifierIndex);
                if (options.modifierBlacklisted(modifier.key().toString())) {
                    continue;
                }
                instance.addModifier(new AttributeModifier(modifier.key(), modifier.amount(), modifier.operation(), modifier.slotGroup()));
            }
        }
    }

    private static DataResult<EquipmentSlotGroup> parseSlotGroup(String value) {
        EquipmentSlotGroup group = EquipmentSlotGroup.getByName(value);
        return group == null ? DataResult.error(() -> "unknown equipment slot group: " + value) : DataResult.success(group);
    }

    public record AttributeValue(@NotNull NamespacedKey key, double base, @NotNull List<ModifierValue> modifiers) {
    }

    public record ModifierValue(@NotNull NamespacedKey key, double amount, @NotNull Operation operation, @NotNull EquipmentSlotGroup slotGroup) {
    }
}

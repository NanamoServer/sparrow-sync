package net.momirealms.sparrow.sync.snapshot.data.type;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.Attributes;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.AttributeValue;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.ModifierValue;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AttributesDataTypeTest {

    @Test
    void codecPreservesBaseValuesAndModifiers() {
        Attributes expected = new Attributes(new AttributeValue[]{new AttributeValue(
                NamespacedKey.minecraft("max_health"),
                24.0,
                new ModifierValue[]{
                        new ModifierValue(NamespacedKey.minecraft("custom_health"), 4.0, Operation.ADD_NUMBER, EquipmentSlotGroup.ANY),
                        new ModifierValue(NamespacedKey.minecraft("scaled_health"), 0.2, Operation.ADD_SCALAR, EquipmentSlotGroup.ANY),
                        new ModifierValue(NamespacedKey.minecraft("total_health"), 0.1, Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY)
                }
        )});

        Tag encoded = AttributesDataType.CODEC.encodeStart(NBTOps.INSTANCE, expected).getOrThrow();
        Attributes decoded = AttributesDataType.CODEC.parse(NBTOps.INSTANCE, encoded).getOrThrow();

        assertEquals(expected.values()[0].key(), decoded.values()[0].key());
        assertEquals(expected.values()[0].base(), decoded.values()[0].base());
        assertArrayEquals(expected.values()[0].modifiers(), decoded.values()[0].modifiers());
        CompoundTag attribute = assertInstanceOf(ListTag.class, encoded).getCompound(0);
        assertEquals("minecraft:max_health", attribute.getString("key"));
        ListTag modifiers = attribute.getList("modifiers");
        assertEquals("add_value", modifiers.getCompound(0).getString("operation"));
        assertEquals("add_multiplied_base", modifiers.getCompound(1).getString("operation"));
        assertEquals("add_multiplied_total", modifiers.getCompound(2).getString("operation"));
    }

    @Test
    void dependenciesPlaceInventoryAndEffectsBeforeAttributes() {
        AttributesDataType type = new AttributesDataType();

        assertEquals(
                Set.of(InventoryDataType.INVENTORY, PotionEffectsDataType.POTION_EFFECTS),
                type.dependencies()
        );
    }
}

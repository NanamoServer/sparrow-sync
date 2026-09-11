package net.momirealms.sparrow.sync.compatibility.migration.husksync;

import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.proxy.minecraft.nbt.TagParserProxy;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.AdvancementValue;
import net.momirealms.sparrow.sync.snapshot.data.type.AdvancementsDataType.Advancements;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.AttributeValue;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.Attributes;
import net.momirealms.sparrow.sync.snapshot.data.type.AttributesDataType.ModifierValue;
import net.momirealms.sparrow.sync.snapshot.data.type.ExperienceDataType.Experience;
import net.momirealms.sparrow.sync.snapshot.data.type.FlightStatusDataType.FlightStatus;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType.Health;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthScaleDataType.HealthScale;
import net.momirealms.sparrow.sync.snapshot.data.type.HungerDataType.Hunger;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType.Inventory;
import net.momirealms.sparrow.sync.snapshot.data.type.LocationDataType.PlayerLocation;
import net.momirealms.sparrow.sync.snapshot.data.type.StatisticsDataType.Statistics;
import net.momirealms.sparrow.sync.util.ItemCodec;
import net.william278.husksync.data.BukkitData;
import net.william278.husksync.data.Data;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementRequirement;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.craftbukkit.CraftStatistic;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.potion.CraftPotionUtil;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class HuskSyncConverter {
    private final DataRegistry registry;

    HuskSyncConverter(DataRegistry registry) {
        this.registry = registry;
    }

    @NotNull
    Map<DataKey, Tag> convert(Map<String, Data> fields) throws Exception {
        Map<DataKey, Tag> result = new HashMap<>();
        for (Map.Entry<String, Data> entry : fields.entrySet()) {
            String key = entry.getKey();
            Data value = entry.getValue();
            switch (key) {
                case "husksync:inventory" -> {
                    BukkitData.Items.Inventory inventory = (BukkitData.Items.Inventory) value;
                    int heldSlot = inventory.getHeldItemSlot();
                    this.put(result, "inventory", new Inventory(items(inventory), heldSlot, 0));
                }
                case "husksync:ender_chest" -> this.put(result, "ender_chest", new ItemCodec.LoadedItems(items((BukkitData.Items.EnderChest) value), 0));
                case "husksync:health" -> {
                    BukkitData.Health health = (BukkitData.Health) value;
                    this.put(result, "health", new Health(health.getHealth()));
                    this.put(result, "health_scale", new HealthScale(health.getHealthScale(), health.isHealthScaled()));
                }
                // 源格式没有饥饿计时器, 沿用 Hunger codec 的缺省值 0.
                case "husksync:hunger" -> {
                    Data.Hunger hunger = (Data.Hunger) value;
                    this.put(result, "hunger", new Hunger(hunger.getFoodLevel(), hunger.getSaturation(), hunger.getExhaustion(), 0));
                }
                case "husksync:experience" -> {
                    Data.Experience experience = (Data.Experience) value;
                    this.put(result, "experience", new Experience(experience.getTotalExperience(), experience.getExpLevel(), experience.getExpProgress()));
                }
                case "husksync:game_mode" -> this.put(result, "game_mode", GameMode.valueOf(((Data.GameMode) value).getGameMode()));
                case "husksync:flight_status" -> {
                    BukkitData.FlightStatus flight = (BukkitData.FlightStatus) value;
                    this.put(result, "flight_status", new FlightStatus(flight.isAllowFlight(), flight.isFlying()));
                }
                // Sparrow 的位置类型以世界名寻址, 保留源名字且不要求迁移时已经加载该世界.
                case "husksync:location" -> {
                    Data.Location location = (Data.Location) value;
                    this.put(result, "location", new PlayerLocation(location.getWorld().name(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()));
                }
                case "husksync:potion_effects" -> {
                    Collection<PotionEffect> effects = ((BukkitData.PotionEffects) value).getEffects();
                    List<MobEffectInstance> converted = new ArrayList<>(effects.size());
                    for (PotionEffect effect : effects) {
                        converted.add(CraftPotionUtil.fromBukkit(effect));
                    }
                    this.put(result, "potion_effects", converted);
                }
                case "husksync:attributes" -> this.put(result, "attributes", attributes((Data.Attributes) value));
                case "husksync:advancements" -> this.put(result, "advancements", advancements((BukkitData.Advancements) value));
                case "husksync:statistics" -> this.put(result, "statistics", statistics((Data.Statistics) value));
                case "husksync:persistent_data" -> {
                    // 源插件已经解出 NBTCompound; SNBT 只用于两个 NBT 类型体系间的无损转换.
                    Object parser = TagParserProxy.INSTANCE.create(NBTOps.INSTANCE);
                    CompoundTag tag = (CompoundTag) TagParserProxy.INSTANCE.parseFully(parser, ((BukkitData.PersistentData) value).getPersistentData().toString());
                    result.put(DataKey.sparrow("persistent_data"), tag);
                }
                default -> throw new IOException("Unsupported HuskSync data field: " + key);
            }
        }
        return result;
    }

    private <T> void put(Map<DataKey, Tag> target, String name, T value) throws IOException {
        DataKey key = DataKey.sparrow(name);
        PlayerDataType<T> type = (PlayerDataType<T>) this.registry.type(key);
        target.put(key, type.encode(value));
    }

    private static ItemStack[] items(BukkitData.Items value) {
        org.bukkit.inventory.ItemStack[] contents = value.getContents();
        ItemStack[] items = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            items[i] = CraftItemStack.asNMSCopy(contents[i]);
        }
        return items;
    }

    private static Attributes attributes(Data.Attributes value) throws IOException {
        List<Data.Attributes.Attribute> source = value.getAttributes();
        AttributeValue[] attributes = new AttributeValue[source.size()];
        for (int i = 0; i < source.size(); i++) {
            Data.Attributes.Attribute entry = source.get(i);
            NamespacedKey key = NamespacedKey.fromString(entry.name());
            Collection<Data.Attributes.Modifier> sourceModifiers = entry.modifiers();
            ModifierValue[] modifiers = new ModifierValue[sourceModifiers.size()];
            int index = 0;
            for (Data.Attributes.Modifier modifier : sourceModifiers) {
                if (modifier.hasUuid()) {
                    throw new IOException("Legacy UUID attribute modifier is unsupported");
                }
                int operation = modifier.operation();
                String slotName = modifier.slotGroup();
                EquipmentSlotGroup slot = EquipmentSlotGroup.getByName(slotName);
                modifiers[index++] = new ModifierValue(NamespacedKey.fromString(modifier.name()), modifier.amount(), Operation.values()[operation], slot);
            }
            attributes[i] = new AttributeValue(key, entry.baseValue(), modifiers);
        }
        return new Attributes(attributes);
    }

    private static Advancements advancements(BukkitData.Advancements value) {
        List<Data.Advancements.Advancement> completed = value.getCompleted();
        AdvancementValue[] values = new AdvancementValue[completed.size()];
        for (int i = 0; i < completed.size(); i++) {
            Data.Advancements.Advancement entry = completed.get(i);
            NamespacedKey key = NamespacedKey.fromString(entry.getKey());
            Map<String, Date> criteria = entry.getCompletedCriteria();
            String[] names = new String[criteria.size()];
            Instant[] times = new Instant[criteria.size()];
            int index = 0;
            for (Map.Entry<String, Date> criterion : criteria.entrySet()) {
                names[index] = criterion.getKey();
                times[index++] = criterion.getValue().toInstant();
            }
            Advancement definition = Bukkit.getAdvancement(key);
            boolean done = definition != null;
            if (definition != null) {
                for (AdvancementRequirement requirement : definition.getRequirements().getRequirements()) {
                    if (requirement.getRequiredCriteria().stream().noneMatch(criteria::containsKey)) done = false;
                }
            }
            // 源没有 done 标记; 已加载定义可重算, 未知成就保留全部 criterion 与时间.
            values[i] = new AdvancementValue(key, names, times, done);
        }
        return new Advancements(values);
    }

    private static Statistics statistics(Data.Statistics value) throws IOException {
        List<Stat<?>> statistics = new ArrayList<>();
        List<Integer> amounts = new ArrayList<>();
        Map<String, Integer> generic = value.getGenericStatistics();
        for (Map.Entry<String, Integer> entry : generic.entrySet()) {
            Statistic statistic = Statistic.valueOf(NamespacedKey.fromString(entry.getKey()).getKey().toUpperCase(Locale.ROOT));
            statistics.add(CraftStatistic.getNMSStatistic(statistic));
            amounts.add(entry.getValue());
        }
        addStatistics(statistics, amounts, value.getBlockStatistics(), Statistic.Type.BLOCK);
        addStatistics(statistics, amounts, value.getItemStatistics(), Statistic.Type.ITEM);
        addStatistics(statistics, amounts, value.getEntityStatistics(), Statistic.Type.ENTITY);
        int[] counts = new int[amounts.size()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = amounts.get(i);
        }
        return new Statistics(statistics.toArray(Stat<?>[]::new), counts);
    }

    private static void addStatistics(List<Stat<?>> statistics, List<Integer> amounts, Map<String, Map<String, Integer>> source, Statistic.Type type) throws IOException {
        for (Map.Entry<String, Map<String, Integer>> entry : source.entrySet()) {
            Statistic statistic = Statistic.valueOf(NamespacedKey.fromString(entry.getKey()).getKey().toUpperCase(Locale.ROOT));
            for (Map.Entry<String, Integer> count : entry.getValue().entrySet()) {
                Stat<?> converted;
                if (type == Statistic.Type.ENTITY) {
                    EntityType entity = Registry.ENTITY_TYPE.get(NamespacedKey.fromString(count.getKey()));
                    converted = CraftStatistic.getEntityStatistic(statistic, entity);
                } else {
                    Material material = Material.matchMaterial(count.getKey());
                    converted = switch (statistic) {
                        case MINE_BLOCK -> Stats.BLOCK_MINED.get(CraftMagicNumbers.getBlock(material));
                        case CRAFT_ITEM -> Stats.ITEM_CRAFTED.get(CraftMagicNumbers.getItem(material));
                        case USE_ITEM -> Stats.ITEM_USED.get(CraftMagicNumbers.getItem(material));
                        case BREAK_ITEM -> Stats.ITEM_BROKEN.get(CraftMagicNumbers.getItem(material));
                        case PICKUP -> Stats.ITEM_PICKED_UP.get(CraftMagicNumbers.getItem(material));
                        case DROP -> Stats.ITEM_DROPPED.get(CraftMagicNumbers.getItem(material));
                        default -> throw new IOException("Unsupported material statistic: " + statistic);
                    };
                }
                statistics.add(converted);
                amounts.add(count.getValue());
            }
        }
    }

}

package net.momirealms.sparrow.sync.compatibility.migration.invsync;

import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffectInstance;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
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
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType.Health;
import net.momirealms.sparrow.sync.snapshot.data.type.HungerDataType.Hunger;
import net.momirealms.sparrow.sync.snapshot.data.type.StatisticsDataType.Statistics;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementRequirement;
import org.bukkit.craftbukkit.CraftStatistic;
import org.bukkit.craftbukkit.potion.CraftPotionUtil;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.momirealms.sparrow.sync.compatibility.migration.invsync.InvSyncAccess.call;

final class InvSyncConverter {
    private final Plugin plugin;
    private final InvSyncAccess access;
    private final DataRegistry registry;

    InvSyncConverter(Plugin plugin, InvSyncAccess access, DataRegistry registry) {
        this.plugin = plugin;
        this.access = access;
        this.registry = registry;
    }

    // 读取一份 InvSync PlayerData，并生成可以写入 Sparrow 快照的字段集合。
    @NotNull
    Map<DataKey, Tag> convert(Object data) throws Exception {
        Map<DataKey, Tag> result = new HashMap<>();
        // 迁移只生成持久 NBT, 未注册的自定义附魔同样原样保留.
        byte[] inventory = this.bytes(data, "inventory");
        if (inventory != null) {
            // 源库未保存手持槽, 快照固定选择第一个快捷栏槽位.
            CompoundTag items = this.items(inventory, 41);
            items.putInt("heldSlot", 0);
            result.put(DataKey.sparrow("inventory"), items);
        }
        byte[] enderChest = this.bytes(data, "enderChest");
        if (enderChest != null) {
            result.put(DataKey.sparrow("ender_chest"), this.items(enderChest, 27));
        }
        // PlayerData 只保存当前生命和最大生命，最大生命放入目标属性列表。
        this.put(result, "health", new Health(((Number) call(data, "getHealth")).doubleValue()));
        double maximum = ((Number) call(data, "getMaxHealth")).doubleValue();
        this.put(result, "attributes", new Attributes(new AttributeValue[]{new AttributeValue(NamespacedKey.minecraft("max_health"), maximum, new ModifierValue[0])}));
        // 源库未保存饱和度、消耗值、计时器和总经验, 迁移缺省为 0.
        this.put(result, "hunger", new Hunger(((Number) call(data, "getFood")).intValue(), 0, 0, 0));
        this.put(result, "experience", new Experience(0, (int) call(data, "getLevel"), (float) call(data, "getExp")));
        // 效果、统计、成就和 PDC 都调用源插件的读取组件，保留它们自己的格式兼容工作。
        byte[] buffs = this.bytes(data, "buffs");
        if (buffs != null) {
            List<?> decoded = (List<?>) this.access.decodeJson(buffs, "bukkit.serializer.buff.GsonPotionEffectData");
            List<MobEffectInstance> effects = new ArrayList<>(decoded.size());
            for (int i = 0; i < decoded.size(); i++) {
                PotionEffect effect = (PotionEffect) call(decoded.get(i), "toPotionEffect");
                if (effect != null) {
                    effects.add(CraftPotionUtil.fromBukkit(effect));
                }
            }
            this.put(result, "potion_effects", effects);
        }
        byte[] statistics = this.bytes(data, "statistic");
        if (statistics != null) {
            this.put(result, "statistics", this.statistics(this.access.decodeStatistics(statistics)));
        }
        byte[] advancements = this.bytes(data, "advancements");
        if (advancements != null) {
            Object serializer = call(this.plugin, "getAdvancementsSerializer");
            this.put(result, "advancements", this.advancements(this.access.decodeAdvancements(serializer, advancements)));
        }
        byte[] persistentData = this.bytes(data, "persistentData");
        if (persistentData != null) {
            String snbt = this.access.decodeNbt(new String(persistentData, StandardCharsets.UTF_8));
            Object parser = TagParserProxy.INSTANCE.create(NBTOps.INSTANCE);
            result.put(DataKey.sparrow("persistent_data"), (CompoundTag) TagParserProxy.INSTANCE.parseFully(parser, snbt));
        }
        // otherData / pluginData 属于来源插件扩展, 缺少 Sparrow DataKey 契约时不生成对应类型.
        return result;
    }

    // 从一份源 PlayerData 取出已初始化的二进制字段。
    private byte @Nullable [] bytes(Object data, String field) throws Exception {
        if (!(boolean) call(data, field + "IsInit")) return null;
        byte[] bytes = (byte[]) call(data, "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1));
        // 零长数组没有可读取的字段正文.
        return bytes.length == 0 ? null : bytes;
    }

    // 源组件负责解压和读取 SNBT; 只添加 Sparrow 的容器结构与槽位.
    private CompoundTag items(byte[] bytes, int size) throws Exception {
        var items = NBT.createList();
        Object parser = TagParserProxy.INSTANCE.create(NBTOps.INSTANCE);
        for (Map<String, Object> entry : this.access.decodeItems(bytes)) {
            int slot = ((Number) entry.get("slot")).intValue();
            String snbt = this.access.decodeNbt((String) entry.get("item"));
            CompoundTag item = (CompoundTag) TagParserProxy.INSTANCE.parseFully(parser, snbt);
            item.putInt("slot", slot);
            items.add(item);
            size = Math.max(size, slot + 1);
        }
        CompoundTag result = NBT.createCompound();
        result.putInt("size", size);
        result.put("items", items);
        return result;
    }

    //将 InvSync 的已完成成就列表转换为 Sparrow 成就记录。
    private Advancements advancements(List<?> source) throws Exception {
        AdvancementValue[] values = new AdvancementValue[source.size()];
        for (int i = 0; i < source.size(); i++) {
            Object entry = source.get(i);
            NamespacedKey key = NamespacedKey.fromString((String) call(entry, "getKey"));
            Map<String, Long> criteria = (Map<String, Long>) call(entry, "getCompletedCriteria");
            String[] names = new String[criteria.size()];
            Instant[] times = new Instant[criteria.size()];
            int index = 0;
            for (Map.Entry<String, Long> criterion : criteria.entrySet()) {
                names[index] = criterion.getKey();
                // 源日期为空时仍保留已授予的 criterion, 迁移将未知日期固定为 epoch.
                times[index++] = criterion.getValue() == null ? Instant.EPOCH : Instant.ofEpochMilli(criterion.getValue());
            }
            Advancement definition = Bukkit.getAdvancement(key);
            boolean done = definition != null;
            if (definition != null) {
                for (AdvancementRequirement requirement : definition.getRequirements().getRequirements()) {
                    if (requirement.getRequiredCriteria().stream().noneMatch(criteria::containsKey)) done = false;
                }
            }
            values[i] = new AdvancementValue(key, names, times, done);
        }
        return new Advancements(values);
    }

    // 将 InvSync 的四类 Bukkit 统计转换为 NMS Stat 和对应计数。
    private Statistics statistics(Object source) throws Exception {
        List<Stat<?>> statistics = new ArrayList<>();
        List<Integer> amounts = new ArrayList<>();
        Map<String, Integer> generic = (Map<String, Integer>) call(source, "getUntypedStatistics");
        if (generic != null) {
            for (Map.Entry<String, Integer> entry : generic.entrySet()) {
                statistics.add(CraftStatistic.getNMSStatistic(Statistic.valueOf(entry.getKey())));
                amounts.add(entry.getValue());
            }
        }
        this.addStatistics(statistics, amounts, (Map<String, Map<String, Integer>>) call(source, "getBlockStatistics"), false);
        this.addStatistics(statistics, amounts, (Map<String, Map<String, Integer>>) call(source, "getItemStatistics"), false);
        this.addStatistics(statistics, amounts, (Map<String, Map<String, Integer>>) call(source, "getEntityStatistics"), true);
        int[] counts = new int[amounts.size()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = amounts.get(i);
        }
        return new Statistics(statistics.toArray(Stat<?>[]::new), counts);
    }

    // 追加一类带 Material 或 EntityType 维度的统计。
    private void addStatistics(List<Stat<?>> statistics, List<Integer> amounts, Map<String, Map<String, Integer>> source, boolean entities) throws IOException {
        if (source == null) return;
        for (Map.Entry<String, Map<String, Integer>> entry : source.entrySet()) {
            Statistic statistic = Statistic.valueOf(entry.getKey());
            for (Map.Entry<String, Integer> count : entry.getValue().entrySet()) {
                Stat<?> converted;
                if (entities) {
                    converted = CraftStatistic.getEntityStatistic(statistic, EntityType.valueOf(count.getKey()));
                } else {
                    Material material = Material.valueOf(count.getKey());
                    converted = switch (statistic) {
                        case MINE_BLOCK -> Stats.BLOCK_MINED.get(CraftMagicNumbers.getBlock(material));
                        case CRAFT_ITEM -> Stats.ITEM_CRAFTED.get(CraftMagicNumbers.getItem(material));
                        case USE_ITEM -> Stats.ITEM_USED.get(CraftMagicNumbers.getItem(material));
                        case BREAK_ITEM -> Stats.ITEM_BROKEN.get(CraftMagicNumbers.getItem(material));
                        case PICKUP -> Stats.ITEM_PICKED_UP.get(CraftMagicNumbers.getItem(material));
                        case DROP -> Stats.ITEM_DROPPED.get(CraftMagicNumbers.getItem(material));
                        default -> throw new IOException("Unsupported InvSync material statistic: " + statistic);
                    };
                }
                statistics.add(converted);
                amounts.add(count.getValue());
            }
        }
    }

    // 用已注册的 Sparrow 数据类型将转换值编码为 NBT Tag。
    private <T> void put(Map<DataKey, Tag> target, String name, T value) {
        DataKey key = DataKey.sparrow(name);
        PlayerDataType<T> type = (PlayerDataType<T>) this.registry.type(key);
        target.put(key, type.encode(value));
    }
}

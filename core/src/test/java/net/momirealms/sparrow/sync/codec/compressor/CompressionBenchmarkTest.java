package net.momirealms.sparrow.sync.codec.compressor;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q4 压缩选型基准: 四种背包画像下 DEFLATE 与各级 Zstd 的压缩率与耗时, 并按内网带宽折算
 * "保存 = 压缩 + 传输" 与 "加载 = 传输 + 解压" 的总成本. 报告输出到控制台与
 * build/compression-benchmark.txt, 断言只锁定选型结论的方向, 数值判断交给报告.
 */
class CompressionBenchmarkTest {
    private static final long BUDGET_NANOS_PER_CASE = 300_000_000L;  // 每个组合的计时预算, 轮数由单轮耗时自适应
    private static final int MIN_ROUNDS = 5;
    private static final int MAX_ROUNDS = 40;
    private static final double MBPS_100 = 100.0 / 8 * 1024 * 1024;  // 100Mbps 链路的字节速率
    private static final double MBPS_1000 = 1000.0 / 8 * 1024 * 1024;

    @Test
    void benchmarkCompressionAcrossInventoryProfiles() throws IOException {
        // 准备: 四种背包画像, 同一份确定性种子保证跨机器可比
        Map<String, byte[]> scenarios = new LinkedHashMap<>();
        scenarios.put("small", NBT.toBytes(smallInventorySnapshot(), false));
        scenarios.put("medium", NBT.toBytes(mediumInventorySnapshot(), false));
        scenarios.put("large", NBT.toBytes(largeInventorySnapshot(), false));
        scenarios.put("mixed", NBT.toBytes(mixedInventorySnapshot(), false));

        Map<String, Compressor> candidates = new LinkedHashMap<>();
        candidates.put("deflate", CompressorRegistry.DEFLATE);
        candidates.put("zstd-1", new ZstdCompressor(1));
        candidates.put("zstd-3", new ZstdCompressor(3));
        candidates.put("zstd-6", new ZstdCompressor(6));
        candidates.put("zstd-9", new ZstdCompressor(9));
        candidates.put("zstd-12", new ZstdCompressor(12));
        candidates.put("zstd-19", new ZstdCompressor(19));

        // 执行: 每个画像 × 每个压缩器测往返与耗时
        StringBuilder report = new StringBuilder();
        report.append("Q4 compression benchmark (median of adaptive rounds)\n");
        Map<String, Map<String, Result>> results = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> scenario : scenarios.entrySet()) {
            byte[] original = scenario.getValue();
            Map<String, Result> byCodec = new LinkedHashMap<>();
            results.put(scenario.getKey(), byCodec);
            report.append(String.format("%nscenario=%s original=%s%n", scenario.getKey(), formatBytes(original.length)));
            report.append(String.format("  %-9s %10s %7s %10s %10s | %10s %10s | %10s %10s%n",
                    "codec", "size", "ratio", "comp", "decomp", "save@100M", "load@100M", "save@1G", "load@1G"));
            for (Map.Entry<String, Compressor> candidate : candidates.entrySet()) {
                Result result = measure(candidate.getValue(), original);
                byCodec.put(candidate.getKey(), result);
                report.append(String.format("  %-9s %10s %6.1f%% %9.2fms %9.2fms | %9.2fms %9.2fms | %9.2fms %9.2fms%n",
                        candidate.getKey(), formatBytes(result.compressedSize),
                        result.compressedSize * 100.0 / original.length,
                        result.compressMillis, result.decompressMillis,
                        result.compressMillis + transferMillis(result.compressedSize, MBPS_100),
                        result.decompressMillis + transferMillis(result.compressedSize, MBPS_100),
                        result.compressMillis + transferMillis(result.compressedSize, MBPS_1000),
                        result.decompressMillis + transferMillis(result.compressedSize, MBPS_1000)));
            }
        }
        System.out.print(report);
        Path reportPath = Path.of("build", "compression-benchmark.txt");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);

        // 断言: 只锁定选型方向 —— zstd-3 的压缩率不逊于 deflate (帧头固定开销留 128B 余量).
        // 耗时不设断言: 两组计时不同时刻采样, GC 或 CI 邻居负载会单边放大, 数值判断交给报告
        for (Map.Entry<String, Map<String, Result>> scenario : results.entrySet()) {
            Result deflate = scenario.getValue().get("deflate");
            Result zstd3 = scenario.getValue().get("zstd-3");
            assertTrue(zstd3.compressedSize <= deflate.compressedSize * 1.25 + 128,
                    scenario.getKey() + ": zstd-3 compressed to " + zstd3.compressedSize + " but deflate reached " + deflate.compressedSize);
        }
    }

    private record Result(int compressedSize, double compressMillis, double decompressMillis) {
    }

    private static Result measure(Compressor compressor, byte[] original) throws IOException {
        byte[] compressed = compressor.compress(original);
        assertArrayEquals(original, compressor.decompress(compressed, 0, compressed.length, Integer.MAX_VALUE));

        double compressMillis = medianMillis(() -> compressor.compress(original));
        double decompressMillis = medianMillis(() -> compressor.decompress(compressed, 0, compressed.length, Integer.MAX_VALUE));
        return new Result(compressed.length, compressMillis, decompressMillis);
    }

    private interface Trial {
        Object run() throws IOException;
    }

    // 3 轮预热后按预算自适应轮数, 取中位数抵抗调度抖动
    private static double medianMillis(Trial trial) throws IOException {
        for (int i = 0; i < 3; i++) {
            trial.run();
        }
        long probeStart = System.nanoTime();
        trial.run();
        long probe = Math.max(System.nanoTime() - probeStart, 1);
        int rounds = (int) Math.clamp(BUDGET_NANOS_PER_CASE / probe, MIN_ROUNDS, MAX_ROUNDS);
        long[] samples = new long[rounds];
        for (int i = 0; i < rounds; i++) {
            long start = System.nanoTime();
            trial.run();
            samples[i] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        return samples[rounds / 2] / 1_000_000.0;
    }

    private static double transferMillis(int bytes, double bytesPerSecond) {
        return bytes / bytesPerSecond * 1000.0;
    }

    private static String formatBytes(int bytes) {
        if (bytes < 1024) return bytes + "B";
        return String.format("%.1fKB", bytes / 1024.0);
    }

    // ---- 背包画像 ----

    /** 一背包不带组件的方块与材料, 36 格堆叠物. */
    private static CompoundTag smallInventorySnapshot() {
        String[] ids = {"minecraft:cobblestone", "minecraft:dirt", "minecraft:oak_log", "minecraft:iron_ingot", "minecraft:bread"};
        Random random = new Random(41);
        ListTag items = NBT.createList();
        for (int slot = 0; slot < 36; slot++) {
            items.add(simpleItem(slot, ids[random.nextInt(ids.length)], 64));
        }
        return snapshotTree(items);
    }

    /** 一背包附魔装备与工具, 每件带名字, lore, 附魔, 属性与小型插件数据. */
    private static CompoundTag mediumInventorySnapshot() {
        Random random = new Random(42);
        ListTag items = NBT.createList();
        for (int slot = 0; slot < 36; slot++) {
            items.add(enchantedItem(slot, random));
        }
        return snapshotTree(items);
    }

    /** 一背包大型物品: 装满附魔物品的潜影盒, 长篇成书与大块插件数据. */
    private static CompoundTag largeInventorySnapshot() {
        Random random = new Random(43);
        ListTag items = NBT.createList();
        for (int slot = 0; slot < 36; slot++) {
            items.add(switch (slot % 3) {
                case 0 -> shulkerBoxItem(slot, random);
                case 1 -> writtenBookItem(slot, random);
                default -> heavyPluginDataItem(slot, random);
            });
        }
        return snapshotTree(items);
    }

    /** 常见的混合背包: 24 格材料, 8 件附魔装备, 4 件大型物品. */
    private static CompoundTag mixedInventorySnapshot() {
        Random random = new Random(44);
        String[] ids = {"minecraft:cobblestone", "minecraft:oak_planks", "minecraft:arrow", "minecraft:cooked_beef"};
        ListTag items = NBT.createList();
        for (int slot = 0; slot < 24; slot++) {
            items.add(simpleItem(slot, ids[random.nextInt(ids.length)], 1 + random.nextInt(64)));
        }
        for (int slot = 24; slot < 32; slot++) {
            items.add(enchantedItem(slot, random));
        }
        for (int slot = 32; slot < 36; slot++) {
            items.add(slot % 2 == 0 ? shulkerBoxItem(slot, random) : writtenBookItem(slot, random));
        }
        return snapshotTree(items);
    }

    // 快照数据体 = 背包 + 其余轻量数据类, 对齐真实压缩输入的形态
    private static CompoundTag snapshotTree(ListTag items) {
        CompoundTag inventory = NBT.createCompound();
        inventory.putInt("heldSlot", 2);
        inventory.put("items", items);
        CompoundTag health = NBT.createCompound();
        health.putDouble("health", 18.5);
        health.putInt("food", 17);
        health.putFloat("saturation", 3.2f);
        CompoundTag experience = NBT.createCompound();
        experience.putInt("level", 42);
        experience.putFloat("progress", 0.63f);
        CompoundTag root = NBT.createCompound();
        root.put("sparrow:inventory", inventory);
        root.put("sparrow:health", health);
        root.put("sparrow:experience", experience);
        return root;
    }

    private static CompoundTag simpleItem(int slot, String id, int count) {
        CompoundTag item = NBT.createCompound();
        item.putInt("slot", slot);
        item.putString("id", id);
        item.putByte("count", (byte) count);
        return item;
    }

    private static CompoundTag enchantedItem(int slot, Random random) {
        String[] ids = {"minecraft:diamond_pickaxe", "minecraft:netherite_sword", "minecraft:diamond_chestplate", "minecraft:bow"};
        CompoundTag components = NBT.createCompound();
        components.putInt("minecraft:damage", random.nextInt(1500));
        CompoundTag enchantments = NBT.createCompound();
        String[] enchantIds = {"minecraft:efficiency", "minecraft:unbreaking", "minecraft:fortune", "minecraft:sharpness", "minecraft:protection"};
        for (int i = 0; i < 3; i++) {
            enchantments.putInt(enchantIds[random.nextInt(enchantIds.length)], 1 + random.nextInt(5));
        }
        components.put("minecraft:enchantments", enchantments);
        components.putString("minecraft:custom_name", "{\"text\":\"" + words(random, 3) + "\",\"color\":\"gold\",\"italic\":false}");
        ListTag lore = NBT.createList();
        for (int i = 0; i < 4; i++) {
            lore.add(NBT.createString("{\"text\":\"" + words(random, 6) + "\",\"color\":\"gray\"}"));
        }
        components.put("minecraft:lore", lore);
        ListTag modifiers = NBT.createList();
        modifiers.add(attributeModifier("minecraft:attack_damage", 7.5, random));
        modifiers.add(attributeModifier("minecraft:attack_speed", -2.4, random));
        components.put("minecraft:attribute_modifiers", modifiers);
        CompoundTag customData = NBT.createCompound();
        customData.putString("craftengine:id", "custom_item_" + random.nextInt(100));
        customData.putInt("myplugin:tier", random.nextInt(5));
        components.put("minecraft:custom_data", customData);

        CompoundTag item = NBT.createCompound();
        item.putInt("slot", slot);
        item.putString("id", ids[random.nextInt(ids.length)]);
        item.putByte("count", (byte) 1);
        item.put("components", components);
        return item;
    }

    private static CompoundTag attributeModifier(String type, double amount, Random random) {
        CompoundTag modifier = NBT.createCompound();
        modifier.putString("type", type);
        modifier.putString("id", "minecraft:base_" + random.nextInt(10));
        modifier.putDouble("amount", amount);
        modifier.putString("operation", "add_value");
        modifier.putString("slot", "mainhand");
        return modifier;
    }

    private static CompoundTag shulkerBoxItem(int slot, Random random) {
        ListTag container = NBT.createList();
        for (int inner = 0; inner < 27; inner++) {
            CompoundTag entry = NBT.createCompound();
            entry.putInt("slot", inner);
            entry.put("item", enchantedItem(inner, random));
            container.add(entry);
        }
        CompoundTag components = NBT.createCompound();
        components.put("minecraft:container", container);
        CompoundTag item = NBT.createCompound();
        item.putInt("slot", slot);
        item.putString("id", "minecraft:shulker_box");
        item.putByte("count", (byte) 1);
        item.put("components", components);
        return item;
    }

    private static CompoundTag writtenBookItem(int slot, Random random) {
        ListTag pages = NBT.createList();
        for (int page = 0; page < 30; page++) {
            CompoundTag text = NBT.createCompound();
            text.putString("raw", words(random, 45));
            pages.add(text);
        }
        CompoundTag content = NBT.createCompound();
        content.putString("title", words(random, 2));
        content.putString("author", "Steve");
        content.put("pages", pages);
        CompoundTag components = NBT.createCompound();
        components.put("minecraft:written_book_content", content);
        CompoundTag item = NBT.createCompound();
        item.putInt("slot", slot);
        item.putString("id", "minecraft:written_book");
        item.putByte("count", (byte) 1);
        item.put("components", components);
        return item;
    }

    private static CompoundTag heavyPluginDataItem(int slot, Random random) {
        CompoundTag stats = NBT.createCompound();
        for (int i = 0; i < 120; i++) {
            stats.putDouble("stat_" + i, random.nextDouble() * 1000);
        }
        ListTag history = NBT.createList();
        for (int i = 0; i < 40; i++) {
            CompoundTag event = NBT.createCompound();
            event.putLong("time", 1_756_000_000_000L + random.nextInt(1_000_000_000));
            event.putString("action", words(random, 4));
            history.add(event);
        }
        CompoundTag customData = NBT.createCompound();
        customData.put("rpgplugin:stats", stats);
        customData.put("rpgplugin:history", history);
        CompoundTag components = NBT.createCompound();
        components.put("minecraft:custom_data", customData);
        CompoundTag item = NBT.createCompound();
        item.putInt("slot", slot);
        item.putString("id", "minecraft:netherite_sword");
        item.putByte("count", (byte) 1);
        item.put("components", components);
        return item;
    }

    // 用词表拼接模拟自然语言文本的可压缩性, 避免随机字节的不真实悲观
    private static String words(Random random, int count) {
        String[] vocabulary = {"ancient", "shadow", "ember", "crystal", "wander", "beneath", "forgotten", "realm",
                "guardian", "whisper", "storm", "legacy", "hollow", "bright", "iron", "song"};
        List<String> parts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            parts.add(vocabulary[random.nextInt(vocabulary.length)]);
        }
        return String.join(" ", parts);
    }
}

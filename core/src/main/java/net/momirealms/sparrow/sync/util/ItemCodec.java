package net.momirealms.sparrow.sync.util;

import com.mojang.serialization.Dynamic;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.snapshot.codec.ops.MinecraftRegistryOps;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ItemCodec {
    private static final String SLOT_KEY = "slot";
    private static final int MAX_CODEC_COUNT = 99;   // vanilla 物品 CODEC 的 count 值域上限

    private ItemCodec() {
    }

    /**
     * 预热 ITEM_STACK 的 DFU 规则构建, 服务器启动后调用一次.
     * 规则按版本对惰性构建且耗时秒级, 不预热则首个旧版快照会把解码线程卡住.
     */
    public static void warmUp() {
        DataFixers.optimize(Set.of(References.ITEM_STACK));
    }

    /**
     * 把单个物品编码为 sparrow NBT compound.
     * 超出 CODEC 值域的堆叠数被钳制到 99.
     *
     * @throws IllegalStateException 当物品无法被本服的物品 CODEC 编码时
     */
    @NotNull
    public static CompoundTag saveItem(@NotNull ItemStack item) {
        net.minecraft.world.item.ItemStack nms = CraftItemStack.asNMSCopy(item);
        if (nms.getCount() > MAX_CODEC_COUNT) {
            nms.setCount(MAX_CODEC_COUNT);
        }
        Tag tag = net.minecraft.world.item.ItemStack.CODEC.encodeStart(MinecraftRegistryOps.sparrowNbt(), nms)
                .getOrThrow(message -> new IllegalStateException("failed to encode item " + item.getType() + ": " + message));
        if (!(tag instanceof CompoundTag compound)) {
            throw new IllegalStateException("item " + item.getType() + " encoded to non-compound tag");
        }
        return compound;
    }

    /**
     * 把物品数组编码为稀疏物品列表, null 与空气槽位不落盘.
     */
    @NotNull
    public static ListTag saveItems(@Nullable ItemStack @NotNull [] items) {
        ListTag list = NBT.createList();
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null || item.isEmpty()) continue;
            CompoundTag compound = saveItem(item);
            compound.putInt(SLOT_KEY, i);
            list.add(compound);
        }
        return list;
    }

    /**
     * 解析单个物品, 需要时先做跨版本升级.
     *
     * @param dataVersion 数据写入时的 Minecraft data version, 0 表示未知则跳过升级
     * @throws IOException 当数据来自更高版本或无法解析时
     */
    @NotNull
    public static ItemStack loadItem(@NotNull CompoundTag tag, int dataVersion) throws IOException {
        int current = VersionHelper.WORLD_VERSION;
        if (dataVersion > current) {
            throw new IOException("item data version " + dataVersion + " is newer than this server (" + current + ")");
        }
        Tag itemTag = tag;
        if (dataVersion > 0 && dataVersion < current) {
            itemTag = DataFixers.getDataFixer().update(References.ITEM_STACK, new Dynamic<>(NBTOps.INSTANCE, itemTag), dataVersion, current).getValue();
        }
        net.minecraft.world.item.ItemStack nms = net.minecraft.world.item.ItemStack.CODEC.parse(MinecraftRegistryOps.sparrowNbt(), itemTag)
                .getOrThrow(message -> new IOException("failed to parse item: " + message));
        return CraftItemStack.asBukkitCopy(nms);
    }

    /**
     * 解析稀疏物品列表为定长数组, 越界或冲突的物品重排进空槽.
     *
     * @param size 数据写入时的容器大小
     * @throws IOException 当任一条目结构损坏或来自更高版本时
     */
    @NotNull
    public static LoadedItems loadItems(@NotNull ListTag list, int size, int dataVersion) throws IOException {
        ItemStack[] items = new ItemStack[size];
        List<ItemStack> overflow = new ArrayList<>();
        int listSize = list.size();
        for (int i = 0; i < listSize; i++) {
            CompoundTag itemTag = list.getCompound(i);
            if (itemTag == null) {
                throw new IOException("item entry " + i + " is not a compound");
            }
            int slot = itemTag.getInt(SLOT_KEY, i);
            ItemStack item = loadItem(itemTag, dataVersion);
            if (slot >= 0 && slot < size && items[slot] == null) {
                items[slot] = item;
            } else {
                overflow.add(item);
            }
        }
        return place(items, overflow);
    }

    /**
     * 把物品数组适配到目标大小, 超出目标的物品重排进空槽. 跨版本容器大小不同时在应用侧调用.
     */
    @NotNull
    public static LoadedItems fit(@Nullable ItemStack @NotNull [] items, int targetSize) {
        if (items.length == targetSize) return new LoadedItems(items, 0);
        ItemStack[] fitted = new ItemStack[targetSize];
        List<ItemStack> overflow = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null) continue;
            if (i < targetSize) {
                fitted[i] = item;
            } else {
                overflow.add(item);
            }
        }
        return place(fitted, overflow);
    }

    // 溢出重排: 依序塞进空槽, 版本降级或容量缩小时不丢物品, 仍放不下的计数上报
    private static LoadedItems place(@Nullable ItemStack[] items, List<ItemStack> overflow) {
        int dropped = 0;
        int cursor = 0;
        int overflowSize = overflow.size();
        for (int i = 0; i < overflowSize; i++) {
            while (cursor < items.length && items[cursor] != null) {
                cursor++;
            }
            if (cursor >= items.length) {
                dropped = overflowSize - i;
                break;
            }
            items[cursor] = overflow.get(i);
        }
        return new LoadedItems(items, dropped);
    }

    /**
     * 物品列表的解析结果.
     *
     * @param items   按槽位排列的物品, 空槽为 null
     * @param dropped 重排后仍放不下而被丢弃的物品数, 大于 0 时调用方应告警
     */
    public record LoadedItems(@Nullable ItemStack @NotNull [] items, int dropped) {
    }
}

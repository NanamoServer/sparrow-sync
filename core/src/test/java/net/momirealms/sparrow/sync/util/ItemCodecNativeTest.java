package net.momirealms.sparrow.sync.util;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.map.MapPipeline;
import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.map.handler.HideMapHandler;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.snapshot.codec.ops.MinecraftRegistryOps;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType.NativeApplyResult;
import net.momirealms.sparrow.sync.snapshot.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.test.ConnectionFixture;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ItemCodecNativeTest {
    private static HolderLookup.Provider registries;

    private Object previousSparrowOps;
    private Object previousNativeOps;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
        BukkitProxy.init("1.21.8", List.of("paper"));
    }

    @BeforeEach
    void bindRegistries() throws Exception {
        // 数据生成注册表使用独立 Holder owner, 必须由 provider 提供匹配的序列化上下文.
        this.previousSparrowOps = replaceOps("sparrowNbt", registries.createSerializationContext(NBTOps.INSTANCE));
        this.previousNativeOps = replaceOps("nativeNbt", null);
    }

    @AfterEach
    void restoreRegistries() throws Exception {
        replaceOps("nativeNbt", this.previousNativeOps);
        replaceOps("sparrowNbt", this.previousSparrowOps);
    }

    @Test
    void nativeOpsAreCachedAndKeepTheSparrowRegistryContext() {
        RegistryOps<net.momirealms.sparrow.nbt.Tag> sparrow = MinecraftRegistryOps.sparrowNbt();
        RegistryOps<net.minecraft.nbt.Tag> nativeOps = MinecraftRegistryOps.nativeNbt();

        assertSame(nativeOps, MinecraftRegistryOps.nativeNbt());
        assertSame(sparrow, MinecraftRegistryOps.sparrowNbt());
        assertEquals(sparrow.withParent(NbtOps.INSTANCE), nativeOps);
        assertEquals(legacyNative(this.complexItem()), ItemCodec.saveNativeItem(this.complexItem()));
    }

    @Test
    void nativeEncodingMatchesLegacyOutputForComponentsAndNestedItems() {
        ItemStack sword = this.complexItem();
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(sword, ItemStack.EMPTY, new ItemStack(Items.DIAMOND, 64))));

        for (ItemStack item : new ItemStack[]{new ItemStack(Items.STONE, 32), sword, box}) {
            CompoundTag nativeTag = ItemCodec.saveNativeItem(item);
            assertEquals(legacyNative(item), nativeTag);
            ItemStack decoded = ItemStack.CODEC.parse(MinecraftRegistryOps.nativeNbt(), nativeTag).getOrThrow();
            assertTrue(ItemStack.matches(item, decoded));
        }
    }

    @Test
    void excessiveCountIsClampedWithoutChangingTheBorrowedItem() {
        ItemStack item = this.complexItem();
        item.setCount(128);
        ItemStack before = item.copy();

        CompoundTag nativeTag = ItemCodec.saveNativeItem(item);

        assertEquals(99, nativeTag.getInt("count").orElseThrow());
        assertEquals(legacyNative(item), nativeTag);
        assertTrue(ItemStack.matches(before, item));
        assertEquals(128, item.getCount());
    }

    @Test
    void invalidItemStillFailsWithTheSameExceptionContract() {
        IllegalStateException legacy = assertThrows(IllegalStateException.class, () -> legacyNative(ItemStack.EMPTY));
        IllegalStateException nativeFailure = assertThrows(IllegalStateException.class, () -> ItemCodec.saveNativeItem(ItemStack.EMPTY));

        assertEquals(legacy.getMessage(), nativeFailure.getMessage());
    }

    @Test
    void nativeResultsDoNotShareMutableTagsWithSourceOrOtherEncodings() {
        ItemStack item = this.complexItem();
        CompoundTag first = ItemCodec.saveNativeItem(item);
        CompoundTag second = ItemCodec.saveNativeItem(item);
        CompoundTag expected = second.copy();

        first.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:custom_data")
                .getCompoundOrEmpty("nested").putString("marker", "changed-output");
        first.putInt("count", 17);
        assertNotEquals(expected, first);
        assertEquals(expected, second);
        assertEquals(expected, ItemCodec.saveNativeItem(item));

        // 直接改源组件的底层 Tag, 验证编码结果没有借用它; 不依赖替换组件后的 COW.
        item.get(DataComponents.CUSTOM_DATA).getUnsafe().getCompoundOrEmpty("nested").putString("marker", "changed-source");
        item.setCount(5);
        assertEquals(expected, second);
    }

    @Test
    void nativeListKeepsSparseSlotsAndByteSlotTags() {
        ItemStack[] items = {null, this.complexItem(), ItemStack.EMPTY, new ItemStack(Items.DIAMOND, 12)};
        ListTag expected = new ListTag();
        CompoundTag sword = legacyNative(items[1]);
        sword.putByte("Slot", (byte) 1);
        expected.add(sword);
        CompoundTag diamond = legacyNative(items[3]);
        diamond.putByte("Slot", (byte) 3);
        expected.add(diamond);

        assertEquals(expected, ItemCodec.saveNativeItems(items));
    }

    @Test
    void inventoryAndEnderChestNativeWritesKeepTheExistingLayout() {
        PlayerSession session = new SessionManager(null).tryOpen(new UUID(0L, 0L), "Steve", ConnectionFixture.create());
        InventoryDataType inventoryType = NmsPlayerFixture.allocate(InventoryDataType.class);
        EnderChestDataType enderChestType = NmsPlayerFixture.allocate(EnderChestDataType.class);
        ItemStack[] inventory = new ItemStack[43];
        inventory[0] = this.complexItem();
        inventory[39] = new ItemStack(Items.DIAMOND_HELMET);
        inventory[40] = new ItemStack(Items.SHIELD);
        ItemStack[] enderChest = new ItemStack[27];
        enderChest[26] = this.complexItem();
        CompoundTag playerData = new CompoundTag();

        assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, inventoryType.applyNative(session, playerData, new InventoryDataType.Inventory(inventory, 7, 0)));
        assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, enderChestType.applyNative(session, playerData, new ItemCodec.LoadedItems(enderChest, 0)));

        ListTag expectedInventory = new ListTag();
        CompoundTag sword = legacyNative(inventory[0]);
        sword.putByte("Slot", (byte) 0);
        expectedInventory.add(sword);
        assertEquals(expectedInventory, playerData.get("Inventory"));
        CompoundTag expectedEquipment = new CompoundTag();
        expectedEquipment.put("head", legacyNative(inventory[39]));
        expectedEquipment.put("offhand", legacyNative(inventory[40]));
        assertEquals(expectedEquipment, playerData.get("equipment"));
        ListTag expectedEnderChest = new ListTag();
        CompoundTag enderItem = legacyNative(enderChest[26]);
        enderItem.putByte("Slot", (byte) 26);
        expectedEnderChest.add(enderItem);
        assertEquals(expectedEnderChest, playerData.get("EnderItems"));
        assertEquals(7, playerData.getInt("SelectedItemSlot").orElseThrow());
    }

    @Test
    void hiddenMapsSurviveItemCodecsNativeWritesAndReturnToOrigin() throws Exception {
        InventoryDataType inventoryType = NmsPlayerFixture.allocate(InventoryDataType.class);
        EnderChestDataType enderChestType = NmsPlayerFixture.allocate(EnderChestDataType.class);
        DataRegistry registry = new DataRegistry();
        registry.register(inventoryType);
        registry.register(enderChestType);
        registry.freeze();
        ItemStack map = new ItemStack(Items.FILLED_MAP);
        map.set(DataComponents.MAP_ID, new MapId(0));
        map.set(DataComponents.CUSTOM_NAME, Component.literal("Map from A"));
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(map.copy())));
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(bundle)));
        ItemStack[] inventory = new ItemStack[43];
        inventory[0] = map;
        inventory[40] = box;
        ItemStack[] enderChest = new ItemStack[27];
        enderChest[26] = box.copy();
        SnapshotMeta meta = new SnapshotMeta(UUID.randomUUID(), UUID.randomUUID(), 1L, SaveCause.DISCONNECT, false, "A", VersionHelper.WORLD_VERSION);
        Snapshot original = new Snapshot(meta, Map.of(
                InventoryDataType.INVENTORY, inventoryType.encode(new InventoryDataType.Inventory(inventory, 0, 0)),
                EnderChestDataType.ENDER_CHEST, enderChestType.encode(new ItemCodec.LoadedItems(enderChest, 0))));

        PluginLogger console = (PluginLogger) Proxy.newProxyInstance(PluginLogger.class.getClassLoader(), new Class<?>[]{PluginLogger.class}, (proxy, method, args) -> {
            throw new AssertionError("unexpected log: " + args[0]);
        });
        MapPipeline pipeline = new MapPipeline(registry, List.of(new HideMapHandler()), new SyncLogger(console));
        Snapshot compiled = pipeline.compile(original, MapType.HIDE, "A-world");
        Snapshot hidden = pipeline.decode(compiled, "B-world");
        InventoryDataType.Inventory decodedInventory = inventoryType.decode(hidden.data(InventoryDataType.INVENTORY), meta.mcDataVersion());
        ItemCodec.LoadedItems decodedEnder = enderChestType.decode(hidden.data(EnderChestDataType.ENDER_CHEST), meta.mcDataVersion());
        assertNull(decodedInventory.contents()[0].get(DataComponents.MAP_ID));
        assertEquals(map.get(DataComponents.CUSTOM_NAME), decodedInventory.contents()[0].get(DataComponents.CUSTOM_NAME));
        assertEquals(hidden.data(InventoryDataType.INVENTORY), inventoryType.encode(decodedInventory));
        assertEquals(hidden.data(EnderChestDataType.ENDER_CHEST), enderChestType.encode(decodedEnder));

        PlayerSession session = new SessionManager(null).tryOpen(meta.player(), "Steve", ConnectionFixture.create());
        CompoundTag playerData = new CompoundTag();
        assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, inventoryType.applyNative(session, playerData, decodedInventory));
        assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, enderChestType.applyNative(session, playerData, decodedEnder));
        CompoundTag nativeMap = playerData.getListOrEmpty("Inventory").getCompoundOrEmpty(0);
        ItemStack loadedMap = ItemStack.CODEC.parse(MinecraftRegistryOps.nativeNbt(), nativeMap).getOrThrow();
        assertNull(loadedMap.get(DataComponents.MAP_ID));
        assertEquals("HIDE", loadedMap.get(DataComponents.CUSTOM_DATA).copyTag().getCompoundOrEmpty("sparrow-sync").getString("map-type").orElseThrow());

        SnapshotMeta savedMeta = new SnapshotMeta(UUID.randomUUID(), meta.player(), 2L, SaveCause.DISCONNECT, false, "B", meta.mcDataVersion());
        Snapshot saved = new Snapshot(savedMeta, Map.of(
                InventoryDataType.INVENTORY, inventoryType.encode(decodedInventory),
                EnderChestDataType.ENDER_CHEST, enderChestType.encode(decodedEnder)));
        Snapshot forwarded = pipeline.compile(saved, MapType.HIDE, "B-world");
        Snapshot restored = pipeline.decode(forwarded, "A-world");
        assertEquals(original.data(), restored.data());
        InventoryDataType.Inventory decodedRestored = inventoryType.decode(restored.data(InventoryDataType.INVENTORY), meta.mcDataVersion());
        assertTrue(ItemStack.matches(map, decodedRestored.contents()[0]));
        assertTrue(ItemStack.matches(box, decodedRestored.contents()[40]));
    }

    private ItemStack complexItem() {
        ItemStack item = new ItemStack(Items.DIAMOND_SWORD);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("Native 编码测试"));
        item.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("复杂组件"), Component.literal("第二行"))));
        item.remove(DataComponents.ATTRIBUTE_MODIFIERS);
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 5);
        enchantments.set(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING), 3);
        item.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        CompoundTag custom = new CompoundTag();
        CompoundTag nested = new CompoundTag();
        nested.putString("marker", "original");
        nested.putByteArray("bytes", new byte[]{1, 2, -1});
        nested.putIntArray("ints", new int[]{0, 42, Integer.MAX_VALUE});
        nested.putLongArray("longs", new long[]{1L, Long.MAX_VALUE});
        custom.put("nested", nested);
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        return item;
    }

    private static CompoundTag legacyNative(ItemStack item) {
        return (CompoundTag) NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, ItemCodec.saveItem(item));
    }

    private static Object replaceOps(String name, Object value) throws Exception {
        Field field = MinecraftRegistryOps.class.getDeclaredField(name);
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, value);
        return previous;
    }
}

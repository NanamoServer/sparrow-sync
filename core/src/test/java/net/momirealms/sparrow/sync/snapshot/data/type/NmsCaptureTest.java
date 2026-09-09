package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.snapshot.codec.ops.MinecraftRegistryOps;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataPipeline;
import net.momirealms.sparrow.sync.snapshot.data.SnapshotDecoder;
import net.momirealms.sparrow.sync.snapshot.data.PlayerDataType;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import net.momirealms.sparrow.sync.util.ItemCodec;
import org.bukkit.GameMode;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftInventoryPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NmsCaptureTest {
    private CraftPlayer player;
    private Object previousOps;
    private Object previousConfig;

    @BeforeEach
    void setUp() throws Exception {
        this.player = NmsPlayerFixture.create();
        this.previousConfig = replace(PluginConfig.class, "config", new PluginConfig.ConfigDefinition());
        this.previousOps = replace(MinecraftRegistryOps.class, "sparrowNbt",
                RegistryOps.create(NBTOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
    }

    @AfterEach
    void restore() throws Exception {
        replace(MinecraftRegistryOps.class, "sparrowNbt", this.previousOps);
        replace(PluginConfig.class, "config", this.previousConfig);
    }

    @Test
    void nativeInventoryApplyMatchesCraftSlotsPacketsAndDetachedValues() {
        CraftPlayer reference = NmsPlayerFixture.create();
        Inventory actualInventory = inventory(this.player);
        Inventory referenceInventory = inventory(reference);
        RecordingConnection actualPackets = connect(this.player);
        RecordingConnection expectedPackets = connect(reference);
        ItemStack[] items = new ItemStack[43];
        org.bukkit.inventory.ItemStack[] bukkitItems = new org.bukkit.inventory.ItemStack[43];
        for (int slot = 0; slot < items.length; slot++) {
            if (slot % 5 != 0) {
                items[slot] = new ItemStack(Items.DIAMOND, slot + 1);
                bukkitItems[slot] = CraftItemStack.asCraftMirror(items[slot]);
            }
        }
        InventoryDataType type = NmsPlayerFixture.allocate(InventoryDataType.class);
        type.apply(this.player, new InventoryDataType.Inventory(items, 6, 0));
        CraftInventoryPlayer craft = new CraftInventoryPlayer(referenceInventory);
        craft.setContents(bukkitItems);
        craft.setHeldItemSlot(6);

        assertEquals(expectedPackets.packets.size(), actualPackets.packets.size());
        for (int slot = 0; slot < items.length; slot++) {
            assertTrue(ItemStack.matches(referenceInventory.getItem(slot), actualInventory.getItem(slot)));
            if (items[slot] != null) assertNotSame(items[slot], actualInventory.getItem(slot));
            Packet<?> actual = actualPackets.packets.get(slot);
            Packet<?> expected = expectedPackets.packets.get(slot);
            assertEquals(expected.getClass(), actual.getClass());
            if (actual instanceof ClientboundContainerSetSlotPacket packet) {
                ClientboundContainerSetSlotPacket old = (ClientboundContainerSetSlotPacket) expected;
                assertEquals(old.getSlot(), packet.getSlot());
                assertEquals(old.getStateId(), packet.getStateId());
                assertTrue(ItemStack.matches(old.getItem(), packet.getItem()));
            } else {
                ClientboundSetPlayerInventoryPacket packet = (ClientboundSetPlayerInventoryPacket) actual;
                ClientboundSetPlayerInventoryPacket old = (ClientboundSetPlayerInventoryPacket) expected;
                assertEquals(old.slot(), packet.slot());
                assertTrue(ItemStack.matches(old.contents(), packet.contents()));
                assertNotSame(actualInventory.getItem(slot), packet.contents());
            }
        }
        assertEquals(new ClientboundSetHeldSlotPacket(6), actualPackets.packets.getLast());
        assertEquals(6, actualInventory.getSelectedSlot());
    }

    @Test
    void containerShrinkRearrangesItemsAndOnlyReportsDroppedCount() throws Exception {
        ItemStack[] items = {new ItemStack(Items.STONE, 64), null, new ItemStack(Items.DIAMOND, 12), new ItemStack(Items.GOLD_INGOT, 7)};
        ItemCodec.LoadedItems fitted = ItemCodec.fit(items, 2);
        assertSame(items[0], fitted.items()[0]);
        assertSame(items[2], fitted.items()[1]);
        assertEquals(1, fitted.dropped());

        ItemCodec.LoadedItems decoded = ItemCodec.loadItems(ItemCodec.saveItems(items), 2, 0);
        assertTrue(ItemStack.matches(items[0], decoded.items()[0]));
        assertTrue(ItemStack.matches(items[2], decoded.items()[1]));
        assertEquals(1, decoded.dropped());
    }

    @Test
    void inventoryApplyUsesSlotZeroForInvalidSelection() {
        Inventory inventory = inventory(this.player);
        RecordingConnection connection = connect(this.player);
        InventoryDataType type = NmsPlayerFixture.allocate(InventoryDataType.class);
        for (int heldSlot : new int[]{Integer.MIN_VALUE, -1, 0, 1, 8, 9, Integer.MAX_VALUE}) {
            int expected = heldSlot >= 0 && heldSlot <= 8 ? heldSlot : 0;
            type.apply(this.player, new InventoryDataType.Inventory(new ItemStack[43], heldSlot, 0));
            assertEquals(expected, inventory.getSelectedSlot());
            assertEquals(new ClientboundSetHeldSlotPacket(expected), connection.packets.getLast());
            connection.packets.clear();
        }
    }

    @Test
    void gameModeCaptureUsesBukkitInEveryMode() {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
            if (method.getName().equals("getGameMode")) return GameMode.CREATIVE;
            throw new AssertionError(method.getName());
        });
        GameModeDataType type = new GameModeDataType();
        for (CaptureMode mode : CaptureMode.values()) {
            assertSame(GameMode.CREATIVE, type.capture(player, mode));
        }
    }

    @Test
    void asyncCaptureIncludesLockedStatisticsAndSimpleScalarState() {
        assertTrue(new StatisticsDataType().supportsAsyncCapture());
        assertTrue(new ExperienceDataType().supportsAsyncCapture());
        assertTrue(new HungerDataType().supportsAsyncCapture());
        assertTrue(new EnchantmentSeedDataType().supportsAsyncCapture());
        assertTrue(new GameModeDataType().supportsAsyncCapture());
        assertTrue(new HealthDataType().supportsAsyncCapture());
        assertTrue(new HealthScaleDataType().supportsAsyncCapture());
        for (PlayerDataType<?> type : List.of(
                new AdvancementsDataType(), new AttributesDataType(), new PDCDataType(), new PotionEffectsDataType(),
                new FlightStatusDataType(), new LocationDataType(), NmsPlayerFixture.allocate(InventoryDataType.class),
                NmsPlayerFixture.allocate(EnderChestDataType.class))) {
            assertFalse(type.supportsAsyncCapture(), type.key().asString());
        }
    }

    @Test
    void worldSaveWorkerEncodesDetachedContainersAndOnlyReadsAsyncScalars() throws Exception {
        Inventory inventory = inventory(this.player);
        ItemStack item = new ItemStack(Items.DIAMOND_SWORD, 1);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("captured"));
        inventory.setItem(0, item);
        PlayerEnderChestContainer chest = new PlayerEnderChestContainer(this.player.getHandle());
        chest.setItem(0, item);
        NmsPlayerFixture.set(net.minecraft.world.entity.player.Player.class, this.player.getHandle(), "enderChestInventory", chest);
        net.minecraft.nbt.CompoundTag pdc = new net.minecraft.nbt.CompoundTag();
        pdc.putInt("value", 1);
        this.player.getPersistentDataContainer().getRaw().put("example:data", pdc);
        this.player.getHandle().activeEffects.put(MobEffects.SPEED, new MobEffectInstance(MobEffects.SPEED, 80, 1));
        this.player.getHandle().getAttributes().getInstance(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(32.0);

        DataRegistry registry = new DataRegistry();
        registry.register(NmsPlayerFixture.allocate(InventoryDataType.class));
        registry.register(NmsPlayerFixture.allocate(EnderChestDataType.class));
        registry.register(new PDCDataType());
        registry.register(new PotionEffectsDataType());
        registry.register(new AttributesDataType());
        registry.register(new ExperienceDataType());
        registry.freeze();
        PlayerDataPipeline pipeline = new PlayerDataPipeline(null);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "dataRegistry", registry);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "decoder", new SnapshotDecoder(registry));
        PluginLogger console = (PluginLogger) Proxy.newProxyInstance(PluginLogger.class.getClassLoader(), new Class<?>[]{PluginLogger.class}, (proxy, method, args) -> null);
        NmsPlayerFixture.set(PlayerDataPipeline.class, pipeline, "logger", new SyncLogger(console));
        PlayerDataPipeline.CaptureResult.Pending pending = assertInstanceOf(PlayerDataPipeline.CaptureResult.Pending.class, pipeline.capture(this.player, CaptureMode.ASYNC));

        // 第一阶段返回后继续修改玩家, worker 编码同步组时仍应看到采集时的状态
        item.setCount(4);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
        pdc.putInt("value", 2);
        this.player.getHandle().activeEffects.clear();
        this.player.getHandle().getAttributes().getInstance(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(40.0);
        this.player.getHandle().totalExperience = 4321;

        try (var worker = Executors.newSingleThreadExecutor()) {
            Map<DataKey, Tag> encoded = worker.submit(() -> {
                PlayerDataPipeline.CaptureResult.Ready captured = assertInstanceOf(PlayerDataPipeline.CaptureResult.Ready.class, pipeline.captureAsync(this.player, pending));
                assertTrue(captured.skipped().isEmpty());
                return assertInstanceOf(PlayerDataPipeline.EncodeResult.Ready.class, pipeline.encode(captured)).data();
            }).get(2, TimeUnit.SECONDS);

            InventoryDataType.Inventory savedInventory = NmsPlayerFixture.allocate(InventoryDataType.class).decode(encoded.get(InventoryDataType.INVENTORY), 0);
            ItemCodec.LoadedItems savedChest = NmsPlayerFixture.allocate(EnderChestDataType.class).decode(encoded.get(EnderChestDataType.ENDER_CHEST), 0);
            assertEquals(1, savedInventory.contents()[0].getCount());
            assertEquals("captured", savedInventory.contents()[0].get(DataComponents.CUSTOM_NAME).getString());
            assertEquals(1, savedChest.items()[0].getCount());
            assertEquals("captured", savedChest.items()[0].get(DataComponents.CUSTOM_NAME).getString());
            assertEquals(1, ((CompoundTag) encoded.get(PDCDataType.PERSISTENT_DATA)).getCompound("example:data").getInt("value"));
            assertEquals(80, new PotionEffectsDataType().decode(encoded.get(PotionEffectsDataType.POTION_EFFECTS), 0).getFirst().getDuration());
            AttributesDataType.Attributes attributes = new AttributesDataType().decode(encoded.get(AttributesDataType.ATTRIBUTES), 0);
            assertEquals(32.0, Arrays.stream(attributes.values()).filter(value -> value.key().getKey().equals("max_health")).findFirst().orElseThrow().base());
            assertEquals(4321, new ExperienceDataType().decode(encoded.get(ExperienceDataType.EXPERIENCE), 0).total());
        }
    }

    @Test
    void enderChestUsesActualContainerAndOnlyOfflineBorrowsStacks() {
        PlayerEnderChestContainer chest = new PlayerEnderChestContainer(this.player.getHandle());
        ItemStack item = new ItemStack(Items.DIAMOND, 12);
        chest.setItem(26, item);
        NmsPlayerFixture.set(net.minecraft.world.entity.player.Player.class, this.player.getHandle(), "enderChestInventory", chest);
        EnderChestDataType type = NmsPlayerFixture.allocate(EnderChestDataType.class);
        assertTrue(type.dependencies().isEmpty());
        ItemCodec.LoadedItems sync = type.capture(this.player, CaptureMode.SYNC);
        ItemCodec.LoadedItems offline = type.capture(this.player, CaptureMode.OFFLINE);
        assertEquals(chest.getContainerSize(), sync.items().length);
        assertNotSame(item, sync.items()[26]);
        assertSame(item, offline.items()[26]);
        item.setCount(2);
        assertEquals(12, sync.items()[26].getCount());
        assertEquals(2, offline.items()[26].getCount());
    }

    @Test
    void onlineItemsDetachCountAndComponentsAndDecodeWithoutBukkit() throws Exception {
        ItemStack live = new ItemStack(Items.DIAMOND_SWORD, 1);
        live.set(DataComponents.CUSTOM_NAME, Component.literal("captured"));
        net.minecraft.nbt.CompoundTag custom = new net.minecraft.nbt.CompoundTag();
        custom.putByteArray("bytes", new byte[]{1, 2, 3});
        live.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        ItemStack[] captured = ItemCodec.captureItems(new SimpleContainer(live, ItemStack.EMPTY), CaptureMode.SYNC);
        assertNotSame(live, captured[0]);
        assertNull(captured[1]);

        live.setCount(4);
        live.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
        live.remove(DataComponents.CUSTOM_DATA);
        ItemStack decoded = ItemCodec.loadItems(ItemCodec.saveItems(captured), 2, 0).items()[0];

        assertEquals(1, decoded.getCount());
        assertEquals("captured", decoded.get(DataComponents.CUSTOM_NAME).getString());
        assertArrayEquals(new byte[]{1, 2, 3}, ((net.minecraft.nbt.ByteArrayTag) decoded.get(DataComponents.CUSTOM_DATA).copyTag().get("bytes")).getAsByteArray());
    }

    @Test
    void offlineItemsBorrowButClampingAndEncodedTagCannotMutateThePlayer() throws Exception {
        ItemStack live = new ItemStack(Items.STONE, 128);
        ItemStack[] captured = ItemCodec.captureItems(new SimpleContainer(live), CaptureMode.OFFLINE);
        assertSame(live, captured[0]);
        CompoundTag encoded = ItemCodec.saveItem(captured[0]);
        assertEquals(128, live.getCount());
        live.setCount(2);
        assertEquals(99, ItemCodec.loadItem(encoded, 0).getCount());
    }

    @Test
    void inventoryReadsEveryEquipmentSlotAndSelectedSlotWithoutMergedContents() {
        EntityEquipment equipment = new EntityEquipment();
        Inventory inventory = new Inventory(this.player.getHandle(), equipment);
        NmsPlayerFixture.set(net.minecraft.world.entity.player.Player.class, this.player.getHandle(), "inventory", inventory);
        inventory.setSelectedSlot(7);
        inventory.setItem(0, new ItemStack(Items.STONE, 32));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot != EquipmentSlot.MAINHAND) {
                equipment.set(slot, new ItemStack(Items.DIAMOND, slot.ordinal() + 1));
            }
        }
        InventoryDataType type = NmsPlayerFixture.allocate(InventoryDataType.class);
        InventoryDataType.Inventory captured = type.capture(this.player, CaptureMode.SYNC);
        assertEquals(inventory.getContainerSize(), captured.contents().length);
        assertEquals(43, captured.contents().length);
        assertEquals(7, captured.heldSlot());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack original = inventory.getItem(slot);
            if (original.isEmpty()) {
                assertNull(captured.contents()[slot]);
            } else {
                assertTrue(ItemStack.matches(original, captured.contents()[slot]));
                assertNotSame(original, captured.contents()[slot]);
            }
        }
        ItemCodec.LoadedItems fitted = ItemCodec.fit(captured.contents(), 41);
        assertEquals(0, fitted.dropped());
        assertEquals(41, fitted.items().length);
    }

    @Test
    void pdcSyncCopiesAndOfflineBorrowsChildrenButEncodingDetachesArrays() {
        net.minecraft.nbt.CompoundTag child = new net.minecraft.nbt.CompoundTag();
        byte[] bytes = {1};
        int[] ints = {2};
        long[] longs = {3};
        child.putByteArray("bytes", bytes);
        child.putIntArray("ints", ints);
        child.putLongArray("longs", longs);
        this.player.getPersistentDataContainer().getRaw().put("example:child", child);
        PDCDataType type = new PDCDataType();
        net.minecraft.nbt.CompoundTag sync = type.capture(this.player, CaptureMode.SYNC);
        net.minecraft.nbt.CompoundTag offline = type.capture(this.player, CaptureMode.OFFLINE);
        assertNotSame(child, sync.get("example:child"));
        assertSame(child, offline.get("example:child"));
        CompoundTag encoded = (CompoundTag) type.encode(offline);
        bytes[0] = 8;
        ints[0] = 9;
        longs[0] = 10;
        child.putString("late", "not captured");
        CompoundTag saved = encoded.getCompound("example:child");
        assertArrayEquals(new byte[]{1}, saved.getByteArray("bytes"));
        assertArrayEquals(new int[]{2}, saved.getIntArray("ints"));
        assertArrayEquals(new long[]{3}, saved.getLongArray("longs"));
        assertFalse(saved.containsKey("late"));
        assertEquals(encoded, type.encode(sync));
    }

    @Test
    void potionCopyIncludesHiddenChainAndOfflineEncodeDetachesBorrowedInstances() throws Exception {
        MobEffectInstance hidden = new MobEffectInstance(MobEffects.SPEED, 400, 0);
        MobEffectInstance effect = new MobEffectInstance(MobEffects.SPEED, 80, 1, false, true, true, hidden);
        this.player.getHandle().activeEffects.put(effect.getEffect(), effect);
        this.player.getHandle().activeEffects.put(MobEffects.LUCK, new MobEffectInstance(MobEffects.LUCK, 100, 0, true, true));
        PotionEffectsDataType type = new PotionEffectsDataType();
        List<MobEffectInstance> sync = type.capture(this.player, CaptureMode.SYNC);
        List<MobEffectInstance> offline = type.capture(this.player, CaptureMode.OFFLINE);
        assertEquals(1, sync.size());
        assertNotSame(effect, sync.getFirst());
        assertNotSame(hidden, sync.getFirst().hiddenEffect);
        assertSame(effect, offline.getFirst());
        Tag encoded = type.encode(offline);
        effect.update(new MobEffectInstance(MobEffects.SPEED, 1200, 3));
        hidden.update(new MobEffectInstance(MobEffects.SPEED, 1600, 2));
        MobEffectInstance restored = type.decode(encoded, 0).getFirst();
        assertEquals(80, restored.getDuration());
        assertEquals(400, restored.hiddenEffect.getDuration());
        assertEquals(80, sync.getFirst().getDuration());
        assertEquals(400, sync.getFirst().hiddenEffect.getDuration());
    }

    @Test
    void scalarsReadNmsButHealthAndScalingReadCraftState() {
        this.player.getHandle().totalExperience = 4321;
        this.player.getHandle().experienceLevel = 23;
        this.player.getHandle().experienceProgress = 0.25f;
        assertEquals(new ExperienceDataType.Experience(4321, 23, 0.25f), new ExperienceDataType().capture(this.player, CaptureMode.ASYNC));
        this.player.getHandle().getFoodData().setFoodLevel(13);
        this.player.getHandle().getFoodData().setSaturation(3.5f);
        this.player.getHandle().getFoodData().exhaustionLevel = 1.25f;
        assertEquals(new HungerDataType.Hunger(13, 3.5f, 1.25f, 0), new HungerDataType().capture(this.player, CaptureMode.ASYNC));
        NmsPlayerFixture.set(CraftPlayer.class, this.player, "health", 37.5);
        NmsPlayerFixture.set(CraftPlayer.class, this.player, "healthScale", 40.0);
        NmsPlayerFixture.set(CraftPlayer.class, this.player, "scaledHealth", true);
        assertEquals(37.5, new HealthDataType().capture(this.player, CaptureMode.SYNC).health());
        assertEquals(new HealthScaleDataType.HealthScale(40.0, true), new HealthScaleDataType().capture(this.player, CaptureMode.SYNC));
    }

    private static Inventory inventory(CraftPlayer player) {
        Inventory inventory = new Inventory(player.getHandle(), new EntityEquipment());
        NmsPlayerFixture.set(net.minecraft.world.entity.player.Player.class, player.getHandle(), "inventory", inventory);
        NmsPlayerFixture.set(net.minecraft.world.entity.player.Player.class, player.getHandle(), "inventoryMenu", NmsPlayerFixture.allocate(InventoryMenu.class));
        return inventory;
    }

    private static RecordingConnection connect(CraftPlayer player) {
        RecordingConnection connection = NmsPlayerFixture.allocate(RecordingConnection.class);
        connection.packets = new ArrayList<>();
        player.getHandle().connection = connection;
        return connection;
    }

    private static final class RecordingConnection extends ServerGamePacketListenerImpl {
        private List<Packet<?>> packets;

        private RecordingConnection() {
            super(null, null, null, null);
        }

        @Override
        public void send(Packet<?> packet) {
            this.packets.add(packet);
        }
    }

    private static Object replace(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, value);
        return previous;
    }
}

package net.momirealms.sparrow.sync.map;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.MapPostProcessing;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Banner;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.Event;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MapInteractionListenerTest {
    private Player player;
    private MapInteractionListener listener;
    private Object previousConfig;
    private PluginConfig.MapOptions options;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BukkitProxy.init("1.21.8", List.of("paper"));
    }

    @BeforeEach
    void prepare() throws Exception {
        this.player = NmsPlayerFixture.create();
        Field config = PluginConfig.class.getDeclaredField("config");
        config.setAccessible(true);
        this.previousConfig = config.get(null);
        config.set(null, new PluginConfig.ConfigDefinition());
        this.options = PluginConfig.synchronization$map();
        this.listener = new MapInteractionListener();
    }

    @AfterEach
    void restore() {
        NmsPlayerFixture.set(PluginConfig.class, null, "config", this.previousConfig);
    }

    @ParameterizedTest
    @CsvSource({"LOCK,false", "LOCK,true", "SCALE,false", "SCALE,true", "COPY,false", "COPY,true"})
    void recipeOperationsRespectIndependentOptionsAndKeepItemsUnchanged(String operation, boolean allowed) {
        this.option(operation, allowed);
        ItemStack result = this.map(-1, operation);
        for (InventoryType type : List.of(InventoryType.CARTOGRAPHY, InventoryType.WORKBENCH, InventoryType.CRAFTING)) {
            InventoryView view = this.view(type, result);
            int slot = type == InventoryType.CARTOGRAPHY ? 2 : 0;
            for (ClickType click : List.of(ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.DROP)) {
                InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.RESULT, slot, click, InventoryAction.PICKUP_ALL, 1);
                this.listener.onClick(event);
                assertEquals(!allowed, event.isCancelled(), type + " " + click);
            }
        }
        Block block = this.block(Material.CRAFTER);
        CrafterCraftEvent event = new CrafterCraftEvent(block, new ShapelessRecipe(new NamespacedKey("test", "map"), result), result);
        this.listener.onCrafter(event);
        assertEquals(!allowed, event.isCancelled());
        assertEquals(-1, ((CraftItemStack) result).handle.get(DataComponents.MAP_ID).id());
        assertEquals(operation.equals("COPY") ? null : MapPostProcessing.valueOf(operation), ((CraftItemStack) result).handle.get(DataComponents.MAP_POST_PROCESSING));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, Integer.MAX_VALUE})
    void nonNegativeMapsRemainUsable(int id) {
        this.option("COPY", false);
        for (String operation : List.of("LOCK", "SCALE", "COPY")) {
            InventoryClickEvent event = new InventoryClickEvent(this.view(InventoryType.CARTOGRAPHY, this.map(id, operation)), InventoryType.SlotType.RESULT, 2, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            this.listener.onClick(event);
            assertFalse(event.isCancelled());
        }
    }

    @ParameterizedTest
    @CsvSource({"HAND,WHITE_BANNER,false,-1", "OFF_HAND,WHITE_WALL_BANNER,false,-1", "HAND,WHITE_BANNER,false,-2147483648", "HAND,WHITE_BANNER,true,-1", "HAND,STONE,false,-1", "HAND,WHITE_BANNER,false,0"})
    void bannerRestrictionUsesTheInteractingHandWithoutCancellingTheBlock(EquipmentSlot hand, Material material, boolean allowed, int id) {
        NmsPlayerFixture.set(PluginConfig.MapOptions.class, this.options, "allowBannerModification", allowed);
        Block block = this.block(material);
        PlayerInteractEvent event = new PlayerInteractEvent(this.player, Action.RIGHT_CLICK_BLOCK, this.map(id, "COPY"), block, BlockFace.UP, hand);
        this.listener.onInteract(event);
        assertEquals(!allowed && material != Material.STONE && id < 0 ? Event.Result.DENY : Event.Result.DEFAULT, event.useItemInHand());
        assertEquals(Event.Result.ALLOW, event.useInteractedBlock());
    }

    @Test
    void creativeCloneOtherSlotsAndUnrelatedItemsAreNotRestricted() {
        this.option("COPY", false);
        ItemStack map = this.map(-1, "COPY");
        InventoryView view = this.view(InventoryType.CARTOGRAPHY, map);
        InventoryClickEvent clone = new InventoryClickEvent(view, InventoryType.SlotType.RESULT, 2, ClickType.MIDDLE, InventoryAction.CLONE_STACK);
        this.listener.onClick(clone);
        assertFalse(clone.isCancelled());
        InventoryCreativeEvent creative = new InventoryCreativeEvent(view, InventoryType.SlotType.RESULT, 2, map);
        this.listener.onClick(creative);
        assertFalse(creative.isCancelled());
        InventoryClickEvent input = new InventoryClickEvent(view, InventoryType.SlotType.CRAFTING, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        this.listener.onClick(input);
        assertFalse(input.isCancelled());
        ItemStack idless = CraftItemStack.asCraftMirror(new net.minecraft.world.item.ItemStack(Items.FILLED_MAP));
        for (ItemStack item : List.of(new ItemStack(Material.STONE), idless)) {
            InventoryClickEvent event = new InventoryClickEvent(this.view(InventoryType.WORKBENCH, item), InventoryType.SlotType.RESULT, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            this.listener.onClick(event);
            assertFalse(event.isCancelled());
        }
    }

    @Test
    void paperPreviewHidesOnlyForbiddenCartographyResults() throws Exception {
        Class<?> previewType = Class.forName(MapInteractionListener.class.getName() + "$PaperPreview");
        var constructor = previewType.getDeclaredConstructor(MapInteractionListener.class);
        constructor.setAccessible(true);
        Object preview = constructor.newInstance(this.listener);
        var prepare = previewType.getDeclaredMethod("onPrepare", PrepareResultEvent.class);
        prepare.setAccessible(true);
        ItemStack locked = this.map(-1, "LOCK");
        PrepareResultEvent denied = new PrepareResultEvent(this.view(InventoryType.CARTOGRAPHY, locked), locked);
        prepare.invoke(preview, denied);
        assertNull(denied.getResult());
        this.option("LOCK", true);
        PrepareResultEvent allowed = new PrepareResultEvent(this.view(InventoryType.CARTOGRAPHY, locked), locked);
        prepare.invoke(preview, allowed);
        assertSame(locked, allowed.getResult());
        this.option("LOCK", false);
        PrepareResultEvent unrelated = new PrepareResultEvent(this.view(InventoryType.CRAFTING, locked), locked);
        prepare.invoke(preview, unrelated);
        assertSame(locked, unrelated.getResult());
    }

    @Test
    void reloadIsCheckedAgainWhenTakingAnExistingPreview() {
        InventoryView view = this.view(InventoryType.WORKBENCH, this.map(-1, "COPY"));
        this.listener.onPrepareCraft(new PrepareItemCraftEvent((CraftingInventory) view.getTopInventory(), view, false));
        assertNotNull(view.getItem(0));
        this.option("COPY", false);
        InventoryClickEvent take = new InventoryClickEvent(view, InventoryType.SlotType.RESULT, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        this.listener.onClick(take);
        assertTrue(take.isCancelled());
        this.listener.onPrepareCraft(new PrepareItemCraftEvent((CraftingInventory) view.getTopInventory(), view, false));
        assertNull(view.getItem(0));
    }

    private Block block(Material material) {
        Class<?> stateType = material == Material.WHITE_BANNER || material == Material.WHITE_WALL_BANNER ? Banner.class : BlockState.class;
        Object state = Proxy.newProxyInstance(BlockState.class.getClassLoader(), new Class<?>[]{stateType}, (proxy, method, args) -> {
            throw new AssertionError(method.getName());
        });
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getType" -> material;
            case "getState" -> state;
            default -> throw new AssertionError(method.getName());
        });
    }

    private void option(String operation, boolean allowed) {
        String field = switch (operation) {
            case "LOCK" -> "allowLock";
            case "SCALE" -> "allowScale";
            default -> "allowCopy";
        };
        NmsPlayerFixture.set(PluginConfig.MapOptions.class, this.options, field, allowed);
    }

    private ItemStack map(int id, String operation) {
        net.minecraft.world.item.ItemStack item = new net.minecraft.world.item.ItemStack(Items.FILLED_MAP);
        item.set(DataComponents.MAP_ID, new MapId(id));
        if (!operation.equals("COPY")) {
            item.set(DataComponents.MAP_POST_PROCESSING, MapPostProcessing.valueOf(operation));
        }
        return CraftItemStack.asCraftMirror(item);
    }

    private InventoryView view(InventoryType type, ItemStack result) {
        ItemStack[] items = new ItemStack[type == InventoryType.CARTOGRAPHY ? 3 : 10];
        int resultSlot = type == InventoryType.CARTOGRAPHY ? 2 : 0;
        items[resultSlot] = result;
        Class<?> inventoryType = type == InventoryType.CARTOGRAPHY ? Inventory.class : CraftingInventory.class;
        Inventory inventory = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(), new Class<?>[]{inventoryType}, (proxy, method, args) -> switch (method.getName()) {
            case "getType" -> type;
            case "getSize" -> items.length;
            case "getItem" -> items[(int) args[0]];
            case "getResult" -> items[resultSlot];
            case "setResult" -> { items[resultSlot] = (ItemStack) args[0]; yield null; }
            default -> throw new AssertionError(method.getName());
        });
        return (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(), new Class<?>[]{InventoryView.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getTopInventory" -> inventory;
            case "getBottomInventory" -> this.player.getInventory();
            case "getPlayer" -> this.player;
            case "getItem" -> items[(int) args[0]];
            case "convertSlot" -> args[0];
            default -> throw new AssertionError(method.getName());
        });
    }
}

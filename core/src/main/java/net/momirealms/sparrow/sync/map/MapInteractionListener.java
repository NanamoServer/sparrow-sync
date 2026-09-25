package net.momirealms.sparrow.sync.map;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.MapPostProcessing;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.proxy.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Banner;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class MapInteractionListener implements Listener {

    // 地图总开关启动时读取, 交互开关每次事件读取.
    public void register(@NotNull Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (VersionHelper.hasPaperPatch) {
            Bukkit.getPluginManager().registerEvents(new PaperPreview(), plugin);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.useItemInHand() == Event.Result.DENY) return;
        if (PluginConfig.synchronization$map().playerOperation().allowBannerModification()) return;
        net.minecraft.world.item.ItemStack item = this.nativeMap(event.getItem());
        if (item != null) {
            MapId id = item.get(DataComponents.MAP_ID);
            if (id != null && id.id() < 0 && event.getClickedBlock() != null && event.getClickedBlock().getState() instanceof Banner) {
                // 只禁止当前手使用物品, 保留其他插件对方块交互的处理结果.
                event.setUseItemInHand(Event.Result.DENY);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event instanceof InventoryCreativeEvent || event.getAction() == InventoryAction.CLONE_STACK) return;
        InventoryType type = event.getView().getTopInventory().getType();
        int slot = event.getRawSlot();
        if (type == InventoryType.CARTOGRAPHY ? slot != 2 : (type != InventoryType.WORKBENCH && type != InventoryType.CRAFTING) || slot != 0) return;
        // 在取出和后处理前拦截, 同时覆盖 Shift、快捷栏交换和丢出成品.
        if (this.blocked(event.getCurrentItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (this.blocked(event.getInventory().getResult())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        // 取消事件可阻止合成器分配地图 ID、产出成品和扣除材料.
        if (this.blocked(event.getResult())) {
            event.setCancelled(true);
        }
    }

    private boolean blocked(@Nullable ItemStack result) {
        net.minecraft.world.item.ItemStack item = this.nativeMap(result);
        if (item == null) return false;
        MapId id = item.get(DataComponents.MAP_ID);
        if (id == null || id.id() >= 0) return false;
        PluginConfig.MapPlayerOperationOptions options = PluginConfig.synchronization$map().playerOperation();
        MapPostProcessing processing = item.get(DataComponents.MAP_POST_PROCESSING);
        if (processing == MapPostProcessing.LOCK) return !options.allowLock();
        if (processing == MapPostProcessing.SCALE) return !options.allowScale();
        return !options.allowCopy();
    }

    // 直接读取 Craft 物品的组件, 无需复制物品.
    @Nullable
    private net.minecraft.world.item.ItemStack nativeMap(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) return null;
        return CraftItemStackProxy.INSTANCE.getHandle(item);
    }

    // 仅在 Paper 加载预览监听器; Spigot 通过 Bukkit 点击事件拦截取出.
    private final class PaperPreview implements Listener {

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPrepare(PrepareResultEvent event) {
            if (event.getInventory().getType() == InventoryType.CARTOGRAPHY && MapInteractionListener.this.blocked(event.getResult())) {
                event.setResult(null);
            }
        }
    }
}

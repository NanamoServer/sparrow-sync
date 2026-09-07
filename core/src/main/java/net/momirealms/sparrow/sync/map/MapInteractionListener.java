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

    // 地图总开关在启动时确定, 交互开关在每次事件中读取最新配置.
    public void register(@NotNull Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (VersionHelper.isPaper()) {
            Bukkit.getPluginManager().registerEvents(new PaperPreview(), plugin);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.useItemInHand() == Event.Result.DENY) return;
        if (PluginConfig.synchronization$map().allowBannerModification()) return;
        net.minecraft.world.item.ItemStack item = this.nativeMap(event.getItem());
        if (item != null) {
            MapId id = item.get(DataComponents.MAP_ID);
            if (id != null && id.id() < 0 && event.getClickedBlock() != null && event.getClickedBlock().getState() instanceof Banner) {
                // 按本次交互的手拒绝物品使用, 保留其他插件对方块交互的判定.
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
        // 点击事件早于原版取出和后处理, Shift、快捷栏交换和丢出结果也沿此入口取消.
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
        // 合成器在此事件之后才分配地图 ID、发出成品并扣除材料.
        if (this.blocked(event.getResult())) {
            event.setCancelled(true);
        }
    }

    private boolean blocked(@Nullable ItemStack result) {
        net.minecraft.world.item.ItemStack item = this.nativeMap(result);
        if (item == null) return false;
        MapId id = item.get(DataComponents.MAP_ID);
        if (id == null || id.id() >= 0) return false;
        PluginConfig.MapOptions options = PluginConfig.synchronization$map();
        MapPostProcessing processing = item.get(DataComponents.MAP_POST_PROCESSING);
        if (processing == MapPostProcessing.LOCK) return !options.allowLock();
        if (processing == MapPostProcessing.SCALE) return !options.allowScale();
        return !options.allowCopy();
    }

    // 原版事件使用 Craft 物品镜像, 直接只读访问组件, 不为判定复制物品.
    @Nullable
    private net.minecraft.world.item.ItemStack nativeMap(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) return null;
        return CraftItemStackProxy.INSTANCE.getHandle(item);
    }

    // Paper 预览入口单独装载, Spigot 的实际取出拦截由上面的 Bukkit 点击事件完成.
    private final class PaperPreview implements Listener {

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPrepare(PrepareResultEvent event) {
            if (event.getInventory().getType() == InventoryType.CARTOGRAPHY && MapInteractionListener.this.blocked(event.getResult())) {
                event.setResult(null);
            }
        }
    }
}

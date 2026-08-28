package net.momirealms.sparrow.sync.data;

import net.momirealms.sparrow.sync.data.type.EnderChestDataType;
import net.momirealms.sparrow.sync.data.type.ExperienceDataType;
import net.momirealms.sparrow.sync.data.type.GameModeDataType;
import net.momirealms.sparrow.sync.data.type.HealthDataType;
import net.momirealms.sparrow.sync.data.type.HungerDataType;
import net.momirealms.sparrow.sync.data.type.InventoryDataType;
import net.momirealms.sparrow.sync.data.type.PDCDataType;
import net.momirealms.sparrow.sync.data.type.PotionEffectsDataType;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class PlayerDataTypes {

    private PlayerDataTypes() {
    }

    /**
     * 内置八类的实例, 含依赖 NMS 的三类, 仅在真实服务器环境装配.
     * 这只是插件自带类型的清单, 完整的装配集合由启动流程汇总 (未来第三方类型经注册窗口加入)
     * 后一次性交给 {@link SnapshotApplier}, 运行期的类型查询以注册表为准.
     *
     * @param pdcMergeNamespaces PDC 应用策略的命名空间白名单, 空为全量替换
     * @param logger             物品溢出丢弃等应用告警的输出
     */
    @NotNull
    public static List<PlayerDataType<?>> builtinTypes(@NotNull Set<String> pdcMergeNamespaces, @NotNull PluginLogger logger) {
        List<PlayerDataType<?>> types = new ArrayList<>(8);
        types.add(new ExperienceDataType());
        types.add(new HealthDataType());
        types.add(new HungerDataType());
        types.add(new PotionEffectsDataType());
        types.add(new GameModeDataType());
        types.add(new InventoryDataType(logger));
        types.add(new EnderChestDataType(logger));
        types.add(new PDCDataType(pdcMergeNamespaces));
        return types;
    }
}

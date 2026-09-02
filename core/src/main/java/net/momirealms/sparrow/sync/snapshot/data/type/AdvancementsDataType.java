package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementHolderProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.server.PlayerAdvancementsProxy;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 同步玩家 advancement 进度, 使用原版 Codec 保留 criterion 完成时间.
 * 采集值只保留脱离玩家状态的标识、criterion 与时间数组, 不携带活的 AdvancementProgress.
 * 应用时直接替换完整进度并刷新监听, 奖励与广播流程不会被触发.
 */
public final class AdvancementsDataType extends CodecDataType<AdvancementsDataType.Advancements> {
    public static final DataKey ADVANCEMENTS = DataKey.sparrow("advancements");

    private static final String INCOMPLETE_CRITERION = "sparrow-sync:incomplete";
    private static final Codec<Map<Object, AdvancementProgress>> STORED_CODEC = Codec.unboundedMap(IdentifierProxy.INSTANCE.getCodec(), AdvancementProgress.CODEC);
    static final Codec<Advancements> CODEC = STORED_CODEC.xmap(AdvancementsDataType::fromStored, AdvancementsDataType::toStored);

    public AdvancementsDataType() {
        super(ADVANCEMENTS, StorageFormat.STRUCTURED, CODEC);
    }

    @Override
    @NotNull //todo 只存储已获得的 不要全量采集? 是否存在快路径?
    protected Advancements captureValue(@NotNull Player player) {
        ServerPlayer handle = handle(player);
        Map<Object, Object> progress = PlayerAdvancementsProxy.INSTANCE.getProgress(handle.getAdvancements());
        AdvancementValue[] captured = new AdvancementValue[progress.size()];
        int count = 0;
        for (Map.Entry<Object, Object> entry : progress.entrySet()) {
            AdvancementProgress current = (AdvancementProgress) entry.getValue();
            AdvancementValue value = captureProgress(AdvancementHolderProxy.INSTANCE.id(entry.getKey()), current);
            if (value != null) captured[count++] = value;
        }
        return new Advancements(Arrays.copyOf(captured, count));
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Advancements value) {
        ServerPlayer handle = handle(player);
        ServerAdvancementManager manager = MinecraftServer.getServer().getAdvancements();
        PlayerAdvancements playerAdvancements = handle.getAdvancements();
        PlayerAdvancementsProxy proxy = PlayerAdvancementsProxy.INSTANCE;
        // 清理旧监听与进度, 再装入完整快照
        proxy.clearTriggers(playerAdvancements); // todo 这方式真不行, 太慢了, 而且10个玩家你在全局去清理注册10次? 这不行, 需要改进方案.
        Map<Object, Object> progressByAdvancement = proxy.getProgress(playerAdvancements);
        Set<Object> changed = proxy.getProgressChanged(playerAdvancements);
        progressByAdvancement.clear();
        changed.clear();
        for (AdvancementHolder advancement : manager.getAllAdvancements()) {
            AdvancementProgress progress = new AdvancementProgress();
            progress.update(advancement.value().requirements());
            AdvancementValue captured = value.find(AdvancementHolderProxy.INSTANCE.id(advancement));
            if (captured != null) restoreCriteria(progress, captured);
            progressByAdvancement.put(advancement, progress);
            changed.add(advancement);
            proxy.markForVisibilityUpdate(playerAdvancements, advancement);
        }
        // 重建触发监听与可见性, 最后一次推送客户端
        proxy.registerListeners(playerAdvancements, manager);
        if (VersionHelper.isOrAbove1_21_5()) {
            proxy.flushDirty$0(playerAdvancements, handle, true);
        } else {
            proxy.flushDirty(playerAdvancements, handle);
        }
    }

    @Nullable
    private static AdvancementValue captureProgress(Object id, AdvancementProgress progress) {
        Map<String, Object> criteria = AdvancementProgressProxy.INSTANCE.getCriteria(progress);
        int completed = 0;
        for (Object value : criteria.values()) {
            if (((CriterionProgress) value).isDone()) completed++;
        }
        if (completed == 0) return null;

        String[] names = new String[completed];
        Instant[] obtained = new Instant[completed];
        int index = 0;
        for (Map.Entry<String, Object> entry : criteria.entrySet()) {
            CriterionProgress criterion = (CriterionProgress) entry.getValue();
            if (!criterion.isDone()) continue;
            names[index] = entry.getKey();
            obtained[index] = criterion.getObtained();
            index++;
        }
        return new AdvancementValue(id, names, obtained, progress.isDone());
    }

    private static Advancements fromStored(Map<Object, AdvancementProgress> stored) {
        AdvancementValue[] values = new AdvancementValue[stored.size()];
        int count = 0;
        for (Map.Entry<Object, AdvancementProgress> entry : stored.entrySet()) {
            AdvancementValue value = captureProgress(entry.getKey(), entry.getValue());
            if (value != null) values[count++] = value;
        }
        return new Advancements(count == values.length ? values : Arrays.copyOf(values, count));
    }

    private static Map<Object, AdvancementProgress> toStored(Advancements advancements) {
        AdvancementValue[] values = advancements.values();
        Map<Object, AdvancementProgress> stored = new LinkedHashMap<>(values.length);
        for (int i = 0; i < values.length; i++) {
            AdvancementValue value = values[i];
            String[] criteria = value.criteria();
            String[] requirements;
            if (value.done()) {
                requirements = criteria;
            } else {
                requirements = Arrays.copyOf(criteria, criteria.length + 1);
                requirements[criteria.length] = INCOMPLETE_CRITERION;
            }
            AdvancementProgress progress = new AdvancementProgress();
            progress.update(AdvancementRequirements.allOf(Arrays.asList(requirements)));
            restoreCriteria(progress, value);
            stored.put(value.id(), progress);
        }
        return stored;
    }

    private static void restoreCriteria(AdvancementProgress progress, AdvancementValue value) {
        Map<String, Object> target = AdvancementProgressProxy.INSTANCE.getCriteria(progress);
        String[] criteria = value.criteria();
        Instant[] obtained = value.obtained();
        for (int i = 0; i < criteria.length; i++) {
            String criterion = criteria[i];
            if (target.containsKey(criterion)) target.put(criterion, new CriterionProgress(obtained[i]));
        }
    }

    private static ServerPlayer handle(Player player) {
        return ((CraftPlayer) player).getHandle();
    }

    public record Advancements(@NotNull AdvancementValue @NotNull [] values) {

        @Nullable
        private AdvancementValue find(Object id) {
            for (int i = 0; i < this.values.length; i++) {
                AdvancementValue value = this.values[i];
                if (value.id().equals(id)) return value;
            }
            return null;
        }
    }

    public record AdvancementValue(
            @NotNull Object id,
            @NotNull String @NotNull [] criteria,
            @NotNull Instant @NotNull [] obtained,
            boolean done
    ) {
    }
}

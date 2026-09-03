package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementHolderProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.CriterionListenerProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.CriterionProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.CriterionProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.TriggerInstanceKeyProxy;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 同步玩家 advancement 进度, 使用原版 Codec 保留 criterion 完成时间.
 * 采集值只保留脱离玩家状态的标识、criterion 与时间数组, 不携带活的 AdvancementProgress.
 * 应用时修补现有 CriterionProgress, 奖励、广播和 Bukkit advancement 事件不会被触发.
 * todo 再审
 */
public final class AdvancementsDataType extends CodecDataType<AdvancementsDataType.Advancements> {
    public static final DataKey ADVANCEMENTS = DataKey.sparrow("advancements");

    private static final String INCOMPLETE_CRITERION = "sparrow-sync:incomplete";
    private static final Codec<Map<Object, AdvancementProgress>> STORED_CODEC = Codec.unboundedMap(IdentifierProxy.INSTANCE.getCodec(), AdvancementProgress.CODEC);
    static final Codec<Advancements> CODEC = STORED_CODEC.xmap(AdvancementsDataType::fromStored, AdvancementsDataType::toStored);
    private static final int INDEX_THRESHOLD = 8; // criterion 数超过该值时为快照值建哈希索引, 少量时线性扫描更快

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
        PlayerAdvancements playerAdvancements = handle.getAdvancements();
        PlayerAdvancementsProxy proxy = PlayerAdvancementsProxy.INSTANCE;
        Map<Object, CapturedValue> captured = index(value.values());
        Map<Object, Object> progressByAdvancement = proxy.getProgress(playerAdvancements);
        Set<Object> progressChanged = proxy.getProgressChanged(playerAdvancements);
        boolean changed = false;
        for (Map.Entry<Object, Object> entry : progressByAdvancement.entrySet()) {
            AdvancementHolder advancement = (AdvancementHolder) entry.getKey();
            CapturedValue target = captured.get(AdvancementHolderProxy.INSTANCE.id(advancement));
            if (applyProgress(playerAdvancements, advancement, (AdvancementProgress) entry.getValue(), target, progressChanged)) {
                changed = true;
            }
        }
        if (!changed) return;

        // dirty 状态一次发给客户端, false 会关闭同步产生的 advancement toast
        if (VersionHelper.isOrAbove1_21_5()) {
            proxy.flushDirty$0(playerAdvancements, handle, false);
        } else {
            proxy.flushDirty(playerAdvancements, handle);
        }
    }

    private static Map<Object, CapturedValue> index(AdvancementValue[] values) {
        Map<Object, CapturedValue> indexed = new HashMap<>(values.length * 2);
        for (int i = 0; i < values.length; i++) {
            AdvancementValue value = values[i];
            String[] criteria = value.criteria();
            // criterion 少时线性扫描更快, 多时建哈希索引
            Map<String, Instant> lookup = null;
            if (criteria.length > INDEX_THRESHOLD) {
                Instant[] obtained = value.obtained();
                lookup = new HashMap<>(criteria.length * 2);
                for (int j = 0; j < criteria.length; j++) {
                    lookup.put(criteria[j], obtained[j]);
                }
            }
            indexed.put(value.id(), new CapturedValue(value, lookup));
        }
        return indexed;
    }

    private static boolean applyProgress(PlayerAdvancements playerAdvancements, AdvancementHolder advancement, AdvancementProgress progress, @Nullable CapturedValue target, Set<Object> progressChanged) {
        Map<String, Object> criteria = AdvancementProgressProxy.INSTANCE.getCriteria(progress);
        Map<String, ?> definitions = advancement.value().criteria();
        boolean changed = false;
        boolean wasDone = false;
        for (Map.Entry<String, ?> entry : definitions.entrySet()) {
            String name = entry.getKey();
            CriterionProgress criterion = (CriterionProgress) criteria.get(name);
            Instant current = criterion.getObtained();
            Instant expected = findObtained(target, name);
            if (Objects.equals(current, expected)) continue;

            // 首个差异出现时才读完成态, 此时尚未写入, 结果就是改动前的基线
            if (!changed) {
                wasDone = progress.isDone();
                changed = true;
            }
            // 直接改原对象, 绕过 award/revoke 产生的事件、奖励与广播.
            CriterionProgressProxy.INSTANCE.setObtained(criterion, expected);
            if (!wasDone && (current == null) != (expected == null)) {
                setTriggerActive(playerAdvancements, advancement, name, entry.getValue(), expected == null);
            }
        }
        if (!changed) return false;

        boolean done = progress.isDone();
        if (wasDone != done) {
            reconcileTriggers(playerAdvancements, advancement, progress, definitions, !done);
            PlayerAdvancementsProxy.INSTANCE.markForVisibilityUpdate(playerAdvancements, advancement);
        }
        progressChanged.add(advancement);
        return true;
    }

    @Nullable
    private static Instant findObtained(@Nullable CapturedValue target, String criterion) {
        if (target == null) return null;
        Map<String, Instant> lookup = target.index();
        if (lookup != null) return lookup.get(criterion);
        String[] criteria = target.value().criteria();
        Instant[] obtained = target.value().obtained();
        for (int i = 0; i < criteria.length; i++) {
            if (criteria[i].equals(criterion)) return obtained[i];
        }
        return null;
    }

    private static void reconcileTriggers(PlayerAdvancements playerAdvancements, AdvancementHolder advancement, AdvancementProgress progress, Map<String, ?> definitions, boolean active) {
        Map<String, Object> criteria = AdvancementProgressProxy.INSTANCE.getCriteria(progress);
        for (Map.Entry<String, ?> entry : definitions.entrySet()) {
            CriterionProgress criterion = (CriterionProgress) criteria.get(entry.getKey());
            setTriggerActive(playerAdvancements, advancement, entry.getKey(), entry.getValue(), active && !criterion.isDone());
        }
    }

    private static void setTriggerActive(PlayerAdvancements playerAdvancements, AdvancementHolder advancement, String criterionName, Object criterion, boolean active) {
        Object trigger = CriterionProxy.INSTANCE.trigger(criterion);
        if (VersionHelper.isOrAbove26_2()) {
            setCurrentTriggerActive(playerAdvancements, advancement, criterionName, criterion, trigger, active);
        } else if (CriterionProxy.SIMPLE_TRIGGER.isInstance(trigger)) {
            setLegacyTriggerActive(playerAdvancements, advancement, criterionName, criterion, trigger, active);
        }
    }

    private static void setLegacyTriggerActive(PlayerAdvancements playerAdvancements, AdvancementHolder advancement, String criterionName, Object criterion, Object trigger, boolean active) {
        Map<Object, Set<Object>> table = PlayerAdvancementsProxy.INSTANCE.getCriterionData(playerAdvancements);
        Set<Object> listeners = table.get(trigger);
        if (active) {
            if (listeners == null) {
                listeners = new HashSet<>();
                table.put(trigger, listeners);
            }
            listeners.add(CriterionListenerProxy.INSTANCE.newInstance(CriterionProxy.INSTANCE.triggerInstance(criterion), advancement, criterionName));
        } else if (listeners != null) {
            listeners.remove(CriterionListenerProxy.INSTANCE.newInstance(CriterionProxy.INSTANCE.triggerInstance(criterion), advancement, criterionName));
            if (listeners.isEmpty()) table.remove(trigger);
        }
    }

    private static void setCurrentTriggerActive(PlayerAdvancements playerAdvancements, AdvancementHolder advancement, String criterionName, Object criterion, Object trigger, boolean active) {
        Map<Object, Map<Object, Object>> table = PlayerAdvancementsProxy.INSTANCE.getActiveTriggers(playerAdvancements);
        Map<Object, Object> listeners = table.get(trigger);
        if (active) {
            if (listeners == null) {
                listeners = new HashMap<>();
                table.put(trigger, listeners);
            }
            listeners.put(TriggerInstanceKeyProxy.INSTANCE.newInstance(advancement, criterionName), CriterionProxy.INSTANCE.triggerInstance(criterion));
        } else if (listeners != null) {
            listeners.remove(TriggerInstanceKeyProxy.INSTANCE.newInstance(advancement, criterionName));
            if (listeners.isEmpty()) table.remove(trigger);
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
    }

    public record AdvancementValue(
            @NotNull Object id,
            @NotNull String @NotNull [] criteria,
            @NotNull Instant @NotNull [] obtained,
            boolean done
    ) {
    }

    private record CapturedValue(AdvancementValue value, @Nullable Map<String, Instant> index) {
    }
}

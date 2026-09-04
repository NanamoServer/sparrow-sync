package net.momirealms.sparrow.sync.snapshot.data.type;

import com.google.gson.JsonObject;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.momirealms.sparrow.nbt.*;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.*;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.server.PlayerAdvancementsProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerJsonFile;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerJsonStorage;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.util.GsonUtils;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class AdvancementsDataType implements NativePlayerDataType<AdvancementsDataType.Advancements> {
    public static final DataKey ADVANCEMENTS = DataKey.sparrow("advancements");

    private static final String IDS_KEY = "ids";
    private static final String CRITERIA_KEY = "criteria";
    private static final String COUNTS_KEY = "counts";
    private static final String OBTAINED_KEY = "obtained";
    private static final String DONE_KEY = "done";
    private static final int INDEX_THRESHOLD = 8; // criterion 数超过该值时为快照值建哈希索引, 少量时线性扫描更快
    private static final AdvancementValue[] EMPTY_VALUES = new AdvancementValue[0];
    private static final DateTimeFormatter OBTAINED_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final AdvancementSlots advancementSlots = new AdvancementSlots(); // 全服共享的稳定 advancement 槽位, 所有玩家候选位图都以此布局解释.

    @Override
    @NotNull
    public DataKey key() {
        return ADVANCEMENTS;
    }

    @Override
    @NotNull
    public StorageFormat storage() {
        return StorageFormat.STRUCTURED;
    }

    /**
     * 在玩家首次应用和首次常规 flush 前安装进度跟踪器.
     * 重复调用会识别现有 wrapper 并保持原对象, 因此同一玩家只安装一次.
     *
     * @param player 已进入 PlayerJoinEvent 且尚未执行 Sparrow Player apply 的玩家
     */
    public void injectTracker(@NotNull Player player) {
        // 读取实际 PlayerAdvancements, wrapper 必须安装到 Bukkit Player 持有的同一个对象
        ServerPlayer handle = handle(player);
        PlayerAdvancements advancements = handle.getAdvancements();
        Set<Object> progressChanged = PlayerAdvancementsProxy.INSTANCE.getProgressChanged(advancements);
        if (progressChanged instanceof AdvancementProgressChangedWrapperSet) return;

        // 代理掉 PlayerAdvancements#progressChanged 以监听变化.
        AdvancementProgressChangedWrapperSet tracking = new AdvancementProgressChangedWrapperSet(progressChanged, PlayerAdvancementsProxy.INSTANCE.getProgress(advancements), this.advancementSlots);
        PlayerAdvancementsProxy.INSTANCE.setProgressChanged(advancements, tracking);
    }

    /**
     * 从当前 PlayerAdvancements 生成只包含实际进度的脱离快照.
     *
     * @param player 要采集的在线玩家
     * @return 完全脱离玩家可变状态的 advancement 数据
     */
    @Override
    @NotNull
    public Advancements capture(@NotNull Player player) {
        ServerPlayer handle = handle(player);
        PlayerAdvancements advancements = handle.getAdvancements();
        Map<Object, Object> progress = PlayerAdvancementsProxy.INSTANCE.getProgress(advancements);
        Set<Object> progressChanged = PlayerAdvancementsProxy.INSTANCE.getProgressChanged(advancements);
        AdvancementProgressChangedWrapperSet tracking = progressChanged instanceof AdvancementProgressChangedWrapperSet current ? current : null;
        // 候选完整且布局稳定时使用稀疏路径
        if (tracking != null && tracking.complete()) {
            AdvancementSlots.Layout layout = this.advancementSlots.current();
            if (layout != null) return captureSparse(progress, tracking, layout);
        }
        // 布局换代冲突或候选缺失时使用完整 Map
        Advancements captured = captureDense(progress);
        return tracking == null ? captured : mergeRetained(captured, tracking.retainedUnknown());
    }

    /**
     * 稀疏采集
     * 只访问跟踪位图中的槽位, 并用当前 holder 和 progress 做最终有效性校验.
     *
     * @param progress 当前玩家的 holder -> AdvancementProgress Map
     * @param tracking 候选位图完整的玩家 tracker
     * @param layout 当前服务端 advancement 布局
     * @return 仅包含仍有实际进度的脱离值
     */
    static Advancements captureSparse(Map<Object, Object> progress, AdvancementProgressChangedWrapperSet tracking, AdvancementSlots.Layout layout) {
        long[] candidates = tracking.candidates(); // Copy
        // 候选是结果容量上界, 预先计数避免使用固定数组
        int capacity = 0;
        for (int i = 0; i < candidates.length; i++) {
            capacity += Long.bitCount(candidates[i]);
        }
        AdvancementValue[] captured = new AdvancementValue[capacity];
        int count = 0;
        for (int i = 0; i < candidates.length; i++) {
            long word = candidates[i];
            while (word != 0L) {
                // 每轮读取并清掉最低置位, 循环次数等于候选数量
                int bit = Long.numberOfTrailingZeros(word);
                Object holder = layout.holder((i << 6) + bit);
                // reload 删除项对应 null holder, 已撤销进度则由 captureProgress 返回 null
                if (holder != null && progress.get(holder) instanceof AdvancementProgress current) {
                    AdvancementValue value = captureProgress(holder, current);
                    if (value != null) captured[count++] = value;
                }
                word &= word - 1L;
            }
        }
        Advancements current = new Advancements(count == captured.length ? captured : Arrays.copyOf(captured, count));
        return mergeRetained(current, tracking.retainedUnknown());
    }

    /**
     * 稠密采集
     * 扫描玩家完整 progress Map, 作为所有安全回退场景的参考采集算法.
     *
     * @param progress 当前玩家的 holder -> AdvancementProgress Map
     * @return 仅包含至少一个已取得 criterion 的脱离值
     */
    static Advancements captureDense(Map<Object, Object> progress) {
        AdvancementValue[] captured = new AdvancementValue[progress.size()];
        int count = 0;
        for (Map.Entry<Object, Object> entry : progress.entrySet()) {
            AdvancementProgress current = (AdvancementProgress) entry.getValue();
            AdvancementValue value = captureProgress(entry.getKey(), current);
            if (value != null) captured[count++] = value;
        }
        return new Advancements(count == captured.length ? captured : Arrays.copyOf(captured, count));
    }

    // 本服实时进度覆盖同 ID 保留值, 其余未知项继续进入下一份快照
    private static Advancements mergeRetained(Advancements captured, AdvancementValue[] retained) {
        if (retained.length == 0) return captured;
        AdvancementValue[] current = captured.values();
        AdvancementValue[] merged = Arrays.copyOf(current, current.length + retained.length);
        int count = current.length;
        for (int i = 0; i < retained.length; i++) {
            AdvancementValue unknown = retained[i];
            boolean present = false;
            for (int j = 0; j < current.length; j++) {
                if (current[j].id().equals(unknown.id())) {
                    present = true;
                    break;
                }
            }
            if (!present) merged[count++] = unknown;
        }
        return new Advancements(count == merged.length ? merged : Arrays.copyOf(merged, count));
    }

    @Override
    @NotNull
    public Tag encode(@NotNull Advancements value) {
        AdvancementValue[] values = value.values();
        int criterionCount = 0;
        for (int i = 0; i < values.length; i++) {
            criterionCount += values[i].criteria().length;
        }

        ListTag ids = NBT.createList(new ArrayList<>(values.length));
        ListTag criteria = NBT.createList(new ArrayList<>(criterionCount));
        int[] counts = new int[values.length];
        long[] obtained = new long[criterionCount];
        byte[] done = new byte[values.length];
        int criterionIndex = 0;
        for (int i = 0; i < values.length; i++) {
            AdvancementValue advancement = values[i];
            String[] names = advancement.criteria();
            Instant[] times = advancement.obtained();
            ids.add(NBT.createString(advancement.id().toString()));
            counts[i] = names.length;
            done[i] = advancement.done() ? (byte) 1 : 0;
            for (int j = 0; j < names.length; j++) {
                criteria.add(NBT.createString(names[j]));
                obtained[criterionIndex++] = times[j].toEpochMilli();
            }
        }

        CompoundTag root = NBT.createCompound();
        root.put(IDS_KEY, ids);
        root.put(CRITERIA_KEY, criteria);
        root.putIntArray(COUNTS_KEY, counts);
        root.putLongArray(OBTAINED_KEY, obtained);
        root.putByteArray(DONE_KEY, done);
        return root;
    }

    @Override
    @NotNull
    public Advancements decode(@NotNull Tag data, int mcDataVersion) throws IOException {
        if (!(data instanceof CompoundTag root)
                || !(root.get(IDS_KEY) instanceof ListTag ids)
                || !(root.get(CRITERIA_KEY) instanceof ListTag criteria)
                || !(root.get(COUNTS_KEY) instanceof IntArrayTag countTag)
                || !(root.get(OBTAINED_KEY) instanceof LongArrayTag obtainedTag)
                || !(root.get(DONE_KEY) instanceof ByteArrayTag doneTag)) {
            throw new IOException("advancements data is not a parallel array compound");
        }
        int[] counts = countTag.value();
        long[] storedObtained = obtainedTag.value();
        byte[] done = doneTag.value();
        if (ids.size() != counts.length || ids.size() != done.length || criteria.size() != storedObtained.length) {
            throw new IOException("advancements arrays have different lengths");
        }

        int criterionCount = 0;
        for (int i = 0; i < counts.length; i++) {
            int count = counts[i];
            if (count <= 0 || count > storedObtained.length - criterionCount) {
                throw new IOException("invalid advancement criterion count at index " + i);
            }
            criterionCount += count;
        }
        if (criterionCount != storedObtained.length) {
            throw new IOException("advancement criterion counts do not cover the flattened arrays");
        }

        AdvancementValue[] values = new AdvancementValue[ids.size()];
        int criterionIndex = 0;
        for (int i = 0; i < values.length; i++) {
            String idName = ids.getString(i, null);
            Object id = idName == null ? null : IdentifierProxy.INSTANCE.tryParse(idName);
            if (id == null) {
                throw new IOException("invalid advancement identifier at index " + i);
            }
            int count = counts[i];
            String[] names = new String[count];
            Instant[] obtained = new Instant[count];
            for (int j = 0; j < count; j++) {
                String name = criteria.getString(criterionIndex, null);
                if (name == null) {
                    throw new IOException("invalid advancement criterion at index " + criterionIndex);
                }
                names[j] = name;
                obtained[j] = Instant.ofEpochMilli(storedObtained[criterionIndex]);
                criterionIndex++;
            }
            values[i] = new AdvancementValue(id, names, obtained, done[i] != 0);
        }
        return new Advancements(values);
    }

    @Override
    public void apply(@NotNull Player player, @NotNull Advancements value) {
        ServerPlayer handle = handle(player);
        PlayerAdvancements playerAdvancements = handle.getAdvancements();
        PlayerAdvancementsProxy proxy = PlayerAdvancementsProxy.INSTANCE;
        // 目标值按 ID 建立工作索引, 成功匹配的本服条目会从中移除
        Map<Object, CapturedValue> captured = index(value.values());
        Map<Object, Object> progressByAdvancement = proxy.getProgress(playerAdvancements);
        Set<Object> progressChanged = proxy.getProgressChanged(playerAdvancements);
        AdvancementProgressChangedWrapperSet tracking = progressChanged instanceof AdvancementProgressChangedWrapperSet current ? current : null;
        boolean changed;
        // 与 capture 相同, 候选完整时才进行稀疏扫描
        if (tracking != null && tracking.complete()) {
            AdvancementSlots.Layout layout = this.advancementSlots.current();
            changed = layout == null
                    ? applyDense(playerAdvancements, progressByAdvancement, captured, progressChanged)
                    : applySparse(playerAdvancements, progressByAdvancement, captured, progressChanged, tracking.candidates(), layout);
        } else {
            changed = applyDense(playerAdvancements, progressByAdvancement, captured, progressChanged);
        }
        // 工作索引中只剩本服无法定位的 ID, 后续快照继续携带这些进度
        if (tracking != null) tracking.retainedUnknown(remainingValues(captured));
        if (!changed) return;

        // 所有 criterion 差量完成后只 flush 一次
        if (VersionHelper.isOrAbove1_21_5()) {
            proxy.flushDirty$0(playerAdvancements, handle, false);
        } else {
            proxy.flushDirty(playerAdvancements, handle);
        }
    }

    /**
     * 稀疏应用
     * 应用本地候选与远端快照 ID 的并集, 使本地撤销和远端新增都进入同一差量逻辑.
     * <strong>captured 是本次调用的工作索引, 能在本服应用的条目会被移除</strong>.
     *
     * @param playerAdvancements 玩家原有的 PlayerAdvancements 对象
     * @param progressByAdvancement 当前完整进度 Map
     * @param captured 按 ID 索引的远端目标工作集
     * @param progressChanged NMS 客户端 dirty Set
     * @param candidates 玩家曾有实际进度的稳定槽位位图
     * @param layout 当前服务端 advancement 布局
     * @return 任一 criterion 实际变化时返回 true
     */
    private static boolean applySparse(PlayerAdvancements playerAdvancements, Map<Object, Object> progressByAdvancement, Map<Object, CapturedValue> captured, Set<Object> progressChanged, long[] candidates, AdvancementSlots.Layout layout) {
        boolean changed = false;
        // 先处理全部本地候选. 远端缺少对应 ID 时 target 为 null, applyProgress 会撤销旧进度
        for (int i = 0; i < candidates.length; i++) {
            long word = candidates[i];
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                Object holder = layout.holder((i << 6) + bit);
                if (holder instanceof AdvancementHolder advancement && progressByAdvancement.get(holder) instanceof AdvancementProgress progress) {
                    CapturedValue target = captured.remove(AdvancementHolderProxy.INSTANCE.id(advancement));
                    if (applyProgress(playerAdvancements, advancement, progress, target, progressChanged)) changed = true;
                }
                word &= word - 1L;
            }
        }
        // 应用远端独有项, 成功定位的条目从工作索引移除
        Iterator<CapturedValue> iterator = captured.values().iterator();
        while (iterator.hasNext()) {
            CapturedValue target = iterator.next();
            int slot = layout.slot(target.value().id());
            Object holder = layout.holder(slot);
            if (holder instanceof AdvancementHolder advancement && progressByAdvancement.get(holder) instanceof AdvancementProgress progress) {
                if (applyProgress(playerAdvancements, advancement, progress, target, progressChanged)) changed = true;
                iterator.remove();
            }
        }
        return changed;
    }

    /**
     * 完整应用
     * 遍历玩家完整 progress Map 并应用目标差量.
     *
     * @param playerAdvancements 玩家原有的 PlayerAdvancements 对象
     * @param progressByAdvancement 当前完整进度 Map
     * @param captured 按 ID 索引的远端目标
     * @param progressChanged NMS 客户端 dirty Set
     * @return 任一 criterion 实际变化时返回 true
     */
    private static boolean applyDense(PlayerAdvancements playerAdvancements, Map<Object, Object> progressByAdvancement, Map<Object, CapturedValue> captured, Set<Object> progressChanged) {
        boolean changed = false;
        for (Map.Entry<Object, Object> entry : progressByAdvancement.entrySet()) {
            AdvancementHolder advancement = (AdvancementHolder) entry.getKey();
            CapturedValue target = captured.remove(AdvancementHolderProxy.INSTANCE.id(advancement));
            if (applyProgress(playerAdvancements, advancement, (AdvancementProgress) entry.getValue(), target, progressChanged)) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull UUID player, @NotNull net.minecraft.nbt.CompoundTag playerData, @NotNull Advancements value) throws IOException {
        if (!VersionHelper.isOrAbove1_21_7()) return NativeApplyResult.NOT_APPLIED;
        AdvancementSlots.Layout layout = this.advancementSlots.current();
        // 原版读取后会丢弃未知 ID, 这类快照留到 Join 应用并挂入玩家 tracker
        if (layout == null || containsUnknown(value.values(), layout)) return NativeApplyResult.NOT_APPLIED;
        if (!PlayerJsonStorage.materialize(player, PlayerJsonFile.ADVANCEMENTS, encodeNativeJson(value))) {
            throw new IOException("atomic advancements JSON replacement failed or is not supported");
        }
        return NativeApplyResult.APPLIED_EXTERNAL;
    }

    private static boolean containsUnknown(AdvancementValue[] values, AdvancementSlots.Layout layout) {
        for (int i = 0; i < values.length; i++) {
            int slot = layout.slot(values[i].id());
            if (layout.holder(slot) == null) return true;
        }
        return false;
    }

    private static byte[] encodeNativeJson(@NotNull Advancements value) {
        JsonObject root = new JsonObject();
        AdvancementValue[] values = value.values();
        for (int i = 0; i < values.length; i++) {
            AdvancementValue advancement = values[i];
            JsonObject criteria = new JsonObject();
            String[] names = advancement.criteria();
            Instant[] obtained = advancement.obtained();
            for (int j = 0; j < names.length; j++) {
                criteria.addProperty(names[j], OBTAINED_TIME_FORMAT.format(obtained[j]));
            }
            JsonObject progress = new JsonObject();
            progress.add("criteria", criteria);
            progress.addProperty("done", advancement.done());
            root.add(advancement.id().toString(), progress);
        }
        root.addProperty("DataVersion", VersionHelper.WORLD_VERSION);
        return GsonUtils.GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
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

    private static AdvancementValue[] remainingValues(Map<Object, CapturedValue> remaining) {
        if (remaining.isEmpty()) return EMPTY_VALUES;
        AdvancementValue[] values = new AdvancementValue[remaining.size()];
        int index = 0;
        for (CapturedValue value : remaining.values()) values[index++] = value.value();
        return values;
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
    private static AdvancementValue captureProgress(Object advancement, AdvancementProgress progress) {
        Map<String, Object> criteria = AdvancementProgressProxy.INSTANCE.getCriteria(progress);
        String[] names = null;
        Instant[] obtained = null;
        int count = 0;
        for (Map.Entry<String, Object> entry : criteria.entrySet()) {
            CriterionProgress criterion = (CriterionProgress) entry.getValue();
            Instant time = criterion.getObtained();
            if (time == null) continue;
            if (names == null) {
                names = new String[criteria.size()];
                obtained = new Instant[criteria.size()];
            }
            names[count] = entry.getKey();
            obtained[count] = time;
            count++;
        }
        if (names == null) return null;
        if (count < names.length) {
            names = Arrays.copyOf(names, count);
            obtained = Arrays.copyOf(obtained, count);
        }
        return new AdvancementValue(AdvancementHolderProxy.INSTANCE.id(advancement), names, obtained, progress.isDone());
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

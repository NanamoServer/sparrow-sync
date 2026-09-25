package net.momirealms.sparrow.sync.snapshot.data.type;

import com.google.gson.JsonObject;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.momirealms.sparrow.nbt.*;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.*;
import net.momirealms.sparrow.sync.proxy.minecraft.network.ConnectionProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.server.PlayerAdvancementsProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerJsonFile;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerJsonStorage;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.util.GsonHelper;
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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;

public final class AdvancementsDataType implements NativePlayerDataType<AdvancementsDataType.Advancements> {
    public static final DataKey ADVANCEMENTS = DataKey.sparrow("advancements");

    private static final boolean NATIVE_APPLY_SUPPORTED = VersionHelper.hasPaperPatch && VersionHelper.isOrAbove1_21_7; // Paper 在此版本起支持延后构造玩家
    private static final String IDS_KEY = "ids";
    private static final String CRITERIA_KEY = "criteria";
    private static final String COUNTS_KEY = "counts";
    private static final String OBTAINED_KEY = "obtained";
    private static final String DONE_KEY = "done";
    private static final int INDEX_THRESHOLD = 8; // 条件较多时建立哈希索引, 较少时线性查找
    private static final AdvancementValue[] EMPTY_VALUES = new AdvancementValue[0];
    private static final DateTimeFormatter OBTAINED_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final AdvancementSlots advancementSlots = new AdvancementSlots(); // 全服共享的固定成就槽位, 供各玩家位图使用

    @Override
    @NotNull
    public DataKey key() {
        return ADVANCEMENTS;
    }

    /**
     * 在首次应用和 flush 前安装进度跟踪器, 已安装时直接复用.
     * @param player 已进入 Join 事件但尚未应用快照的玩家
     */
    public void injectTracker(@NotNull Player player) {
        // 跟踪器必须安装到 Bukkit Player 实际持有的 PlayerAdvancements
        ServerPlayer handle = handle(player);
        PlayerAdvancements advancements = handle.getAdvancements();
        Set<Object> progressChanged = PlayerAdvancementsProxy.INSTANCE.getProgressChanged(advancements);
        if (progressChanged instanceof AdvancementProgressChangedWrapperSet) return;

        // 包装 progressChanged, 记录成就变化
        AdvancementProgressChangedWrapperSet tracking = new AdvancementProgressChangedWrapperSet(progressChanged, PlayerAdvancementsProxy.INSTANCE.getProgress(advancements), this.advancementSlots);
        PlayerAdvancementsProxy.INSTANCE.setProgressChanged(advancements, tracking);
    }

    /**
     * 采集有实际进度的成就, 返回值不引用玩家可变状态.
     * <strong>可能复用缓存结果, 数组及元素均只读</strong>.
     */
    @Override
    @NotNull
    public Advancements capture(@NotNull Player player, @NotNull CaptureMode mode) {
        ServerPlayer handle = handle(player);
        PlayerAdvancements advancements = handle.getAdvancements();
        Map<Object, Object> progress = PlayerAdvancementsProxy.INSTANCE.getProgress(advancements);
        Set<Object> progressChanged = PlayerAdvancementsProxy.INSTANCE.getProgressChanged(advancements);
        AdvancementProgressChangedWrapperSet tracking = progressChanged instanceof AdvancementProgressChangedWrapperSet current ? current : null;
        // 候选记录完整且布局稳定时只读取候选槽位
        AdvancementSlots.Layout layout = tracking != null && tracking.complete() ? this.advancementSlots.current() : null;
        boolean keepUnknown = PluginConfig.synchronization$advancements().keepUnknownAdvancements();
        if (layout != null) {
            Advancements cached = tracking.capture(layout, keepUnknown);
            if (cached != null) return cached;
        }
        long[] candidates = layout == null ? null : tracking.candidates();
        // 缓存正忙时独立采集, 布局不稳定或候选不全时扫描完整 Map
        Advancements captured = layout == null ? captureDense(progress) : captureSparse(progress, candidates, layout);
        if (tracking == null || !keepUnknown) return captured;
        // 将本服进度与保留的未知成就合并
        return mergeRetained(captured, tracking.retainedUnknown(), layout, candidates);
    }

    /** 只采集候选槽位中仍有进度的成就, 按当前 holder 和 progress 检查有效性. */
    static Advancements captureSparse(Map<Object, Object> progress, long[] candidates, AdvancementSlots.Layout layout) {
        // 按候选数量分配数组, 实际结果不会超过此数量
        int capacity = 0;
        for (int i = 0; i < candidates.length; i++) {
            capacity += Long.bitCount(candidates[i]);
        }
        AdvancementValue[] captured = new AdvancementValue[capacity];
        int count = 0;
        for (int i = 0; i < candidates.length; i++) {
            long word = candidates[i];
            while (word != 0L) {
                // 每轮取出并清除最低置位, 逐个访问候选槽位
                int bit = Long.numberOfTrailingZeros(word);
                Object holder = layout.holder((i << 6) + bit);
                // 已删除的成就没有 holder, 已撤销的进度返回 null
                if (holder != null && progress.get(holder) instanceof AdvancementProgress current) {
                    AdvancementValue value = captureProgress(holder, current);
                    if (value != null) captured[count++] = value;
                }
                word &= word - 1L;
            }
        }
        return new Advancements(count == captured.length ? captured : Arrays.copyOf(captured, count));
    }

    /** 扫描完整 progress Map, 仅保留至少完成一个条件的成就. */
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

    // 本服进度覆盖同 ID 的保留值, 其余未知进度继续保存
    static Advancements mergeRetained(Advancements captured, AdvancementValue[] retained, @Nullable AdvancementSlots.Layout layout, long[] candidates) {
        if (retained.length == 0) return captured;
        AdvancementValue[] current = captured.values();
        AdvancementValue[] merged = Arrays.copyOf(current, current.length + retained.length);
        int count = current.length;
        for (int i = 0; i < retained.length; i++) {
            AdvancementValue unknown = retained[i];
            boolean present;
            if (layout == null) {
                present = false;
                for (int j = 0; j < current.length; j++) {
                    if (current[j].id().equals(unknown.id())) {
                        present = true;
                        break;
                    }
                }
            } else {
                // 本服出现过进度的成就以本服为准, 全部撤销后也不恢复旧远端值
                int slot = layout.slot(unknown.id());
                int word = slot >>> 6;
                present = slot >= 0 && word < candidates.length && (candidates[word] & (1L << (slot & 63))) != 0L;
            }
            if (!present) {
                merged[count++] = unknown;
            }
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
                // obtained 使用毫秒时间
                obtained[criterionIndex++] = times[j].truncatedTo(ChronoUnit.SECONDS).toEpochMilli();
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
    public Advancements decode(@NotNull Tag data) throws IOException {
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
                obtained[j] = Instant.ofEpochMilli(storedObtained[criterionIndex]).truncatedTo(ChronoUnit.SECONDS);
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
        // 按 ID 查找目标进度, 已匹配的本服条目从索引移除
        Map<Object, CapturedValue> captured = index(value.values());
        Map<Object, Object> progressByAdvancement = proxy.getProgress(playerAdvancements);
        Set<Object> progressChanged = proxy.getProgressChanged(playerAdvancements);
        AdvancementProgressChangedWrapperSet tracking = progressChanged instanceof AdvancementProgressChangedWrapperSet current ? current : null;
        AdvancementSlots.Layout layout = tracking == null ? null : this.advancementSlots.current();
        boolean changed;
        // 候选完整时只扫描候选槽位
        if (tracking != null && tracking.complete() && layout != null) {
            changed = applySparse(playerAdvancements, progressByAdvancement, captured, progressChanged, tracking.candidates(), layout);
        } else {
            changed = applyDense(playerAdvancements, progressByAdvancement, captured, progressChanged);
        }
        // 剩余项是本服找不到的成就, 按配置保留供后续保存
        if (tracking != null) {
            tracking.retainedUnknown(PluginConfig.synchronization$advancements().keepUnknownAdvancements() ? remainingValues(captured) : EMPTY_VALUES);
        }
        if (!changed) return;

        // 全部条件更新完后统一 flush
        if (VersionHelper.isOrAbove1_21_5) {
            proxy.flushDirty$0(playerAdvancements, handle, false);
        } else {
            proxy.flushDirty(playerAdvancements, handle);
        }
    }

    /**
     * 处理本地候选和远端成就, 更新新增、撤销和已变更的进度.
     * @param captured <strong>本次调用独占的索引, 已匹配条目会被移除</strong>
     * @return 任一条件发生变化时为 true
     */
    private static boolean applySparse(PlayerAdvancements playerAdvancements, Map<Object, Object> progressByAdvancement, Map<Object, CapturedValue> captured, Set<Object> progressChanged, long[] candidates, AdvancementSlots.Layout layout) {
        boolean changed = false;
        // 先处理本地候选, 远端没有的成就撤销原进度
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
        // 处理远端独有的成就, 匹配成功后从索引移除
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
     * 遍历完整 progress Map, 更新与目标不同的进度.
     * @return 任一条件发生变化时为 true
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
    public boolean shouldApply(@NotNull PlayerSession session) {
        // 已提前构造的 PlayerAdvancements 读过本地 JSON, 留到 Join 更新现有对象
        return NATIVE_APPLY_SUPPORTED
                && PluginConfig.synchronization$nativeAsyncApply().advancements()
                && ConnectionProxy.INSTANCE.getSavedPlayerForLegacyEvents(session.connection()) == null;
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull Advancements value) throws IOException {
        AdvancementSlots.Layout layout = null;
        // 无法取得稳定布局时留到玩家线程应用
        if (PluginConfig.synchronization$advancements().keepUnknownAdvancements()) {
            layout = this.advancementSlots.current();
            if (layout == null) return NativeApplyResult.NOT_APPLIED;
        }

        NativeEncoding encoded = encodeNativeJson(value, layout);
        if (!PlayerJsonStorage.materialize(session.uuid(), PlayerJsonFile.ADVANCEMENTS, encoded.json())) {
            throw new IOException("atomic advancements JSON replacement failed or is not supported");
        }
        // 按配置保留本服未知的成就
        return layout == null
                ? NativeApplyResult.APPLIED_EXTERNAL
                : NativeApplyResult.APPLIED_EXTERNAL.withHandoff(this.nativeHandoff(layout, value, encoded.unknown()));
    }

    // 回调保留到 Join, 期间注册表变化时按完整快照重新应用
    private Consumer<Player> nativeHandoff(AdvancementSlots.Layout layout, Advancements snapshot, AdvancementValue[] unknown) {
        return player -> {
            if (this.advancementSlots.current() != layout) {
                this.apply(player, snapshot);
                return;
            }
            PlayerAdvancements advancements = handle(player).getAdvancements();
            AdvancementProgressChangedWrapperSet tracking = (AdvancementProgressChangedWrapperSet) PlayerAdvancementsProxy.INSTANCE.getProgressChanged(advancements);
            tracking.retainedUnknown(unknown);
        };
    }

    static NativeEncoding encodeNativeJson(@NotNull Advancements value, @Nullable AdvancementSlots.Layout layout) {
        JsonObject root = new JsonObject();
        AdvancementValue[] values = value.values();
        AdvancementValue[] unknown = EMPTY_VALUES;
        int unknownCount = 0;
        for (int i = 0; i < values.length; i++) {
            AdvancementValue advancement = values[i];
            if (layout != null && layout.holder(layout.slot(advancement.id())) == null) {
                if (unknownCount == unknown.length) {
                    int capacity = unknown.length == 0 ? Math.min(4, values.length) : Math.min(unknown.length << 1, values.length);
                    unknown = Arrays.copyOf(unknown, capacity);
                }
                unknown[unknownCount++] = advancement;
                continue;
            }
            appendNativeJson(root, advancement);
        }
        root.addProperty("DataVersion", VersionHelper.WORLD_VERSION);
        byte[] json = GsonHelper.DEFAULT_GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        return new NativeEncoding(json, unknownCount == unknown.length ? unknown : Arrays.copyOf(unknown, unknownCount));
    }

    private static void appendNativeJson(JsonObject root, AdvancementValue advancement) {
        JsonObject criteria = new JsonObject();
        String[] names = advancement.criteria();
        Instant[] obtained = advancement.obtained();
        for (int i = 0; i < names.length; i++) {
            criteria.addProperty(names[i], OBTAINED_TIME_FORMAT.format(obtained[i]));
        }
        JsonObject progress = new JsonObject();
        progress.add("criteria", criteria);
        progress.addProperty("done", advancement.done());
        root.add(advancement.id().toString(), progress);
    }

    private static Map<Object, CapturedValue> index(AdvancementValue[] values) {
        Map<Object, CapturedValue> indexed = new HashMap<>(values.length * 2);
        for (int i = 0; i < values.length; i++) {
            AdvancementValue value = values[i];
            String[] criteria = value.criteria();
            // 条件较少时线性查找, 较多时建立哈希索引
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
            // 原版文件只保留整秒, 比较时忽略小数秒
            if (current == expected || current != null && expected != null && current.getEpochSecond() == expected.getEpochSecond()) {
                continue;
            }

            // 首次发现差异时记录原完成状态, 此时尚未修改进度
            if (!changed) {
                wasDone = progress.isDone();
                changed = true;
            }
            // 直接修改原对象, 不触发 award/revoke 的事件、奖励和广播
            CriterionProgressProxy.INSTANCE.setObtained(criterion, expected == null ? null : expected.truncatedTo(ChronoUnit.SECONDS));
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
        if (VersionHelper.isOrAbove26_2) {
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
    static AdvancementValue captureProgress(Object advancement, AdvancementProgress progress) {
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
            obtained[count] = time.truncatedTo(ChronoUnit.SECONDS);
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

    record NativeEncoding(byte @NotNull [] json, @NotNull AdvancementValue @NotNull [] unknown) {
    }

    private record CapturedValue(AdvancementValue value, @Nullable Map<String, Instant> index) {
    }
}

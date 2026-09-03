package net.momirealms.sparrow.sync.snapshot.data.type;

import com.google.gson.JsonObject;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.momirealms.sparrow.nbt.ByteArrayTag;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.IntArrayTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.LongArrayTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementHolderProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.CriterionListenerProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.CriterionProgressProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.CriterionProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.TriggerInstanceKeyProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.server.PlayerAdvancementsProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerJsonFile;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage.PlayerJsonStorage;
import net.momirealms.sparrow.sync.plugin.configuration.ServerConfig;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 同步玩家 advancement 进度, 快照只保留已完成 criterion 及其完成时间.
 * 采集值不携带活的 AdvancementProgress, 原生文件按 Mojang JSON 格式独立生成.
 * 应用时修补现有 CriterionProgress, 奖励、广播和 Bukkit advancement 事件不会被触发.
 * todo 再审
 */
public final class AdvancementsDataType implements NativePlayerDataType<AdvancementsDataType.Advancements> {
    public static final DataKey ADVANCEMENTS = DataKey.sparrow("advancements");

    private static final String IDS_KEY = "ids";
    private static final String CRITERIA_KEY = "criteria";
    private static final String COUNTS_KEY = "counts";
    private static final String OBTAINED_KEY = "obtained";
    private static final String DONE_KEY = "done";
    private static final int INDEX_THRESHOLD = 8; // criterion 数超过该值时为快照值建哈希索引, 少量时线性扫描更快
    private static final DateTimeFormatter OBTAINED_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).withZone(ZoneId.systemDefault());

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

    @Override
    @NotNull
    public Advancements capture(@NotNull Player player) {
        ServerPlayer handle = handle(player);
        Map<Object, Object> progress = PlayerAdvancementsProxy.INSTANCE.getProgress(handle.getAdvancements());
        AdvancementValue[] captured = new AdvancementValue[progress.size()];
        int count = 0;
        for (Map.Entry<Object, Object> entry : progress.entrySet()) {
            AdvancementProgress current = (AdvancementProgress) entry.getValue();
            AdvancementValue value = captureProgress(entry.getKey(), current);
            if (value != null) captured[count++] = value;
        }
        return new Advancements(count == captured.length ? captured : Arrays.copyOf(captured, count));
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

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull UUID player, @NotNull net.minecraft.nbt.CompoundTag playerData, @NotNull Advancements value) throws IOException {
        if (!ServerConfig.nativeJson() || !VersionHelper.isOrAbove1_21_7()) return NativeApplyResult.NOT_APPLIED;
        if (!PlayerJsonStorage.materialize(player, PlayerJsonFile.ADVANCEMENTS, encodeNativeJson(value))) {
            throw new IOException("atomic advancements JSON replacement failed or is not supported");
        }
        return NativeApplyResult.APPLIED_EXTERNAL;
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

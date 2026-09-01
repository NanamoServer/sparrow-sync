package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.AdvancementHolderProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.server.PlayerAdvancementsProxy;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 同步玩家 advancement 进度, 使用原版 Codec 保留 criterion 完成时间.
 * 应用时直接替换完整进度并刷新监听, 奖励与广播流程不会被触发.
 * todo 可能有问题
 */
public final class AdvancementsDataType extends CodecDataType<Map<Object, AdvancementProgress>> {
    public static final DataKey ADVANCEMENTS = DataKey.sparrow("advancements");

    static final Codec<Map<Object, AdvancementProgress>> CODEC = Codec.unboundedMap(IdentifierProxy.INSTANCE.getCodec(), AdvancementProgress.CODEC);

    public AdvancementsDataType() {
        super(ADVANCEMENTS, StorageFormat.STRUCTURED, CODEC);
    }

    @Override
    @NotNull
    protected Map<Object, AdvancementProgress> captureValue(@NotNull Player player) {
        ServerPlayer handle = handle(player);
        PlayerAdvancements playerAdvancements = handle.getAdvancements();
        Map<Object, AdvancementProgress> captured = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : PlayerAdvancementsProxy.INSTANCE.getProgress(playerAdvancements).entrySet()) {
            AdvancementProgress progress = (AdvancementProgress) entry.getValue();
            if (!progress.hasProgress()) {
                continue;
            }
            captured.put(AdvancementHolderProxy.INSTANCE.id(entry.getKey()), progress);
        }
        return captured;
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull Map<Object, AdvancementProgress> value) {
        ServerPlayer handle = handle(player);
        ServerAdvancementManager manager = handle.getServer().getAdvancements();
        PlayerAdvancements playerAdvancements = handle.getAdvancements();
        PlayerAdvancementsProxy proxy = PlayerAdvancementsProxy.INSTANCE;
        // 清理旧监听与进度, 再装入完整快照
        if (VersionHelper.isOrAbove26_2()) {
            proxy.clearTriggers(playerAdvancements);
        } else {
            proxy.stopListening(playerAdvancements);
        }
        Map<Object, Object> progressByAdvancement = proxy.getProgress(playerAdvancements);
        Set<Object> changed = proxy.getProgressChanged(playerAdvancements);
        progressByAdvancement.clear();
        changed.clear();
        for (AdvancementHolder advancement : manager.getAllAdvancements()) {
            AdvancementProgress progress = value.get(AdvancementHolderProxy.INSTANCE.id(advancement));
            if (progress == null) {
                progress = new AdvancementProgress();
            }
            progress.update(advancement.value().requirements());
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

    private static ServerPlayer handle(Player player) {
        return ((CraftPlayer) player).getHandle();
    }
}

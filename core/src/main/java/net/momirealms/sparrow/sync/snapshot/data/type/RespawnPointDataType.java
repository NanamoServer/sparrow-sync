package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Dynamic;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.Level;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.proxy.minecraft.server.level.PlayerRespawnProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.server.level.RespawnConfigProxy;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public final class RespawnPointDataType implements NativePlayerDataType<CompoundTag> {
    public static final DataKey RESPAWN_POINT = DataKey.sparrow("respawn_point");
    private static final String[] LEGACY_KEYS = {"SpawnDimension", "SpawnX", "SpawnY", "SpawnZ", "SpawnAngle", "SpawnForced"};

    @NotNull
    @Override
    public DataKey key() {
        return RESPAWN_POINT;
    }

    @NotNull
    @Override
    public CompoundTag capture(@NotNull Player player, @NotNull CaptureMode mode) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        PlayerRespawnProxy proxy = PlayerRespawnProxy.INSTANCE;
        CompoundTag root = NBT.createCompound();
        root.putInt("DataVersion", VersionHelper.WORLD_VERSION);
        if (VersionHelper.isOrAbove1_21_5) {
            Object config = proxy.getConfig(handle);
            if (config != null) root.put("respawn", RespawnConfigProxy.INSTANCE.getCodec().encodeStart(NBTOps.INSTANCE, config).getOrThrow());
        } else {
            BlockPos position = proxy.getPosition(handle);
            if (position != null) {
                root.put("SpawnDimension", Level.RESOURCE_KEY_CODEC.encodeStart(NBTOps.INSTANCE, proxy.getDimension(handle)).getOrThrow());
                root.putInt("SpawnX", position.getX());
                root.putInt("SpawnY", position.getY());
                root.putInt("SpawnZ", position.getZ());
                root.putFloat("SpawnAngle", proxy.getAngle(handle));
                root.putBoolean("SpawnForced", proxy.getForced(handle));
            }
        }
        return root;
    }

    @NotNull
    @Override
    public Tag encode(@NotNull CompoundTag value) {
        return value.deepClone();
    }

    @NotNull
    @Override
    public CompoundTag decode(@NotNull Tag data) throws IOException {
        if (!(data instanceof CompoundTag source)) throw new IOException("player data block is not a compound");
        int version = source.getInt("DataVersion", -1);
        int current = VersionHelper.WORLD_VERSION;
        if (version <= 0 || version > current) throw new IOException("unsupported player data version: " + version);
        CompoundTag root = source.deepClone();
        if (version < current) {
            root = (CompoundTag) DataFixers.getDataFixer().update(References.PLAYER, new Dynamic<>(NBTOps.INSTANCE, root), version, current).getValue();
        }
        root.putInt("DataVersion", current);
        if (VersionHelper.isOrAbove1_21_5) {
            Tag respawn = root.get("respawn");
            if (respawn != null) RespawnConfigProxy.INSTANCE.getCodec().parse(NBTOps.INSTANCE, respawn).getOrThrow(IOException::new);
        }
        return root;
    }

    @Override
    public void apply(@NotNull Player player, @NotNull CompoundTag value) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        PlayerRespawnProxy proxy = PlayerRespawnProxy.INSTANCE;
        // 直接恢复记录, 目标服尚未加载的维度也保留, 实际复活仍由原版和路由插件处理.
        if (VersionHelper.isOrAbove1_21_5) {
            Tag respawn = value.get("respawn");
            Object config = respawn == null ? null : RespawnConfigProxy.INSTANCE.getCodec().parse(NBTOps.INSTANCE, respawn).getOrThrow();
            proxy.setConfig(handle, config);
        } else {
            Tag x = value.get("SpawnX");
            BlockPos position = x == null ? null : new BlockPos(value.getInt("SpawnX"), value.getInt("SpawnY"), value.getInt("SpawnZ"));
            Tag dimension = value.get("SpawnDimension");
            proxy.setDimension(handle, dimension == null ? Level.OVERWORLD : Level.RESOURCE_KEY_CODEC.parse(NBTOps.INSTANCE, dimension).getOrThrow());
            proxy.setPosition(handle, position);
            proxy.setAngle(handle, value.getFloat("SpawnAngle", 0.0F));
            proxy.setForced(handle, value.getBoolean("SpawnForced", false));
        }
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @NotNull
    @Override
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull CompoundTag value) {
        playerData.remove("SpawnWorld");
        playerData.remove("respawn");
        for (String key : LEGACY_KEYS) playerData.remove(key);
        if (VersionHelper.isOrAbove1_21_5) {
            Tag respawn = value.get("respawn");
            if (respawn != null) playerData.put("respawn", respawn.deepClone());
        } else {
            for (String key : LEGACY_KEYS) {
                Tag tag = value.get(key);
                if (tag != null) playerData.put(key, tag.deepClone());
            }
        }
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }
}

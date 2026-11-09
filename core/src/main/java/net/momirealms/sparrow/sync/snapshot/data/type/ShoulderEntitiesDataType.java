package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Set;

public final class ShoulderEntitiesDataType implements NativePlayerDataType<ShoulderEntitiesDataType.Shoulders> {
    public static final DataKey SHOULDER_ENTITIES = DataKey.sparrow("shoulder_entities");
    private static final String LEFT = "ShoulderEntityLeft";
    private static final String RIGHT = "ShoulderEntityRight";

    @NotNull
    @Override
    public DataKey key() {
        return SHOULDER_ENTITIES;
    }

    @NotNull
    @Override
    public Set<DataKey> dependencies() {
        return Set.of(GameModeDataType.GAME_MODE, FlightStatusDataType.FLIGHT_STATUS, LocationDataType.LOCATION);
    }

    @NotNull
    @Override
    public Shoulders capture(@NotNull Player player, @NotNull CaptureMode mode) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        return new Shoulders(handle.getShoulderEntityLeft().copy(), handle.getShoulderEntityRight().copy());
    }

    @NotNull
    @Override
    public Tag encode(@NotNull Shoulders value) {
        CompoundTag root = NBT.createCompound();
        root.putInt("DataVersion", VersionHelper.WORLD_VERSION);
        root.put(LEFT, NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, value.left()).deepClone());
        root.put(RIGHT, NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, value.right()).deepClone());
        return root;
    }

    @NotNull
    @Override
    public Shoulders decode(@NotNull Tag data) throws IOException {
        if (!(data instanceof CompoundTag source)) throw new IOException("player data block is not a compound");
        int version = source.getInt("DataVersion", -1);
        int current = VersionHelper.WORLD_VERSION;
        if (version <= 0 || version > current) throw new IOException("unsupported player data version: " + version);
        CompoundTag root = source.deepClone();
        if (version < current) {
            root = (CompoundTag) DataFixers.getDataFixer().update(References.PLAYER, new Dynamic<>(NBTOps.INSTANCE, root), version, current).getValue();
        }
        root.putInt("DataVersion", current);
        if (!(root.get(LEFT) instanceof CompoundTag left) || !(root.get(RIGHT) instanceof CompoundTag right)) {
            throw new IOException("shoulder entities must contain left and right compounds");
        }
        return new Shoulders(
                (net.minecraft.nbt.CompoundTag) NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, left),
                (net.minecraft.nbt.CompoundTag) NBTOps.INSTANCE.convertTo(NbtOps.INSTANCE, right)
        );
    }

    @Override
    public void apply(@NotNull Player player, @NotNull Shoulders value) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        handle.setShoulderEntityLeft(value.left().copy());
        handle.setShoulderEntityRight(value.right().copy());
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @NotNull
    @Override
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull Shoulders value) {
        playerData.put(LEFT, NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, value.left()).deepClone());
        playerData.put(RIGHT, NbtOps.INSTANCE.convertTo(NBTOps.INSTANCE, value.right()).deepClone());
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    public record Shoulders(@NotNull net.minecraft.nbt.CompoundTag left, @NotNull net.minecraft.nbt.CompoundTag right) {
    }
}

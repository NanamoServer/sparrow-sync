package net.momirealms.sparrow.sync.snapshot.data.type;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.snapshot.data.CodecDataType;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType;
import net.momirealms.sparrow.sync.snapshot.model.BlockMeta;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class LocationDataType extends CodecDataType<LocationDataType.PlayerLocation> implements NativePlayerDataType<LocationDataType.PlayerLocation> {
    public static final DataKey LOCATION = DataKey.sparrow("location");

    public LocationDataType() {
        super(LOCATION, PlayerLocation.CODEC);
    }

    @Override
    @NotNull
    public BlockMeta meta() {
        return BlockMeta.DISCARD_UNKNOWN;
    }

    @Override
    @NotNull
    public Set<DataKey> dependencies() {
        return Set.of(FlightStatusDataType.FLIGHT_STATUS, PotionEffectsDataType.POTION_EFFECTS);
    }

    @Override
    @NotNull
    protected PlayerLocation captureValue(@NotNull Player player, @NotNull CaptureMode mode) {
        Location location = player.getLocation();
        return new PlayerLocation(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    @Override
    protected void applyValue(@NotNull Player player, @NotNull PlayerLocation value) {
        World world = player.getServer().getWorld(value.world());
        if (world == null) {
            throw new IllegalStateException("location world is not loaded: " + value.world());
        }
        Location target = new Location(world, value.x(), value.y(), value.z(), value.yaw(), value.pitch());
        // Folia 没有同步传送, 这里只发起异步传送, 不等待也不校验结果.
        if (VersionHelper.isFolia()) {
            player.teleportAsync(target);
            return;
        }
        if (!player.teleport(target)) {
            throw new IllegalStateException("location teleport was rejected: " + value.world());
        }
    }

    @Override
    public boolean shouldApply(@NotNull PlayerSession session) {
        return PluginConfig.synchronization$nativeAsyncApply().playerData();
    }

    @Override
    @NotNull
    public NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull PlayerLocation value) {
        if (value.world().isEmpty() || !Double.isFinite(value.x()) || !Double.isFinite(value.y()) || !Double.isFinite(value.z()) || !Float.isFinite(value.yaw()) || !Float.isFinite(value.pitch())) return NativeApplyResult.NOT_APPLIED;

        ListTag position = NBT.createList();
        position.add(NBT.createDouble(value.x()));
        position.add(NBT.createDouble(value.y()));
        position.add(NBT.createDouble(value.z()));
        ListTag rotation = NBT.createList();
        rotation.add(NBT.createFloat(value.yaw()));
        rotation.add(NBT.createFloat(value.pitch()));

        // Paper 优先读取 UUID, world 分支会按当前服务器的同名世界解析目标维度.
        playerData.remove("Dimension");
        playerData.remove("WorldUUIDMost");
        playerData.remove("WorldUUIDLeast");
        playerData.putString("world", value.world());
        playerData.put("Pos", position);
        playerData.put("Rotation", rotation);
        return NativeApplyResult.APPLIED_PLAYER_DATA;
    }

    public record PlayerLocation(@NotNull String world, double x, double y, double z, float yaw, float pitch) {
        public static final Codec<PlayerLocation> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("world").forGetter(PlayerLocation::world),
                Codec.DOUBLE.fieldOf("x").forGetter(PlayerLocation::x),
                Codec.DOUBLE.fieldOf("y").forGetter(PlayerLocation::y),
                Codec.DOUBLE.fieldOf("z").forGetter(PlayerLocation::z),
                Codec.FLOAT.fieldOf("yaw").forGetter(PlayerLocation::yaw),
                Codec.FLOAT.fieldOf("pitch").forGetter(PlayerLocation::pitch)
        ).apply(instance, PlayerLocation::new));
    }
}

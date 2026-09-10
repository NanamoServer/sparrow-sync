package net.momirealms.sparrow.sync.compatibility.migration.husksync;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.compatibility.migration.MigrationSource;
import net.momirealms.sparrow.sync.storage.StoredUser;
import net.momirealms.sparrow.sync.util.VersionHelper;
import net.william278.husksync.HuskSync;
import net.william278.husksync.adapter.DataAdapter.AdaptionException;
import net.william278.husksync.data.Data;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.data.Identifier;
import net.william278.husksync.database.Database;
import net.william278.husksync.user.User;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApiStatus.Internal
public final class HuskSyncSourceV4 implements MigrationSource {
    private final HuskSync huskSync;
    private final HuskSyncConverter converter;

    public HuskSyncSourceV4(@NotNull Plugin plugin, @NotNull DataRegistry registry) {
        this.huskSync = (HuskSync) plugin;
        this.converter = new HuskSyncConverter(registry);
    }

    @Override
    @NotNull
    public String id() {
        return "husksync";
    }

    @Override
    public void read(@NotNull Sink sink) throws Exception {
        Database database = this.huskSync.getDatabase();
        List<User> users = database.getAllUsers();
        for (int i = 0, size = users.size(); i < size; i++) {
            User user = users.get(i);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("HuskSync migration interrupted");
            }
            UUID player = user.getUuid();
            String name = user.getName();
            Optional<DataSnapshot.Packed> latest;
            try {
                latest = database.getLatestSnapshot(user);
            } catch (AdaptionException exception) {
                sink.reject(player, name, "read", exception, null);
                continue;
            }
            if (latest.isEmpty()) {
                continue;
            }
            DataSnapshot.Packed packed = latest.get();
            byte[] raw = null;
            PlayerData converted;
            try {
                raw = packed.asBytes(this.huskSync);
                converted = this.convert(player, name, packed);
            } catch (InterruptedException exception) {
                throw exception;
            } catch (Exception exception) {
                sink.reject(player, name, "decode/convert", exception, raw);
                continue;
            }
            // ZIP 写入异常属于整批故障, 必须越过单玩家归档边界向上传播.
            sink.accept(converted);
        }
    }

    @NotNull
    private PlayerData convert(UUID player, String name, DataSnapshot.Packed packed) throws Exception {
        DataSnapshot.Unpacked unpacked = packed.unpack(this.huskSync);
        Map<Identifier, Data> decoded = unpacked.getData();
        Map<String, Data> fields = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Data> entry : decoded.entrySet()) {
            String key = entry.getKey().getKey().toString();
            fields.put(key, entry.getValue());
        }
        Map<DataKey, Tag> data = this.converter.convert(fields);
        long timestamp = packed.getTimestamp().toInstant().toEpochMilli();
        return new PlayerData(player, new StoredUser(player, name, 0), timestamp, VersionHelper.WORLD_VERSION, data);
    }

}

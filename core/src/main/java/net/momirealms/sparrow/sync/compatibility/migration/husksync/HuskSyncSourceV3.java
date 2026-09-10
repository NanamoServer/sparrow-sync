package net.momirealms.sparrow.sync.compatibility.migration.husksync;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.compatibility.migration.MigrationSource;
import net.william278.husksync.HuskSync;
import net.william278.husksync.adapter.DataAdapter.AdaptionException;
import net.william278.husksync.data.Data;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.data.Identifier;
import net.william278.husksync.database.Database;
import net.william278.husksync.user.User;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

@ApiStatus.Internal
public final class HuskSyncSourceV3 implements MigrationSource {
    private final HuskSync huskSync;
    private final Executor syncExecutor;
    private final HuskSyncConverter converter;

    public HuskSyncSourceV3(@NotNull Plugin plugin, @NotNull Executor syncExecutor, @NotNull DataRegistry registry) {
        this.huskSync = (HuskSync) plugin;
        this.syncExecutor = syncExecutor;
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
                converted = this.convert(player, packed);
            } catch (InterruptedException | RejectedExecutionException exception) {
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
    private PlayerData convert(UUID player, DataSnapshot.Packed packed) throws Exception {
        FutureTask<PlayerData> task = new FutureTask<>(() -> {
            DataSnapshot.Unpacked unpacked = packed.unpack(this.huskSync);
            Map<Identifier, Data> decoded = unpacked.getData();
            Map<String, Data> fields = new LinkedHashMap<>();
            for (Map.Entry<Identifier, Data> entry : decoded.entrySet()) {
                String key = entry.getKey().getKey().toString();
                fields.put(key, entry.getValue());
            }
            Map<DataKey, Tag> data = this.converter.convert(fields);
            long timestamp = packed.getTimestamp().toInstant().toEpochMilli();
            // 源 User 只有名字, 没有 lastSeen; 保留诊断名, 不创建虚假的 StoredUser.
            return new PlayerData(player, null, timestamp, Bukkit.getUnsafe().getDataVersion(), data);
        });
        // 解码只操作独立物品和注册表, 在 Paper 主线程 / Folia 全局线程完成后交回 I/O worker.
        this.syncExecutor.execute(task);
        try {
            return task.get();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception failure) throw failure;
            if (cause instanceof Error failure) throw failure;
            throw new IllegalStateException(cause);
        } catch (InterruptedException exception) {
            task.cancel(false);
            throw exception;
        }
    }

}

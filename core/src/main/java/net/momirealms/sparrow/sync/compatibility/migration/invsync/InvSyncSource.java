package net.momirealms.sparrow.sync.compatibility.migration.invsync;

import net.momirealms.sparrow.sync.compatibility.migration.MigrationSource;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.util.ReflectionUtils;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static net.momirealms.sparrow.sync.compatibility.migration.invsync.InvSyncAccess.call;

@ApiStatus.Internal
public final class InvSyncSource implements MigrationSource {
    private final Plugin plugin;
    private final InvSyncAccess access;       // 访问来源私有存储成员和 Kotlin object 的桥接器
    private final InvSyncConverter converter; // 将每份来源 PlayerData 编码为 Sparrow 字段

    public InvSyncSource(@NotNull Plugin plugin, @NotNull DataRegistry registry) {
        this.plugin = plugin;
        this.access = new InvSyncAccess(plugin);
        this.converter = new InvSyncConverter(plugin, this.access, registry);
    }

    @Override
    @NotNull
    public String id() {
        return "invsync";
    }

    @Override
    public void read(@NotNull Sink sink) throws Exception {
        Object storage = call(this.plugin, "getStorageManager");
        switch (storage.getClass().getSimpleName()) {
            case "MysqlManager" -> this.readMysql(storage, sink);
            case "MongoDBManager" -> this.readMongo(storage, sink);
            default -> throw new UnsupportedOperationException("Unsupported InvSync storage: " + storage.getClass().getName());
        }
    }

    private void readMysql(Object storage, Sink sink) throws Exception {
        Object dao = InvSyncAccess.field(storage, "playerDataDao");
        Object query = call(dao, "queryBuilder");
        // ORM 只枚举 UUID, 每次交付后才读取下一位玩家正文.
        call(query, "selectColumns", String[].class, new String[]{"uuid"});
        Iterator<?> players = (Iterator<?>) call(query, "iterator");
        try (AutoCloseable ignored = (AutoCloseable) players) {
            // 当前表先按 UUID 流式读取，每位玩家完成后再向游标请求下一条记录。
            while (players.hasNext()) {
                this.checkInterrupted();
                String uuid = (String) call(players.next(), "getUuid");
                Object data = call(storage, "getPlayerData", String.class, uuid);
                if (data != null) {
                    this.accept(UUID.fromString(uuid), data, false, sink);
                }
            }
        }
        // 源存储知道旧表名称和 V1 格式，迁移仅筛掉已有当前记录的 UUID。
        List<String> legacy = (List<String>) call(storage, "getAllV1PlayerUUIDs");
        for (int i = 0; i < legacy.size(); i++) {
            this.checkInterrupted();
            String uuid = legacy.get(i);
            if (call(storage, "getPlayerData", String.class, uuid) != null) {
                continue;
            }
            Object data = call(storage, "getPlayerDataFromV1", String.class, uuid);
            if (data != null) {
                this.accept(UUID.fromString(uuid), data, true, sink);
            }
        }
    }

    private void readMongo(Object storage, Sink sink) throws Exception {
        Object collection = InvSyncAccess.field(storage, "playerDataCollection");
        Object find = call(collection, "find");
        call(find, "batchSize", int.class, 1);
        Iterator<?> players = (Iterator<?>) call(find, "iterator");
        try (AutoCloseable ignored = (AutoCloseable) players) {
            // batchSize 为 1，使来源驱动在完成当前玩家后再取得下一份 BSON 文档。
            while (players.hasNext()) {
                this.checkInterrupted();
                Object document = players.next();
                Method mapper = ReflectionUtils.setAccessible(storage.getClass().getDeclaredMethod("documentToPlayerData", document.getClass()));
                Object data = InvSyncAccess.invoke(mapper, storage, document);
                UUID uuid = UUID.fromString((String) call(data, "getUuid"));
                this.accept(uuid, data, false, sink);
            }
        }
    }

    // 转换一位玩家
    private void accept(UUID uuid, Object data, boolean legacy, Sink sink) throws Exception {
        byte[] raw = null;
        PlayerData converted;
        try {
            Object gson = call(this.plugin, "getGson");
            raw = ((String) call(gson, "toJson", Object.class, data)).getBytes(StandardCharsets.UTF_8);
            converted = this.convert(uuid, data, legacy);
        } catch (InterruptedException exception) {
            throw exception;
        } catch (Exception exception) {
            sink.reject(uuid, null, "decode/convert", exception, raw);
            return;
        }
        // Sink 的 IOException 属于 ZIP 生成故障，迁移批次需要停止并保留其上下文。
        sink.accept(converted);
    }

    // 处理旧表转换并生成 Sparrow 的迁移记录
    private PlayerData convert(UUID uuid, Object source, boolean legacy) throws Exception {
        Object data = source;
        if (legacy) {
            Object converter = this.access.singleton("bukkit.storage.V1DataConverter");
            data = call(converter, "convertV1ToV2", source.getClass(), source);
        }
        return new PlayerData(uuid, null, null, Bukkit.getUnsafe().getDataVersion(), this.converter.convert(data));
    }

    private void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("InvSync migration interrupted");
        }
    }
}

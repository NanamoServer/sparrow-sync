package net.momirealms.sparrow.sync.compatibility.migration.invsync;

import net.momirealms.sparrow.sync.compatibility.migration.MigrationSource;
import net.momirealms.sparrow.sync.snapshot.DataRegistry;
import net.momirealms.sparrow.sync.storage.StoredUser;
import net.momirealms.sparrow.sync.util.ReflectionUtils;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import static net.momirealms.sparrow.sync.compatibility.migration.invsync.InvSyncAccess.call;

@ApiStatus.Internal
public final class InvSyncSource implements MigrationSource {
    private final Plugin plugin;
    private final InvSyncConverter converter; // 将每份来源 PlayerData 编码为 Sparrow 字段

    public InvSyncSource(@NotNull Plugin plugin, @NotNull DataRegistry registry) {
        this.plugin = plugin;
        this.converter = new InvSyncConverter(plugin, new InvSyncAccess(plugin), registry);
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
        Map<UUID, String> names = this.readNames(InvSyncAccess.field(storage, "playerUUIDDataDao"), false);
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
                    UUID player = UUID.fromString(uuid);
                    this.accept(player, names.get(player), data, sink);
                }
            }
        }
    }

    private void readMongo(Object storage, Sink sink) throws Exception {
        Map<UUID, String> names = this.readNames(InvSyncAccess.field(storage, "uuidCollection"), true);
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
                this.accept(uuid, names.get(uuid), data, sink);
            }
        }
    }

    // 名字映射独立于 PlayerData; 只保留 UUID/名字, 不累计玩家正文或复制源表查询.
    private Map<UUID, String> readNames(Object source, boolean mongo) throws Exception {
        Object query = mongo ? call(call(source, "find"), "batchSize", int.class, 128) : source;
        Iterator<?> users = (Iterator<?>) call(query, "iterator");
        Map<UUID, String> names = new HashMap<>();
        try (AutoCloseable ignored = (AutoCloseable) users) {
            while (users.hasNext()) {
                this.checkInterrupted();
                Object user = users.next();
                String uuid = (String) (mongo ? call(user, "getString", String.class, "uuid") : call(user, "getUuid"));
                String name = (String) (mongo ? call(user, "getString", String.class, "_id") : call(user, "getName"));
                // 源表允许一个 UUID 留有多个历史名字但没有时间; 固定选择顺序, 避免重跑随机换名.
                names.merge(UUID.fromString(uuid), name, (first, second) -> first.compareTo(second) <= 0 ? first : second);
            }
        }
        return names;
    }

    // 转换一位玩家
    private void accept(UUID uuid, String name, Object data, Sink sink) throws Exception {
        byte[] raw = null;
        PlayerData converted;
        try {
            Object gson = call(this.plugin, "getGson");
            raw = ((String) call(gson, "toJson", Object.class, data)).getBytes(StandardCharsets.UTF_8);
            converted = new PlayerData(uuid, name == null ? null : new StoredUser(uuid, name, 0), null,
                    Bukkit.getUnsafe().getDataVersion(), this.converter.convert(data));
        } catch (InterruptedException exception) {
            throw exception;
        } catch (Exception exception) {
            sink.reject(uuid, name, "decode/convert", exception, raw);
            return;
        }
        // Sink 的 IOException 属于 ZIP 生成故障，迁移批次需要停止并保留其上下文。
        sink.accept(converted);
    }

    private void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("InvSync migration interrupted");
        }
    }
}

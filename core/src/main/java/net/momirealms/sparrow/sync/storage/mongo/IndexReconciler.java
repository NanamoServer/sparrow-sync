package net.momirealms.sparrow.sync.storage.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import net.momirealms.sparrow.sync.codec.DocumentSnapshotCodec;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class IndexReconciler {
    private static final int SCHEMA_VERSION = 1; // 索引声明的当前版本, 改动声明时一起递增
    private static final String SCHEMA_DOCUMENT_ID = "schema";
    private static final String SCHEMA_FIELD_VERSION = "version";

    // 同一玩家的快照按采集时间倒序排列, 同一时间再按 _id 排序
    private static final List<IndexDeclaration> SNAPSHOT_INDEXES = List.of(
            new IndexDeclaration(
                    new Document(DocumentSnapshotCodec.FIELD_PLAYER, 1)
                            .append(DocumentSnapshotCodec.FIELD_TIMESTAMP, -1)
                            .append(DocumentSnapshotCodec.FIELD_ID, -1),
                    false
            )
    );
    // 同名记录可以并存, lookupUser 会按 lastSeen 选择最近使用这个名字的玩家
    private static final List<IndexDeclaration> USER_INDEXES = List.of(
            new IndexDeclaration(
                    new Document(MongoStorageProvider.USER_FIELD_NAME, 1),
                    false
            )
    );

    private final PluginLogger logger;

    IndexReconciler(@NotNull PluginLogger logger) {
        this.logger = logger;
    }

    /**
     * 按当前版本准备业务索引, 数据库记录的索引版本更高时拒绝连接.
     */
    public void reconcile(@NotNull MongoCollection<Document> metaCollection, @NotNull MongoCollection<Document> userCollection, @NotNull MongoCollection<Document> snapshotCollection) {
        this.guardSchemaVersion(metaCollection);
        this.reconcileIndexes(userCollection, USER_INDEXES);
        this.reconcileIndexes(snapshotCollection, SNAPSHOT_INDEXES);
        this.commitSchemaVersion(metaCollection);
    }

    // 新版本可能已经换过索引, 旧插件继续启动会把数据库改回自己的声明
    private void guardSchemaVersion(MongoCollection<Document> metaCollection) {
        Document schema = metaCollection.find(new Document("_id", SCHEMA_DOCUMENT_ID)).first();
        int stored = schema != null && schema.get(SCHEMA_FIELD_VERSION) instanceof Number number ? number.intValue() : 0;
        if (stored > SCHEMA_VERSION) {
            this.logger.error(TranslationManager.console(LogConstants.STORAGE_SCHEMA_TOO_NEW, String.valueOf(stored), String.valueOf(SCHEMA_VERSION)));
            throw new IllegalStateException("database schema generation " + stored + " is newer than this plugin supports (" + SCHEMA_VERSION + ")");
        }
    }

    // 每次启动都以当前声明为准, 旧版本留下或手工添加的索引会在这里清掉
    private void reconcileIndexes(MongoCollection<Document> collection, List<IndexDeclaration> declared) {
        List<Document> existing = new ArrayList<>();
        collection.listIndexes().into(existing);
        // _id_ 由 MongoDB 管理, 不参与业务索引对账
        for (int i = 0; i < existing.size(); i++) {
            Document index = existing.get(i);
            String name = index.getString("name");
            if ("_id_".equals(name)) continue;
            if (declarationOf(index, declared) == null) {
                collection.dropIndex(name);
                this.logger.info(TranslationManager.console(LogConstants.STORAGE_STALE_INDEX_DROPPED, name));
            }
        }
        // 当前版本缺哪条索引就补哪条
        for (int i = 0; i < declared.size(); i++) {
            IndexDeclaration declaration = declared.get(i);
            boolean present = false;
            for (int j = 0; j < existing.size(); j++) {
                if (declarationOf(existing.get(j), List.of(declaration)) != null) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                collection.createIndex(declaration.keys(), new IndexOptions().unique(declaration.unique()));
            }
        }
    }

    // 索引都准备好后再记版本, 中途失败不会留下错误的完成标记
    private void commitSchemaVersion(MongoCollection<Document> metaCollection) {
        metaCollection.replaceOne(new Document("_id", SCHEMA_DOCUMENT_ID),
                new Document("_id", SCHEMA_DOCUMENT_ID).append(SCHEMA_FIELD_VERSION, SCHEMA_VERSION),
                new ReplaceOptions().upsert(true));
    }

    // 对账只认键顺序和 unique, 索引名不会影响结果
    @Nullable
    private static IndexDeclaration declarationOf(Document index, List<IndexDeclaration> declared) {
        Document keys = index.get("key", Document.class);
        boolean unique = Boolean.TRUE.equals(index.getBoolean("unique"));
        for (int i = 0; i < declared.size(); i++) {
            IndexDeclaration declaration = declared.get(i);
            if (declaration.unique() == unique && keysEqualInOrder(keys, declaration.keys())) {
                return declaration;
            }
        }
        return null;
    }

    // 复合索引要同时核对字段顺序和升降序, Map.equals 在这里不够用
    private static boolean keysEqualInOrder(@Nullable Document actual, Document declared) {
        if (actual == null || actual.size() != declared.size()) return false;
        Iterator<Map.Entry<String, Object>> actualEntries = actual.entrySet().iterator();
        Iterator<Map.Entry<String, Object>> declaredEntries = declared.entrySet().iterator();
        while (declaredEntries.hasNext()) {
            Map.Entry<String, Object> left = actualEntries.next();
            Map.Entry<String, Object> right = declaredEntries.next();
            if (!left.getKey().equals(right.getKey())) return false;
            if (!(left.getValue() instanceof Number actualDirection) || !(right.getValue() instanceof Number declaredDirection) || actualDirection.doubleValue() != declaredDirection.doubleValue()) {
                return false;
            }
        }
        return true;
    }

    private record IndexDeclaration(Document keys, boolean unique) {
    }
}

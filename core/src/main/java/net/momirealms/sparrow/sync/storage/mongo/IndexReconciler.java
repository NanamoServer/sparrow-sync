package net.momirealms.sparrow.sync.storage.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import net.momirealms.sparrow.sync.codec.DocumentSnapshotCodec;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.plugin.logger.LogCategory;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class IndexReconciler {
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

    // 按当前版本准备业务索引
    static void reconcile(
            @NotNull SyncLogger logger,
            @NotNull MongoCollection<Document> metaCollection,
            @NotNull MongoCollection<Document> userCollection,
            @NotNull MongoCollection<Document> snapshotCollection
    ) {
        // 新版本可能已经换过索引, 旧插件继续启动会把数据库改回自己的声明
        Document schema = metaCollection.find(new Document("_id", SCHEMA_DOCUMENT_ID)).first();
        int stored = schema != null && schema.get(SCHEMA_FIELD_VERSION) instanceof Number number ? number.intValue() : 0;
        if (stored > SCHEMA_VERSION) {
            logger.error(LogCategory.STORAGE, LogConstants.STORAGE_SCHEMA_TOO_NEW, String.valueOf(stored), String.valueOf(SCHEMA_VERSION));
            throw new IllegalStateException("database schema generation " + stored + " is newer than this plugin supports (" + SCHEMA_VERSION + ")");
        }
        // 版本门禁通过后再整理两个业务集合
        reconcileIndexes(logger, userCollection, USER_INDEXES);
        reconcileIndexes(logger, snapshotCollection, SNAPSHOT_INDEXES);
        // 索引都准备好后再记版本, 中途失败不会留下错误的完成标记
        metaCollection.replaceOne(
                new Document("_id", SCHEMA_DOCUMENT_ID),
                new Document("_id", SCHEMA_DOCUMENT_ID).append(SCHEMA_FIELD_VERSION, SCHEMA_VERSION),
                new ReplaceOptions().upsert(true)
        );
    }

    // 每次启动都以当前声明为准, 旧版本留下或手工添加的索引会在这里清掉
    private static void reconcileIndexes(SyncLogger logger, MongoCollection<Document> collection, List<IndexDeclaration> declarations) {
        List<Document> existing = new ArrayList<>();
        collection.listIndexes().into(existing);
        // _id_ 由 MongoDB 管理, 不参与业务索引对账
        for (int i = 0; i < existing.size(); i++) {
            Document index = existing.get(i);
            String name = index.getString("name");
            if ("_id_".equals(name)) continue;
            boolean declared = false;
            for (int j = 0; j < declarations.size(); j++) {
                if (matches(index, declarations.get(j))) {
                    declared = true;
                    break;
                }
            }
            if (!declared) {
                collection.dropIndex(name);
                logger.file(LogCategory.STORAGE, null, null, LogConstants.STORAGE_STALE_INDEX_DROPPED, name);
            }
        }
        // 当前版本缺哪条索引就补哪条
        for (int i = 0; i < declarations.size(); i++) {
            IndexDeclaration declaration = declarations.get(i);
            boolean present = false;
            for (int j = 0; j < existing.size(); j++) {
                if (matches(existing.get(j), declaration)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                collection.createIndex(declaration.keys(), new IndexOptions().unique(declaration.unique()));
            }
        }
    }

    // 对账只认 unique, 字段顺序和升降序, 索引名不会影响结果
    private static boolean matches(Document index, IndexDeclaration declaration) {
        Document actualKeys = index.get("key", Document.class);
        boolean actualUnique = Boolean.TRUE.equals(index.getBoolean("unique"));
        if (declaration.unique() != actualUnique || actualKeys == null || actualKeys.size() != declaration.keys().size()) return false;
        Iterator<Map.Entry<String, Object>> actualEntries = actualKeys.entrySet().iterator();
        Iterator<Map.Entry<String, Object>> declaredEntries = declaration.keys().entrySet().iterator();
        while (declaredEntries.hasNext()) {
            Map.Entry<String, Object> actual = actualEntries.next();
            Map.Entry<String, Object> declared = declaredEntries.next();
            if (!actual.getKey().equals(declared.getKey())) return false;
            if (!(actual.getValue() instanceof Number actualDirection) || !(declared.getValue() instanceof Number declaredDirection) || actualDirection.doubleValue() != declaredDirection.doubleValue()) {
                return false;
            }
        }
        return true;
    }

    private record IndexDeclaration(Document keys, boolean unique) {
    }
}

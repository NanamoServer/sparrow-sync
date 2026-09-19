package net.momirealms.sparrow.sync.storage.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.plugin.logger.JavaPluginLogger;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "sparrow.test.database", matches = "true", disabledReason = "Database integration tests are off by default; run with -Psparrow.test.database=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoMapStorageTest {
    private final SyncLogger logger = new SyncLogger(new JavaPluginLogger(Logger.getAnonymousLogger()));
    private MongoClient client;
    private MongoDatabase database;
    private ExecutorService executor;
    private String prefix;
    private MongoMapStorage source;
    private MongoMapStorage receiver;

    @BeforeAll
    void connect() {
        this.client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=2000");
        this.database = this.client.getDatabase("sparrow_sync_maps_it_" + UUID.randomUUID().toString().replace("-", ""));
        this.database.runCommand(new Document("ping", 1));
        this.executor = Executors.newFixedThreadPool(8);
    }

    @BeforeEach
    void prepare() {
        this.prefix = "it_" + UUID.randomUUID().toString().replace("-", "") + "_";
        this.source = this.storage(this.prefix);
        this.receiver = this.storage(this.prefix);
    }

    private MongoMapStorage storage(String prefix) {
        IndexReconciler.reconcile(this.logger, this.database, prefix);
        MongoMapStorage result = new MongoMapStorage(this.database, prefix, this.executor);
        result.initialize();
        return result;
    }

    @AfterAll
    void close() {
        if (this.executor != null) {
            this.executor.close();
        }
        if (this.database != null) {
            this.database.drop();
        }
        if (this.client != null) {
            this.client.close();
        }
    }

    @Test
    void concurrentRegistrationPublishesOneCompleteMapping() {
        MapSource origin = new MapSource("A", 1);
        List<CompletableFuture<StoredMap>> pending = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            pending.add(this.source.register(origin, data(i)));
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        StoredMap registered = pending.getFirst().join();
        StoredMap winner = this.source.find(registered.identity().globalId()).join().orElseThrow();
        for (int i = 0; i < pending.size(); i++) {
            assertEquals(winner, pending.get(i).join());
        }
        assertEquals(1, this.database.getCollection(this.prefix + "maps").countDocuments());
        Document document = this.database.getCollection(this.prefix + "maps").find(eq("_id", winner.identity().globalId())).first();
        assertNotNull(document);
        assertFalse(document.containsKey("global_id"));
        assertEquals(Set.of("_id_", "map_source", "map_updated_at"), Set.copyOf(this.database.getCollection(this.prefix + "maps").listIndexes().map(index -> index.getString("name")).into(new ArrayList<>())));
        assertEquals(winner, this.source.register(origin, data(99)).join());
        assertEquals(winner, this.receiver.find(winner.identity().globalId()).join().orElseThrow());
    }

    @Test
    void separatesOriginsAndCollectionPrefixesAndMatchesCompleteIdentity() {
        StoredMap a = this.source.register(new MapSource("A", 0), data(1)).join();
        StoredMap b = this.receiver.register(new MapSource("B", 0), data(2)).join();
        assertEquals(-1, a.identity().globalId());
        assertEquals(-2, b.identity().globalId());
        MongoMapStorage other = this.storage("other-" + this.prefix);
        assertEquals(-1, other.register(new MapSource("A", 0), data(3)).join().identity().globalId());
        assertEquals(a, this.receiver.register(a.identity().source(), data(4)).join());
        MapIdentity wrongOwner = new MapIdentity(new MapSource("B", 0), a.identity().globalId());
        assertThrows(CompletionException.class, () -> this.receiver.update(wrongOwner, data(4)).join());
        other.update(a.identity(), data(4)).join();
        assertEquals(data(1), this.source.find(-1).join().orElseThrow().data());
        assertEquals(data(4), other.find(-1).join().orElseThrow().data());
        this.source.update(a.identity(), data(5)).join();
        assertEquals(data(5), this.receiver.find(a.identity().globalId()).join().orElseThrow().data());
        assertTrue(this.source.find(-100).join().isEmpty());
    }

    @Test
    void separatesDatabasesWithTheSameCollectionPrefix() {
        MongoDatabase otherDatabase = this.client.getDatabase(this.database.getName() + "_other");
        try {
            IndexReconciler.reconcile(this.logger, otherDatabase, this.prefix);
            MongoMapStorage other = new MongoMapStorage(otherDatabase, this.prefix, this.executor);
            other.initialize();
            StoredMap stored = this.source.register(new MapSource("A", 0), data(1)).join();
            assertTrue(other.find(-1).join().isEmpty());
            StoredMap otherStored = other.register(stored.identity().source(), data(2)).join();
            assertEquals(-1, otherStored.identity().globalId());
            other.update(otherStored.identity(), data(3)).join();
            assertEquals(stored, this.source.find(-1).join().orElseThrow());
            assertEquals(data(3), other.find(-1).join().orElseThrow().data());
        } finally {
            otherDatabase.drop();
        }
    }

    @Test
    void doesNotUpsertMissingIdentityOrReplaceAnExistingMapping() {
        StoredMap a = this.source.register(new MapSource("A", 1), data(1)).join();
        MapIdentity wrong = new MapIdentity(new MapSource("A", 2), a.identity().globalId());
        assertThrows(CompletionException.class, () -> this.source.update(wrong, data(2)).join());
        this.database.getCollection(this.prefix + "meta").updateOne(eq("_id", "maps"), set("sequence", 0L));
        assertThrows(CompletionException.class, () -> this.source.register(new MapSource("A", 2), data(2)).join());
        assertEquals(a, this.source.find(a.identity().globalId()).join().orElseThrow());
    }

    @Test
    void stopsAtMinimumIntWithoutWrappingOrReusingIds() {
        this.database.getCollection(this.prefix + "meta").updateOne(eq("_id", "maps"), set("sequence", -(long) Integer.MIN_VALUE - 1));
        StoredMap last = this.source.register(new MapSource("A", 1), data(1)).join();
        assertEquals(Integer.MIN_VALUE, last.identity().globalId());
        assertThrows(CompletionException.class, () -> this.source.register(new MapSource("A", 2), data(2)).join());
        assertEquals(-(long) Integer.MIN_VALUE, this.database.getCollection(this.prefix + "meta").find(eq("_id", "maps")).first().getLong("sequence"));
    }

    @Test
    void malformedPayloadFailsAndReopeningKeepsTheMapping() {
        StoredMap a = this.source.register(new MapSource("A", 7), data(7)).join();
        assertEquals(a, this.storage(this.prefix).find(a.identity().globalId()).join().orElseThrow());
        this.database.getCollection(this.prefix + "maps").updateOne(eq("_id", a.identity().globalId()), set("data", new Binary(new byte[0])));
        assertThrows(CompletionException.class, () -> this.receiver.find(a.identity().globalId()).join());
    }

    @Test
    void timestampsChangeOnlyWhenContentIsWritten() {
        long before = System.currentTimeMillis();
        StoredMap stored = this.source.register(new MapSource("time", 1), data(1)).join();
        var maps = this.database.getCollection(this.prefix + "maps");
        long created = maps.find(eq("_id", stored.identity().globalId())).first().getLong("updated_at");
        assertTrue(created >= before && created <= System.currentTimeMillis());
        maps.updateOne(eq("_id", stored.identity().globalId()), set("updated_at", 5L));
        assertEquals(stored, this.receiver.find(stored.identity().globalId()).join().orElseThrow());
        assertEquals(stored, this.source.register(stored.identity().source(), data(2)).join());
        assertEquals(5L, maps.find(eq("_id", stored.identity().globalId())).first().getLong("updated_at"));
        MapIdentity wrong = new MapIdentity(new MapSource("wrong", 1), stored.identity().globalId());
        assertThrows(CompletionException.class, () -> this.source.update(wrong, data(3)).join());
        assertEquals(5L, maps.find(eq("_id", stored.identity().globalId())).first().getLong("updated_at"));
        before = System.currentTimeMillis();
        MapData updated = new MapData(4441, data(4).getTag());
        this.source.update(stored.identity(), updated).join();
        long changed = maps.find(eq("_id", stored.identity().globalId())).first().getLong("updated_at");
        assertTrue(changed >= before && changed <= System.currentTimeMillis());
        assertEquals(updated, this.receiver.find(stored.identity().globalId()).join().orElseThrow().data());
    }

    @Test
    void missingOrWrongTimestampCannotBeReadOrRepairedByAnUpdate() {
        StoredMap stored = this.source.register(new MapSource("legacy", 1), data(1)).join();
        var maps = this.database.getCollection(this.prefix + "maps");
        Object[] invalid = {null, 1, 1.0, "1"};
        for (int i = 0; i < invalid.length; i++) {
            maps.updateOne(eq("_id", stored.identity().globalId()), invalid[i] == null ? unset("updated_at") : set("updated_at", invalid[i]));
            Document original = maps.find(eq("_id", stored.identity().globalId())).first();
            assertThrows(CompletionException.class, () -> this.source.find(stored.identity().globalId()).join());
            assertThrows(CompletionException.class, () -> this.source.register(stored.identity().source(), data(2)).join());
            assertThrows(CompletionException.class, () -> this.source.update(stored.identity(), data(2)).join());
            assertEquals(original, maps.find(eq("_id", stored.identity().globalId())).first());
        }
    }

    @Test
    void timestampRangeUsesItsIndexAndEncodingFailureKeepsTheRecord() {
        StoredMap first = this.source.register(new MapSource("range", 1), data(1)).join();
        StoredMap second = this.source.register(new MapSource("range", 2), data(2)).join();
        var maps = this.database.getCollection(this.prefix + "maps");
        maps.updateOne(eq("_id", first.identity().globalId()), set("updated_at", 10L));
        maps.updateOne(eq("_id", second.identity().globalId()), set("updated_at", 20L));
        assertEquals(List.of(second.identity().globalId()), maps.find(and(gte("updated_at", 20L), lte("updated_at", 20L))).hintString("map_updated_at")
                .map(document -> document.getInteger("_id")).into(new ArrayList<>()));
        CompoundTag tag = data(3).getTag();
        tag.putString("oversizedString", "a".repeat(70_000));
        MapData invalid = new MapData(4440, tag);
        Document original = maps.find(eq("_id", first.identity().globalId())).first();
        assertThrows(CompletionException.class, () -> this.source.update(first.identity(), invalid).join());
        assertEquals(original, maps.find(eq("_id", first.identity().globalId())).first());
        long sequence = this.database.getCollection(this.prefix + "meta").find(eq("_id", "maps")).first().getLong("sequence");
        assertThrows(CompletionException.class, () -> this.source.register(new MapSource("invalid", 9), invalid).join());
        assertEquals(sequence, this.database.getCollection(this.prefix + "meta").find(eq("_id", "maps")).first().getLong("sequence"));
    }

    private static MapData data(int color) {
        CompoundTag tag = NBT.createCompound();
        tag.putString("dimension", "minecraft:overworld");
        tag.putInt("xCenter", 0);
        tag.putInt("zCenter", 0);
        byte[] colors = new byte[MapData.PIXEL_COUNT];
        colors[0] = (byte) color;
        tag.putByteArray("colors", colors);
        return new MapData(4440, tag);
    }
}

package net.momirealms.sparrow.sync.storage.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.map.MapData;
import net.momirealms.sparrow.sync.map.MapIdentity;
import net.momirealms.sparrow.sync.map.MapSource;
import net.momirealms.sparrow.sync.map.StoredMap;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoMapStorageTest {
    private MongoClient client;
    private MongoDatabase database;
    private ExecutorService executor;
    private String cluster;
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
        this.cluster = UUID.randomUUID().toString();
        this.source = this.storage(this.cluster, "A");
        this.receiver = this.storage(this.cluster, "B");
    }

    private MongoMapStorage storage(String clusterId, String ownerId) {
        MongoMapStorage result = new MongoMapStorage(this.database, "it_", clusterId, ownerId, this.executor);
        result.initialize().join();
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
        StoredMap winner = this.source.find(origin).join().orElseThrow();
        for (int i = 0; i < pending.size(); i++) {
            assertEquals(winner, pending.get(i).join());
        }
        assertEquals(1, this.database.getCollection("it_maps").countDocuments(eq("cluster", this.cluster)));
        assertEquals(winner, this.source.register(origin, data(99)).join());
        assertEquals(winner, this.receiver.find(winner.identity().globalId()).join().orElseThrow());
    }

    @Test
    void separatesOriginsAndClustersAndRestrictsUploads() {
        StoredMap a = this.source.register(new MapSource("A", 0), data(1)).join();
        StoredMap b = this.receiver.register(new MapSource("B", 0), data(2)).join();
        assertEquals(-1, a.identity().globalId());
        assertEquals(-2, b.identity().globalId());
        MongoMapStorage other = this.storage("other-" + this.cluster, "A");
        assertEquals(-1, other.register(new MapSource("A", 0), data(3)).join().identity().globalId());
        assertThrows(CompletionException.class, () -> this.receiver.register(a.identity().source(), data(4)).join());
        assertThrows(CompletionException.class, () -> this.receiver.update(a.identity(), data(4)).join());
        assertThrows(CompletionException.class, () -> other.update(a.identity(), data(4)).join());
        this.source.update(a.identity(), data(5)).join();
        assertEquals(data(5), this.receiver.find(a.identity().globalId()).join().orElseThrow().data());
        assertTrue(this.source.find(-100).join().isEmpty());
    }

    @Test
    void doesNotUpsertMissingIdentityOrReplaceAnExistingMapping() {
        StoredMap a = this.source.register(new MapSource("A", 1), data(1)).join();
        MapIdentity wrong = new MapIdentity(this.cluster, new MapSource("A", 2), a.identity().globalId());
        assertThrows(CompletionException.class, () -> this.source.update(wrong, data(2)).join());
        this.database.getCollection("it_map_counters").updateOne(eq("_id", this.cluster), set("sequence", 0L));
        assertThrows(CompletionException.class, () -> this.source.register(new MapSource("A", 2), data(2)).join());
        assertEquals(a, this.source.find(a.identity().globalId()).join().orElseThrow());
    }

    @Test
    void stopsAtMinimumIntWithoutWrappingOrReusingIds() {
        this.database.getCollection("it_map_counters").updateOne(eq("_id", this.cluster), set("sequence", -(long) Integer.MIN_VALUE - 1));
        StoredMap last = this.source.register(new MapSource("A", 1), data(1)).join();
        assertEquals(Integer.MIN_VALUE, last.identity().globalId());
        assertThrows(CompletionException.class, () -> this.source.register(new MapSource("A", 2), data(2)).join());
        assertEquals(-(long) Integer.MIN_VALUE, this.database.getCollection("it_map_counters").find(eq("_id", this.cluster)).first().getLong("sequence"));
    }

    @Test
    void malformedPayloadFailsAndReopeningKeepsTheMapping() {
        StoredMap a = this.source.register(new MapSource("A", 7), data(7)).join();
        assertEquals(a, this.storage(this.cluster, "A").find(a.identity().globalId()).join().orElseThrow());
        this.database.getCollection("it_maps").updateOne(eq("cluster", this.cluster), set("data", new Binary(new byte[0])));
        assertThrows(CompletionException.class, () -> this.receiver.find(a.identity().globalId()).join());
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

package net.momirealms.sparrow.sync.map.cache;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.IntTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.StringTag;
import net.momirealms.sparrow.redis.messagebroker.MessageBroker;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapIdentity;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.map.data.StoredMap;
import net.momirealms.sparrow.sync.map.message.MapInvalidationMessage;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

public final class RedisMapCache implements MapCache {
    private static final long TTL_SECONDS = 7 * 24 * 60 * 60;
    private final RedisAsyncCommands<byte[], byte[]> commands;
    private final MessageBroker<ByteBuf> broker;
    private final Executor worker;

    public RedisMapCache(@NotNull RedisAsyncCommands<byte[], byte[]> commands, @NotNull MessageBroker<ByteBuf> broker, @NotNull Executor worker) {
        this.commands = commands;
        this.broker = broker;
        this.worker = worker;
    }

    @Override
    @NotNull
    public CompletableFuture<Optional<StoredMap>> find(int globalId) {
        return this.commands.get(this.key(globalId)).toCompletableFuture().thenApplyAsync(bytes -> {
            if (bytes == null) return Optional.empty();
            try {
                CompoundTag tag = NBT.fromBytes(bytes);
                assert tag != null;
                if (!(tag.get("owner") instanceof StringTag owner) || !(tag.get("origin_id") instanceof IntTag origin)
                        || !(tag.get("global_id") instanceof IntTag global) || global.getAsInt() != globalId
                        || !(tag.get("data_version") instanceof IntTag version) || !(tag.get("data") instanceof CompoundTag data)) {
                    throw new IOException("invalid cached map identity: " + globalId);
                }
                return Optional.of(new StoredMap(new MapIdentity(new MapSource(owner.getAsString(), origin.getAsInt()), globalId), new MapData(version.getAsInt(), data)));
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, this.worker);
    }

    @Override
    @NotNull
    public CompletableFuture<Void> publish(@NotNull StoredMap map) {
        MapIdentity identity = map.identity();
        return CompletableFuture.supplyAsync(() -> {
            CompoundTag tag = NBT.createCompound();
            tag.putString("owner", identity.source().ownerId());
            tag.putInt("origin_id", identity.source().id());
            tag.putInt("global_id", identity.globalId());
            tag.putInt("data_version", map.data().dataVersion());
            tag.put("data", map.data().getTag());
            try {
                return NBT.toBytes(tag);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, this.worker).thenCompose(bytes -> this.commands.set(this.key(identity.globalId()), bytes, SetArgs.Builder.ex(TTL_SECONDS)).toCompletableFuture())
                // 等待 Redis 接受广播命令后完成发布, 各接收服随后异步刷新.
                .thenCompose(ignored -> this.commands.publish(this.broker.channel(), this.broker.encode(new MapInvalidationMessage(identity.globalId()))).toCompletableFuture())
                .thenApply(ignored -> null);
    }

    @Override
    @NotNull
    public CompletableFuture<Boolean> touch(int globalId) {
        return this.commands.expire(this.key(globalId), TTL_SECONDS).toCompletableFuture();
    }

    // 生成所选 Redis 数据库中某张地图的键.
    private byte[] key(int globalId) {
        return ("sparrow-sync:maps:" + globalId).getBytes(StandardCharsets.UTF_8);
    }
}

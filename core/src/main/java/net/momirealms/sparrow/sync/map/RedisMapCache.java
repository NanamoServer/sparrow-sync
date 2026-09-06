package net.momirealms.sparrow.sync.map;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.IntTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.StringTag;
import net.momirealms.sparrow.redis.messagebroker.MessageBroker;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** 复用现有 Redis 连接, 大块 NBT 编解码不在 Lettuce 的 I/O 线程执行. */
public final class RedisMapCache implements MapCache {
    private static final long TTL_SECONDS = 7 * 24 * 60 * 60;
    private final RedisAsyncCommands<byte[], byte[]> commands;
    private final MessageBroker<ByteBuf> broker;
    private final String clusterId;
    private final String ownerId;
    private final String prefix;
    private final Executor worker;

    public RedisMapCache(@NotNull RedisAsyncCommands<byte[], byte[]> commands, @NotNull MessageBroker<ByteBuf> broker, @NotNull String clusterId, @NotNull String ownerId, @NotNull Executor worker) {
        this.commands = commands;
        this.broker = broker;
        this.clusterId = clusterId;
        this.ownerId = ownerId;
        this.prefix = "sparrow-sync:maps:" + HexFormat.of().formatHex(clusterId.getBytes(StandardCharsets.UTF_8)) + ":";
        this.worker = worker;
    }

    @Override
    @NotNull
    public CompletableFuture<Optional<StoredMap>> find(int globalId) {
        return this.commands.get(this.key(globalId)).toCompletableFuture().thenApplyAsync(bytes -> {
            if (bytes == null) return Optional.empty();
            try {
                CompoundTag tag = NBT.fromBytes(bytes);
                if (!(tag.get("cluster") instanceof StringTag cluster) || !this.clusterId.equals(cluster.getAsString())
                        || !(tag.get("owner") instanceof StringTag owner) || !(tag.get("origin_id") instanceof IntTag origin)
                        || !(tag.get("global_id") instanceof IntTag global) || global.getAsInt() != globalId
                        || !(tag.get("data_version") instanceof IntTag version) || !(tag.get("data") instanceof CompoundTag data)) {
                    throw new IOException("invalid cached map identity: " + globalId);
                }
                return Optional.of(new StoredMap(new MapIdentity(this.clusterId, new MapSource(owner.getAsString(), origin.getAsInt()), globalId), new MapData(version.getAsInt(), data)));
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, this.worker);
    }

    @Override
    @NotNull
    public CompletableFuture<Void> publish(@NotNull StoredMap map) {
        MapIdentity identity = map.identity();
        if (!this.clusterId.equals(identity.clusterId()) || !this.ownerId.equals(identity.source().ownerId())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("only the source owner may publish map content"));
        }
        return CompletableFuture.supplyAsync(() -> {
            CompoundTag tag = NBT.createCompound();
            tag.putString("cluster", identity.clusterId());
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
                // 等待 PUBLISH 命令完成, 不把 broker 的 void 发送当成完整发布边界.
                .thenCompose(ignored -> this.commands.publish(this.broker.channel(), this.broker.encode(new MapInvalidationMessage(identity.globalId()))).toCompletableFuture())
                .thenApply(ignored -> null);
    }

    @Override
    @NotNull
    public CompletableFuture<Boolean> touch(int globalId) {
        return this.commands.expire(this.key(globalId), TTL_SECONDS).toCompletableFuture();
    }

    private byte[] key(int globalId) {
        return (this.prefix + globalId).getBytes(StandardCharsets.UTF_8);
    }
}

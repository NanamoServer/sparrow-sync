package net.momirealms.sparrow.sync.cluster.message;

import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageIdentifier;
import net.momirealms.sparrow.redis.messagebroker.RedisMessage;
import net.momirealms.sparrow.redis.messagebroker.codec.MessageCodec;
import net.momirealms.sparrow.redis.messagebroker.message.TwoWayRequestMessage;
import net.momirealms.sparrow.redis.messagebroker.util.ByteBufHelper;
import net.momirealms.sparrow.sync.cluster.HandoffManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class HandoffRequestMessage extends TwoWayRequestMessage<ByteBuf, HandoffResponseMessage> {
    public static final MessageIdentifier ID = MessageIdentifier.of("sparrow_sync", "handoff_request");
    public static final MessageCodec<ByteBuf, HandoffRequestMessage> CODEC = RedisMessage.codec(HandoffRequestMessage::write, HandoffRequestMessage::new);
    private static volatile HandoffManager service;

    private final UUID player;
    private final String targetInstanceId;

    public HandoffRequestMessage(@NotNull UUID player, @NotNull String targetInstanceId) {
        this.player = player;
        this.targetInstanceId = targetInstanceId;
    }

    private HandoffRequestMessage(ByteBuf buf) {
        super(buf);
        this.player = new UUID(buf.readLong(), buf.readLong());
        this.targetInstanceId = ByteBufHelper.readUtf8(buf, 128);
    }

    @Override
    protected void write(ByteBuf buf) {
        super.write(buf);
        buf.writeLong(this.player.getMostSignificantBits());
        buf.writeLong(this.player.getLeastSignificantBits());
        ByteBufHelper.writeUtf8(buf, this.targetInstanceId, 128);
    }

    @Override
    @NotNull
    public MessageIdentifier identifier() {
        return ID;
    }

    @Override
    @NotNull
    @ApiStatus.Internal
    public CompletableFuture<HandoffResponseMessage> handleRequest() {
        HandoffManager service = HandoffRequestMessage.service;
        return CompletableFuture.completedFuture(service == null ? null : service.answer(this.player, this.targetInstanceId));
    }

    public static void service(@NotNull HandoffManager service) {
        HandoffRequestMessage.service = service;
    }
}

package net.momirealms.sparrow.sync.cluster;

import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageIdentifier;
import net.momirealms.sparrow.redis.messagebroker.RedisMessage;
import net.momirealms.sparrow.redis.messagebroker.codec.MessageCodec;
import net.momirealms.sparrow.redis.messagebroker.message.TwoWayRequestMessage;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class HandoffRequestMessage extends TwoWayRequestMessage<ByteBuf, HandoffResponseMessage> {
    public static final MessageIdentifier ID = MessageIdentifier.of("sparrow_sync", "handoff_request");
    public static final MessageCodec<ByteBuf, HandoffRequestMessage> CODEC = RedisMessage.codec(HandoffRequestMessage::write, HandoffRequestMessage::new);
    private static volatile HandoffManager service;

    private final UUID player;

    public HandoffRequestMessage(@NotNull UUID player) {
        this.player = player;
    }

    private HandoffRequestMessage(ByteBuf buf) {
        super(buf);
        this.player = new UUID(buf.readLong(), buf.readLong());
    }

    @Override
    protected void write(ByteBuf buf) {
        super.write(buf);
        buf.writeLong(this.player.getMostSignificantBits());
        buf.writeLong(this.player.getLeastSignificantBits());
    }

    @Override
    @NotNull
    public MessageIdentifier identifier() {
        return ID;
    }

    @Override
    @NotNull
    protected CompletableFuture<HandoffResponseMessage> handleRequest() {
        HandoffManager service = HandoffRequestMessage.service;
        return CompletableFuture.completedFuture(service == null ? HandoffResponseMessage.unknown() : service.answer(this.player));
    }

    static void service(@NotNull HandoffManager service) {
        HandoffRequestMessage.service = service;
    }
}

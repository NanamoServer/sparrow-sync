package net.momirealms.sparrow.sync.session.cluster;

import net.nyana.message.libs.codec.Codec;
import net.nyana.message.message.MessageIdentifier;
import net.nyana.message.message.TwoWayRequestMessage;
import net.nyana.message.util.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class HandoffRequestMessage extends TwoWayRequestMessage<HandoffResponseMessage> {
    public static final MessageIdentifier ID = MessageIdentifier.of("sparrow_sync", "handoff_request");
    public static final Codec<FriendlyByteBuf, HandoffRequestMessage> CODEC = Codec.of(HandoffRequestMessage::write, HandoffRequestMessage::new);
    private static volatile HandoffManager service;

    private final UUID player;

    public HandoffRequestMessage(@NotNull UUID player) {
        this.player = player;
    }

    private HandoffRequestMessage(FriendlyByteBuf buf) {
        super(buf);
        this.player = new UUID(buf.readLong(), buf.readLong());
    }

    @Override
    protected void write(FriendlyByteBuf buf) {
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

package net.momirealms.sparrow.sync.message;

import io.netty.buffer.Unpooled;
import net.nyana.message.util.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HandoffMessageCodecTest {

    @Test
    void requestRoundTrip() {
        UUID player = UUID.randomUUID();
        HandoffRequestMessage message = new HandoffRequestMessage(player);
        message.setMessageId(42L);
        message.setSourceServer("serverB");
        message.setTargetServer("serverA");

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        HandoffRequestMessage.CODEC.encode(message, buf);
        HandoffRequestMessage decoded = HandoffRequestMessage.CODEC.decode(buf);

        assertEquals(42L, decoded.messageId());
        assertEquals("serverB", decoded.sourceServer());
        assertEquals("serverA", decoded.targetServer());
    }

    @Test
    void savingResponseRoundTrip() {
        HandoffResponseMessage message = HandoffResponseMessage.saving();
        message.setMessageId(7L);
        message.setSourceServer("serverA");
        message.setTargetServer("serverB");

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        HandoffResponseMessage.CODEC.encode(message, buf);
        HandoffResponseMessage decoded = HandoffResponseMessage.CODEC.decode(buf);

        assertEquals(HandoffResponseMessage.Status.SAVING, decoded.status());
        assertEquals(0L, decoded.timestamp());
    }

    @Test
    void doneResponseCarriesTimestamp() {
        HandoffResponseMessage message = HandoffResponseMessage.done(1_756_300_000_123L);
        message.setMessageId(8L);
        message.setSourceServer("serverA");
        message.setTargetServer("serverB");

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        HandoffResponseMessage.CODEC.encode(message, buf);
        HandoffResponseMessage decoded = HandoffResponseMessage.CODEC.decode(buf);

        assertEquals(HandoffResponseMessage.Status.DONE, decoded.status());
        assertEquals(1_756_300_000_123L, decoded.timestamp());
    }
}

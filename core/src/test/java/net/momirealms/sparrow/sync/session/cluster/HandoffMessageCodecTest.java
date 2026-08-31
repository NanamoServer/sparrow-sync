package net.momirealms.sparrow.sync.session.cluster;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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

        ByteBuf buf = Unpooled.buffer();
        HandoffRequestMessage.CODEC.encode(buf, message);
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

        ByteBuf buf = Unpooled.buffer();
        HandoffResponseMessage.CODEC.encode(buf, message);
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

        ByteBuf buf = Unpooled.buffer();
        HandoffResponseMessage.CODEC.encode(buf, message);
        HandoffResponseMessage decoded = HandoffResponseMessage.CODEC.decode(buf);

        assertEquals(HandoffResponseMessage.Status.DONE, decoded.status());
        assertEquals(1_756_300_000_123L, decoded.timestamp());
    }
}

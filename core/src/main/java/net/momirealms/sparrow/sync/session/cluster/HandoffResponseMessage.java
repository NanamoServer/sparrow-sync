package net.momirealms.sparrow.sync.session.cluster;

import net.nyana.message.libs.codec.Codec;
import net.nyana.message.message.MessageIdentifier;
import net.nyana.message.message.TwoWayResponseMessage;
import net.nyana.message.util.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public final class HandoffResponseMessage extends TwoWayResponseMessage {
    public static final MessageIdentifier ID = MessageIdentifier.of("sparrow_sync", "handoff_response");
    public static final Codec<FriendlyByteBuf, HandoffResponseMessage> CODEC = Codec.of(HandoffResponseMessage::write, HandoffResponseMessage::new);

    private final Status status;
    private final long timestamp;  // DONE 时为快照采集时刻, 其余状态为 0

    private HandoffResponseMessage(Status status, long timestamp) {
        this.status = status;
        this.timestamp = timestamp;
    }

    private HandoffResponseMessage(FriendlyByteBuf buf) {
        super(buf);
        this.status = Status.VALUES[buf.readByte()];
        this.timestamp = buf.readCompactLong();
    }

    @Override
    protected void write(FriendlyByteBuf buf) {
        super.write(buf);
        buf.writeByte(this.status.ordinal());
        buf.writeCompactLong(this.timestamp);
    }

    @NotNull
    public static HandoffResponseMessage saving() {
        return new HandoffResponseMessage(Status.SAVING, 0L);
    }

    @NotNull
    public static HandoffResponseMessage done(long timestamp) {
        return new HandoffResponseMessage(Status.DONE, timestamp);
    }

    @NotNull
    public static HandoffResponseMessage unknown() {
        return new HandoffResponseMessage(Status.UNKNOWN, 0L);
    }

    @NotNull
    public Status status() {
        return this.status;
    }

    public long timestamp() {
        return this.timestamp;
    }

    @Override
    @NotNull
    public MessageIdentifier identifier() {
        return ID;
    }

    /** 持有服对玩家会话进展的回答. */
    public enum Status {
        /** 会话仍在本服手里, 退出保存还没走完, 继续等. */
        SAVING,
        /** 退出保存已落库, 锁已在释放, 可以重试抢锁. */
        DONE,
        /** 本服不认识这个玩家, 锁是上一条命的残留, 直接夺走. */
        UNKNOWN;

        static final Status[] VALUES = values();
    }
}

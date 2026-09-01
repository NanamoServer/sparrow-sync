package net.momirealms.sparrow.sync.cluster;

import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageIdentifier;
import net.momirealms.sparrow.redis.messagebroker.RedisMessage;
import net.momirealms.sparrow.redis.messagebroker.codec.MessageCodec;
import net.momirealms.sparrow.redis.messagebroker.message.TwoWayResponseMessage;
import org.jetbrains.annotations.NotNull;

public final class HandoffResponseMessage extends TwoWayResponseMessage<ByteBuf> {
    public static final MessageIdentifier ID = MessageIdentifier.of("sparrow_sync", "handoff_response");
    public static final MessageCodec<ByteBuf, HandoffResponseMessage> CODEC = RedisMessage.codec(HandoffResponseMessage::write, HandoffResponseMessage::new);

    private final Status status;

    private HandoffResponseMessage(Status status) {
        this.status = status;
    }

    private HandoffResponseMessage(ByteBuf buf) {
        super(buf);
        this.status = Status.VALUES[buf.readByte()];
    }

    @Override
    protected void write(ByteBuf buf) {
        super.write(buf);
        buf.writeByte(this.status.ordinal());
    }

    @NotNull
    public static HandoffResponseMessage saving() {
        return new HandoffResponseMessage(Status.SAVING);
    }

    @NotNull
    public static HandoffResponseMessage done() {
        return new HandoffResponseMessage(Status.DONE);
    }

    @NotNull
    public static HandoffResponseMessage unknown() {
        return new HandoffResponseMessage(Status.UNKNOWN);
    }

    @NotNull
    public Status status() {
        return this.status;
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

package net.momirealms.sparrow.sync.cluster.message;

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

    /** 持有服返回的会话状态, 供等待方决定继续探测还是获取锁. */
    public enum Status {
        SAVING,  // 本服仍有会话或离线恢复任务, 继续等待.
        DONE,    // 退出快照已存入数据库, 可以重试获取锁.
        UNKNOWN; // 本服没有会话或近期保存记录, 可以尝试接管锁.

        static final Status[] VALUES = values();
    }
}

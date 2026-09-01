package net.momirealms.sparrow.sync.redis.heartbeats;

import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.redis.messagebroker.MessageIdentifier;
import net.momirealms.sparrow.redis.messagebroker.RedisMessage;
import net.momirealms.sparrow.redis.messagebroker.codec.MessageCodec;
import net.momirealms.sparrow.redis.messagebroker.message.TwoWayResponseMessage;
import net.momirealms.sparrow.redis.messagebroker.util.ByteBufHelper;
import org.jetbrains.annotations.NotNull;

/**
 * 对 {@link ServerProbeMessage} 的存活应答, 携带应答方本次启动的身份 token.
 * 发起方收到它即确认同 id 的服务器仍在线.
 */
public final class ServerProbeResponseMessage extends TwoWayResponseMessage<ByteBuf> {
    public static final MessageIdentifier ID = MessageIdentifier.of("sparrow_sync", "server_probe_response");
    public static final MessageCodec<ByteBuf, ServerProbeResponseMessage> CODEC = RedisMessage.codec(ServerProbeResponseMessage::write, ServerProbeResponseMessage::new);

    private final String token;

    ServerProbeResponseMessage(@NotNull String token) {
        this.token = token;
    }

    private ServerProbeResponseMessage(ByteBuf buf) {
        super(buf);
        this.token = ByteBufHelper.readUtf8(buf, 128);
    }

    @Override
    protected void write(ByteBuf buf) {
        super.write(buf);
        ByteBufHelper.writeUtf8(buf, this.token, 128);
    }

    @NotNull
    public String token() {
        return this.token;
    }

    @Override
    @NotNull
    public MessageIdentifier identifier() {
        return ID;
    }
}
